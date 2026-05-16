package com.becalm.android.integration.local.ui.evidence

import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.BeCalmDatabase
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

        assertEquals(EvidenceImportPersistentStatus.PROCESSING, projection().observeStatus().first())
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
                    snippet = "SPEAKER_02 확인 필요",
                    suggestedLabel = "SPEAKER_02",
                    occurredAt = NOW,
                    createdAt = NOW,
                ),
            ),
        )

        assertEquals(EvidenceImportPersistentStatus.REVIEW_REQUIRED, projection().observeStatus().first())
    }

    @Test
    fun `unresolved source event participant projects review required status`() = runTest {
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

        assertEquals(EvidenceImportPersistentStatus.REVIEW_REQUIRED, projection().observeStatus().first())
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

    private companion object {
        const val USER_ID = "user-evidence-import"
        val NOW: Instant = Instant.parse("2026-05-13T00:00:00Z")
    }
}
