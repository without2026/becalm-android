package com.becalm.android.integration.local.ui.evidence

import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.entity.MeetingSpeakerPreviewEntity
import com.becalm.android.data.local.db.entity.MeetingSpeakerPreviewStatus
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.local.db.entity.UnmatchedPersonInteractionEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.ui.evidence.EvidenceImportPersistentStatus
import com.becalm.android.ui.evidence.RoomEvidenceImportStatusProjectionPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EvidenceImportStatusProjectionPortTest {

    private val db: BeCalmDatabase = LocalIntegrationSupport.inMemoryDatabase()
    private val userPrefsStore = UserPrefsStoreImpl(
        LocalIntegrationSupport.prefsDataStore("evidence-import-status-prefs"),
    )

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `pending evidence import projects processing status`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-meeting-pending",
                sourceType = SourceType.MEETING,
                syncStatus = "pending",
            ),
        )

        assertEquals(
            EvidenceImportPersistentStatus.processing(processingCount = 1, oldestStartedAt = NOW),
            projection().observeStatus().first(),
        )
    }

    @Test
    fun `awaiting consent evidence import projects consent required not processing`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-meeting-awaiting-consent",
                sourceType = SourceType.MEETING,
                syncStatus = "awaiting_consent",
            ),
        )

        assertEquals(
            EvidenceImportPersistentStatus.consentRequired(consentRequiredCount = 1),
            projection().observeStatus().first(),
        )
    }

    @Test
    fun `pending meeting speaker preview projects processing status`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.meetingSpeakerPreviewDao().upsert(
            meetingPreview(
                rawEventId = "raw-meeting-preview",
                status = MeetingSpeakerPreviewStatus.PENDING,
            ),
        )

        assertEquals(
            EvidenceImportPersistentStatus.processing(processingCount = 1, oldestStartedAt = NOW),
            projection().observeStatus().first(),
        )
    }

    @Test
    fun `meeting speaker preview ready projects review required status`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.meetingSpeakerPreviewDao().upsert(
            meetingPreview(
                rawEventId = "raw-meeting-review",
                status = MeetingSpeakerPreviewStatus.REVIEW_REQUIRED,
                speakersJson = SPEAKERS_JSON,
            ),
        )

        assertEquals(
            EvidenceImportPersistentStatus.reviewRequired(
                reviewRequiredCount = 1,
                meetingReviewRequiredCount = 1,
                personReviewRequiredCount = 0,
            ),
            projection().observeStatus().first(),
        )
    }

    @Test
    fun `empty meeting speaker preview does not project unrecoverable review required status`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.meetingSpeakerPreviewDao().upsert(
            meetingPreview(
                rawEventId = "raw-meeting-empty-review",
                status = MeetingSpeakerPreviewStatus.REVIEW_REQUIRED,
                speakersJson = "[]",
            ),
        )

        assertEquals(EvidenceImportPersistentStatus.NONE, projection().observeStatus().first())
    }

    @Test
    fun `failed raw event and failed meeting preview count as one failed evidence item`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-meeting-failed",
                sourceType = SourceType.MEETING,
                syncStatus = "failed",
            ),
        )
        db.meetingSpeakerPreviewDao().upsert(
            meetingPreview(
                rawEventId = "raw-meeting-failed",
                status = MeetingSpeakerPreviewStatus.FAILED,
            ),
        )

        assertEquals(
            EvidenceImportPersistentStatus.failed(failedCount = 1),
            projection().observeStatus().first(),
        )
    }

    @Test
    fun `failed meeting preview with synced raw event still projects one failed evidence item`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-meeting-preview-failed",
                sourceType = SourceType.MEETING,
                syncStatus = "synced",
            ),
        )
        db.meetingSpeakerPreviewDao().upsert(
            meetingPreview(
                rawEventId = "raw-meeting-preview-failed",
                status = MeetingSpeakerPreviewStatus.FAILED,
            ),
        )

        assertEquals(
            EvidenceImportPersistentStatus.failed(failedCount = 1),
            projection().observeStatus().first(),
        )
    }

    @Test
    fun `unmatched interaction projects review required status before processing`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-message-pending",
                sourceType = SourceType.MESSAGE_SCREENSHOT,
                syncStatus = "pending",
            ),
        )
        db.personIndexDao().upsertUnmatchedInteractions(
            listOf(
                UnmatchedPersonInteractionEntity(
                    id = "unmatched-1",
                    userId = USER_ID,
                    sourceType = SourceType.MEETING,
                    sourceRef = "raw:raw-message-pending",
                    interactionKind = "meeting",
                    title = "회의 녹음",
                    snippet = "김민홍님에게 자료를 보내기로 했습니다.",
                    suggestedLabel = "김민홍",
                    occurredAt = NOW,
                    createdAt = NOW,
                ),
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                SourceEventParticipantEntity(
                    id = "participant-counterparty-1",
                    userId = USER_ID,
                    sourceEventId = "raw-message-pending",
                    sourceType = SourceType.MEETING,
                    sourceRef = "meeting-file-1",
                    personId = null,
                    role = "counterparty",
                    relationToUser = "counterparty",
                    identityType = "name",
                    normalizedValue = "김민홍",
                    displayNameRaw = "김민홍",
                    emailRaw = null,
                    phoneRaw = null,
                    organizationRaw = null,
                    titleRaw = null,
                    evidence = "김민홍님에게 자료를 보내기로 했습니다.",
                    confidence = 0.72,
                    resolutionStatus = "unresolved",
                    createdAt = NOW,
                ),
            ),
        )

        assertEquals(
            EvidenceImportPersistentStatus.reviewRequired(
                reviewRequiredCount = 1,
                processingCount = 1,
                oldestStartedAt = NOW,
            ),
            projection().observeStatus().first(),
        )
    }

    @Test
    fun `meeting speaker labels do not project person review required status`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                SourceEventParticipantEntity(
                    id = "participant-unresolved-1",
                    userId = USER_ID,
                    sourceEventId = "raw-meeting-1",
                    sourceType = SourceType.MEETING,
                    sourceRef = "meeting-file-1",
                    personId = null,
                    role = "speaker",
                    relationToUser = "participant",
                    identityType = "speaker_label",
                    normalizedValue = "SPEAKER_02",
                    displayNameRaw = "SPEAKER_02",
                    emailRaw = null,
                    phoneRaw = null,
                    organizationRaw = null,
                    titleRaw = null,
                    evidence = "SPEAKER_02",
                    confidence = 0.0,
                    resolutionStatus = "unresolved",
                    createdAt = NOW,
                ),
            ),
        )

        assertEquals(EvidenceImportPersistentStatus.NONE, projection().observeStatus().first())
    }

    @Test
    fun `direct meeting counterparty participant projects one event review`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                SourceEventParticipantEntity(
                    id = "participant-counterparty-1",
                    userId = USER_ID,
                    sourceEventId = "raw-meeting-1",
                    sourceType = SourceType.MEETING,
                    sourceRef = "meeting-file-1",
                    personId = null,
                    role = "counterparty",
                    relationToUser = "counterparty",
                    identityType = "name",
                    normalizedValue = "김민홍",
                    displayNameRaw = "김민홍",
                    emailRaw = null,
                    phoneRaw = null,
                    organizationRaw = null,
                    titleRaw = null,
                    evidence = "김민홍님에게 자료를 보내기로 했습니다.",
                    confidence = 0.72,
                    resolutionStatus = "unresolved",
                    createdAt = NOW,
                ),
                SourceEventParticipantEntity(
                    id = "participant-counterparty-duplicate",
                    userId = USER_ID,
                    sourceEventId = "raw-meeting-1",
                    sourceType = SourceType.MEETING,
                    sourceRef = "meeting-file-1",
                    personId = null,
                    role = "counterparty",
                    relationToUser = "counterparty",
                    identityType = "name",
                    normalizedValue = "김민홍",
                    displayNameRaw = "김민홍",
                    emailRaw = null,
                    phoneRaw = null,
                    organizationRaw = null,
                    titleRaw = null,
                    evidence = "김민홍님에게 자료를 보내기로 했습니다.",
                    confidence = 0.72,
                    resolutionStatus = "unresolved",
                    createdAt = NOW,
                ),
            ),
        )

        assertEquals(
            EvidenceImportPersistentStatus.reviewRequired(
                reviewRequiredCount = 1,
                meetingReviewRequiredCount = 0,
                personReviewRequiredCount = 1,
            ),
            projection().observeStatus().first(),
        )
    }

    @Test
    fun `referenced meeting names and organization only rows do not project person review`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                SourceEventParticipantEntity(
                    id = "participant-mentioned-name",
                    userId = USER_ID,
                    sourceEventId = "raw-meeting-1",
                    sourceType = SourceType.MEETING,
                    sourceRef = "meeting-file-1",
                    personId = null,
                    role = "mentioned",
                    relationToUser = "referenced",
                    identityType = "name",
                    normalizedValue = "범진",
                    displayNameRaw = null,
                    emailRaw = null,
                    phoneRaw = null,
                    organizationRaw = null,
                    titleRaw = null,
                    evidence = "범진님 이야기가 언급되었습니다.",
                    confidence = 0.5,
                    resolutionStatus = "unresolved",
                    createdAt = NOW,
                ),
                SourceEventParticipantEntity(
                    id = "participant-organization",
                    userId = USER_ID,
                    sourceEventId = "raw-meeting-1",
                    sourceType = SourceType.MEETING,
                    sourceRef = "meeting-file-1",
                    personId = null,
                    role = "mentioned",
                    relationToUser = "referenced",
                    identityType = "organization",
                    normalizedValue = "오뚜기",
                    displayNameRaw = null,
                    emailRaw = null,
                    phoneRaw = null,
                    organizationRaw = "오뚜기",
                    titleRaw = null,
                    evidence = "오뚜기 사례가 언급되었습니다.",
                    confidence = 0.5,
                    resolutionStatus = "unresolved",
                    createdAt = NOW,
                ),
            ),
        )

        assertEquals(EvidenceImportPersistentStatus.NONE, projection().observeStatus().first())
    }

    @Test
    fun `repair ignores non reviewable evidence participants and removes derived unmatched rows`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.personIndexDao().upsertUnmatchedInteractions(
            listOf(
                UnmatchedPersonInteractionEntity(
                    id = "unmatched-meeting-1",
                    userId = USER_ID,
                    sourceType = SourceType.MEETING,
                    sourceRef = "raw:raw-meeting-1",
                    interactionKind = "meeting",
                    title = "회의 녹음",
                    snippet = "범진님이 언급되었습니다.",
                    suggestedLabel = "범진",
                    occurredAt = NOW,
                    createdAt = NOW,
                ),
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                SourceEventParticipantEntity(
                    id = "participant-mentioned-name",
                    userId = USER_ID,
                    sourceEventId = "raw-meeting-1",
                    sourceType = SourceType.MEETING,
                    sourceRef = "meeting-file-1",
                    personId = null,
                    role = "mentioned",
                    relationToUser = "referenced",
                    identityType = "name",
                    normalizedValue = "범진",
                    displayNameRaw = null,
                    emailRaw = null,
                    phoneRaw = null,
                    organizationRaw = null,
                    titleRaw = null,
                    evidence = "범진님 이야기가 언급되었습니다.",
                    confidence = 0.5,
                    resolutionStatus = "unresolved",
                    createdAt = NOW,
                ),
            ),
        )

        assertEquals(1, db.personIndexDao().ignoreNonReviewableEvidenceImportParticipants(USER_ID))
        assertEquals(1, db.personIndexDao().deleteEvidenceImportUnmatchedWithoutReviewableParticipants(USER_ID))

        assertEquals(EvidenceImportPersistentStatus.NONE, projection().observeStatus().first())
        assertEquals(emptyList<UnmatchedPersonInteractionEntity>(), db.personIndexDao().findUnmatchedInteractions(USER_ID, 20))
    }

    @Test
    fun `resolved evidence participants suppress stale unmatched review count and are repaired`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.personIndexDao().upsertUnmatchedInteractions(
            listOf(
                UnmatchedPersonInteractionEntity(
                    id = "unmatched-meeting-resolved",
                    userId = USER_ID,
                    sourceType = SourceType.MEETING,
                    sourceRef = "raw:raw-meeting-resolved",
                    interactionKind = "meeting",
                    title = "회의 녹음",
                    snippet = "이미 연결된 기록입니다.",
                    suggestedLabel = "김민홍",
                    occurredAt = NOW,
                    createdAt = NOW,
                ),
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                SourceEventParticipantEntity(
                    id = "participant-resolved-counterparty",
                    userId = USER_ID,
                    sourceEventId = "raw-meeting-resolved",
                    sourceType = SourceType.MEETING,
                    sourceRef = "meeting-file-resolved",
                    personId = "person-minhong",
                    role = "counterparty",
                    relationToUser = "counterparty",
                    identityType = "name",
                    normalizedValue = "김민홍",
                    displayNameRaw = "김민홍",
                    emailRaw = null,
                    phoneRaw = null,
                    organizationRaw = null,
                    titleRaw = null,
                    evidence = "김민홍님에게 자료를 보내기로 했습니다.",
                    confidence = 0.91,
                    resolutionStatus = "resolved",
                    createdAt = NOW,
                ),
            ),
        )

        assertEquals(EvidenceImportPersistentStatus.NONE, projection().observeStatus().first())
        assertEquals(1, db.personIndexDao().deleteEvidenceImportUnmatchedWithoutReviewableParticipants(USER_ID))
        assertEquals(emptyList<UnmatchedPersonInteractionEntity>(), db.personIndexDao().findUnmatchedInteractions(USER_ID, 20))
    }

    @Test
    fun `legacy evidence unmatched without participants remains reviewable`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.personIndexDao().upsertUnmatchedInteractions(
            listOf(
                UnmatchedPersonInteractionEntity(
                    id = "unmatched-meeting-legacy",
                    userId = USER_ID,
                    sourceType = SourceType.MEETING,
                    sourceRef = "raw:raw-meeting-legacy",
                    interactionKind = "meeting",
                    title = "회의 녹음",
                    snippet = "사람 연결이 필요한 오래된 기록입니다.",
                    suggestedLabel = "김민홍",
                    occurredAt = NOW,
                    createdAt = NOW,
                ),
            ),
        )

        assertEquals(
            EvidenceImportPersistentStatus.reviewRequired(
                reviewRequiredCount = 1,
                meetingReviewRequiredCount = 0,
                personReviewRequiredCount = 1,
            ),
            projection().observeStatus().first(),
        )
        assertEquals(0, db.personIndexDao().deleteEvidenceImportUnmatchedWithoutReviewableParticipants(USER_ID))
        assertEquals(1, db.personIndexDao().findUnmatchedInteractions(USER_ID, 20).size)
    }

    @Test
    fun `non evidence import matching backlog does not project evidence review status`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.personIndexDao().upsertUnmatchedInteractions(
            listOf(
                UnmatchedPersonInteractionEntity(
                    id = "unmatched-gmail-1",
                    userId = USER_ID,
                    sourceType = SourceType.GMAIL,
                    sourceRef = "raw:raw-gmail-1",
                    interactionKind = "email",
                    title = "메일",
                    snippet = "확인 필요",
                    suggestedLabel = "minji@example.com",
                    occurredAt = NOW,
                    createdAt = NOW,
                ),
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                SourceEventParticipantEntity(
                    id = "participant-gmail-unresolved-1",
                    userId = USER_ID,
                    sourceEventId = "raw-gmail-1",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-1",
                    personId = null,
                    role = "sender",
                    relationToUser = "counterparty",
                    identityType = "email",
                    normalizedValue = "minji@example.com",
                    displayNameRaw = "Minji",
                    emailRaw = "minji@example.com",
                    phoneRaw = null,
                    organizationRaw = null,
                    titleRaw = null,
                    evidence = "확인 필요",
                    confidence = 0.0,
                    resolutionStatus = "unresolved",
                    createdAt = NOW,
                ),
            ),
        )

        assertEquals(EvidenceImportPersistentStatus.NONE, projection().observeStatus().first())
    }

    private fun projection(): RoomEvidenceImportStatusProjectionPort =
        RoomEvidenceImportStatusProjectionPort(
            userPrefsStore = userPrefsStore,
            rawIngestionEventDao = db.rawIngestionEventDao(),
            meetingSpeakerPreviewDao = db.meetingSpeakerPreviewDao(),
            personIndexDao = db.personIndexDao(),
            ioDispatcher = Dispatchers.Unconfined,
        )

    private fun rawEvent(id: String, sourceType: String, syncStatus: String): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = id,
            userId = USER_ID,
            clientEventId = "client-$id",
            sourceType = sourceType,
            sourceRef = "file://$id",
            eventTitle = id,
            timestamp = NOW,
            syncStatus = syncStatus,
        )

    private fun meetingPreview(
        rawEventId: String,
        status: String,
        speakersJson: String = "[]",
    ): MeetingSpeakerPreviewEntity =
        MeetingSpeakerPreviewEntity(
            id = "preview-$rawEventId",
            userId = USER_ID,
            rawEventId = rawEventId,
            sourceRef = "content://meeting/$rawEventId",
            speakerPreviewId = "speaker-preview-$rawEventId",
            speakersJson = speakersJson,
            transcriptSegmentsJson = "[]",
            billableSeconds = 0,
            status = status,
            selectedSelfSpeakerId = null,
            lastError = null,
            expiresAt = null,
            createdAt = NOW,
            updatedAt = NOW,
        )

    private companion object {
        const val USER_ID = "user-evidence-import"
        const val SPEAKERS_JSON = """[{"speaker_id":"SPEAKER_01","total_seconds":12,"sample_texts":["제가 보낼게요"]}]"""
        val NOW: Instant = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
    }
}
