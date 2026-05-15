package com.becalm.android.integration.local.worker

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.PersonEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceEventParticipantDto
import com.becalm.android.data.remote.dto.SourceEventParticipantPatchRequestDto
import com.becalm.android.data.remote.dto.SourceEventParticipantResponse
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.PersonManualMatchRepositoryImpl
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.worker.PersonInteractionIndexWorker
import com.becalm.android.worker.SourceParticipantMirrorWorker
import com.becalm.android.worker.WorkScheduler
import java.io.IOException
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PersonManualMatchPipelineLocalIntegrationTest {

    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val userPrefsStore = UserPrefsStoreImpl(
        LocalIntegrationSupport.prefsDataStore("person-manual-match-prefs"),
    )
    private val logger = RecordingLogger()
    private val scheduler = RecordingWorkScheduler()
    private val dispatcher = UnconfinedTestDispatcher()

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `manual match no longer promotes legacy raw rows without relation participants`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.rawIngestionEventDao().insert(rawEvent(id = "raw-unmatched-1", snippet = "예약 시간이 확정되었습니다."))

        newWorker().doWork()
        assertTrue(db.personIndexDao().findUnmatchedInteractions(USER_ID, limit = 10).isEmpty())
        logger.clear()
        newWorker().doWork()
        assertTrue(logger.entries.any { "indexed mode=full dirtySources=0 changedSources=0" in it.message })

        val repository = PersonManualMatchRepositoryImpl(
            personIndexDao = db.personIndexDao(),
            workScheduler = scheduler,
            logger = logger,
            ioDispatcher = dispatcher,
        )
        val result = repository.matchInteraction(
            userId = USER_ID,
            sourceType = SourceType.GMAIL,
            sourceRef = "raw:raw-unmatched-1",
            interactionKind = "email",
            personAnchor = NAVER_EMAIL,
            nickname = NAVER_NICKNAME,
        )
        assertTrue(result is BecalmResult.Success)
        assertEquals(1, scheduler.personIndexEnqueueCount)

        newWorker().doWork()
        assertTrue(db.personIndexDao().findUnmatchedInteractions(USER_ID, limit = 10).isEmpty())
        assertTrue(db.personIndexDao().observeAggregates(USER_ID, limit = 10).first().isEmpty())

        db.rawIngestionEventDao().insert(rawEvent(id = "raw-future-1", snippet = "$NAVER_NICKNAME 확인 메일입니다."))
        newWorker().doWork()
        assertTrue(db.personIndexDao().observeAggregates(USER_ID, limit = 10).first().isEmpty())
    }

    @Test
    fun `manual match resolves unmatched source participant into person projection`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        val personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, CUSTOMER_EMAIL)).personId
        db.rawIngestionEventDao().insert(rawEvent(id = "raw-relation-1", snippet = "Steve 검토 필요"))
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-unresolved-1",
                    sourceEventId = "raw-relation-1",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-1",
                    displayName = "Steve",
                ),
            ),
        )

        newWorker().doWork()
        assertEquals(1, db.personIndexDao().findUnmatchedInteractions(USER_ID, limit = 10).size)

        val api = mockk<RailwayApi>()
        val patchSlot = slot<SourceEventParticipantPatchRequestDto>()
        coEvery {
            api.patchSourceEventParticipant(
                participantId = "participant-unresolved-1",
                request = capture(patchSlot),
            )
        } returns Response.success(sourceParticipantResponse("participant-unresolved-1"))
        val repository = PersonManualMatchRepositoryImpl(
            personIndexDao = db.personIndexDao(),
            workScheduler = scheduler,
            apiProvider = Provider { api },
            logger = logger,
            ioDispatcher = dispatcher,
        )
        val result = repository.matchInteraction(
            userId = USER_ID,
            sourceType = SourceType.GMAIL,
            sourceRef = "raw:raw-relation-1",
            interactionKind = "email",
            personAnchor = CUSTOMER_EMAIL,
            nickname = "Customer",
        )
        assertTrue(result is BecalmResult.Success)

        newWorker().doWork()

        assertTrue(db.personIndexDao().findUnmatchedInteractions(USER_ID, limit = 10).isEmpty())
        val interactions = db.personIndexDao().observeInteractionsForPerson(USER_ID, personId, limit = 10).first()
        assertEquals(listOf("raw:raw-relation-1"), interactions.map { it.sourceRef })
        assertEquals(1, scheduler.personIndexEnqueueCount)
        coVerify(exactly = 1) {
            api.patchSourceEventParticipant(
                participantId = "participant-unresolved-1",
                request = patchSlot.captured,
            )
        }
        assertEquals(personId, patchSlot.captured.personId)
        assertEquals("email", patchSlot.captured.identityType)
        assertEquals(CUSTOMER_EMAIL, patchSlot.captured.normalizedValue)
        assertEquals("resolved", patchSlot.captured.resolutionStatus)
    }

    @Test
    // spec: RUX-007
    fun `manual match can resolve directly to existing person id choice`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        val personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, CUSTOMER_EMAIL)).personId
        db.personIndexDao().upsertPersons(
            listOf(
                person(
                    id = personId,
                    displayName = "Customer",
                    primaryEmail = CUSTOMER_EMAIL,
                ),
            ),
        )
        db.personIndexDao().upsertIdentities(
            listOf(
                identity(
                    id = "identity-customer-email",
                    personId = personId,
                    rawValue = CUSTOMER_EMAIL,
                ),
            ),
        )
        db.rawIngestionEventDao().insert(rawEvent(id = "raw-speaker-1", snippet = "금요일까지 자료 공유"))
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-speaker-1",
                    sourceEventId = "raw-speaker-1",
                    sourceType = SourceType.MEETING,
                    sourceRef = "meeting-file-1",
                    displayName = "SPEAKER_02",
                ).copy(
                    role = "speaker",
                    relationToUser = "participant",
                    identityType = "speaker_label",
                    normalizedValue = "SPEAKER_02",
                    confidence = 0.0,
                ),
            ),
        )

        newWorker().doWork()
        assertEquals(1, db.personIndexDao().findUnmatchedInteractions(USER_ID, limit = 10).size)

        val repository = PersonManualMatchRepositoryImpl(
            personIndexDao = db.personIndexDao(),
            workScheduler = scheduler,
            logger = logger,
            ioDispatcher = dispatcher,
        )
        val result = repository.matchInteraction(
            userId = USER_ID,
            sourceType = SourceType.MEETING,
            sourceRef = "raw:raw-speaker-1",
            interactionKind = "meeting",
            personAnchor = personId,
            nickname = "Customer",
        )
        assertTrue(result is BecalmResult.Success)

        newWorker().doWork()

        assertTrue(db.personIndexDao().findUnmatchedInteractions(USER_ID, limit = 10).isEmpty())
        val interactions = db.personIndexDao().observeInteractionsForPerson(USER_ID, personId, limit = 10).first()
        assertEquals(listOf("raw:raw-speaker-1"), interactions.map { it.sourceRef })
        assertEquals("Customer", db.personIndexDao().findPersonForMemory(USER_ID, personId)?.displayName)
        val participants = db.personIndexDao().findSourceEventParticipantsForUserAndEventIds(
            userId = USER_ID,
            sourceEventIds = listOf("raw-speaker-1"),
        )
        assertEquals(personId, participants.single().personId)
        val identities = db.personIndexDao().findIdentitiesForMemory(USER_ID, personId)
        assertTrue(identities.none { it.identityType == "speaker_label" })
        assertEquals(1, scheduler.personIndexEnqueueCount)
    }

    @Test
    fun `self match resolves participant without creating a person projection`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.rawIngestionEventDao().insert(rawEvent(id = "raw-self-1", snippet = "제가 금요일까지 보내겠습니다."))
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-self-1",
                    sourceEventId = "raw-self-1",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-self",
                    displayName = "me@example.com",
                ).copy(
                    relationToUser = "participant",
                    identityType = "email",
                    normalizedValue = "me@example.com",
                    emailRaw = "me@example.com",
                ),
            ),
        )

        newWorker().doWork()
        assertEquals(1, db.personIndexDao().findUnmatchedInteractions(USER_ID, limit = 10).size)

        val api = mockk<RailwayApi>()
        val selfPatchSlot = slot<SourceEventParticipantPatchRequestDto>()
        coEvery {
            api.patchSourceEventParticipant(
                participantId = "participant-self-1",
                request = capture(selfPatchSlot),
            )
        } returns Response.success(sourceParticipantResponse("participant-self-1"))
        val repository = PersonManualMatchRepositoryImpl(
            personIndexDao = db.personIndexDao(),
            workScheduler = scheduler,
            apiProvider = Provider { api },
            logger = logger,
            ioDispatcher = dispatcher,
        )
        val result = repository.matchInteractionAsSelf(
            userId = USER_ID,
            sourceType = SourceType.GMAIL,
            sourceRef = "raw:raw-self-1",
            interactionKind = "email",
        )
        assertTrue(result is BecalmResult.Success)

        val participant = db.personIndexDao().findSourceEventParticipantsForUserAndEventIds(
            userId = USER_ID,
            sourceEventIds = listOf("raw-self-1"),
        ).single()
        assertEquals("self", participant.relationToUser)
        assertEquals("self_resolved", participant.resolutionStatus)
        assertEquals(null, participant.personId)
        assertTrue(db.personIndexDao().findUnmatchedInteractions(USER_ID, limit = 10).isEmpty())
        assertEquals(1, scheduler.personIndexEnqueueCount)

        newWorker().doWork()
        assertTrue(db.personIndexDao().observeAggregates(USER_ID, limit = 10).first().isEmpty())
        coVerify(exactly = 1) {
            api.patchSourceEventParticipant(
                participantId = "participant-self-1",
                request = selfPatchSlot.captured,
            )
        }
        assertEquals("email", selfPatchSlot.captured.identityType)
        assertEquals("me@example.com", selfPatchSlot.captured.normalizedValue)
        assertEquals("me@example.com", selfPatchSlot.captured.emailRaw)
        assertEquals("self", selfPatchSlot.captured.relationToUser)
        assertEquals("self_resolved", selfPatchSlot.captured.resolutionStatus)
    }

    @Test
    fun `manual match queues remote mirror after transient failure and worker retries`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.rawIngestionEventDao().insert(rawEvent(id = "raw-retry-1", snippet = "Steve 검토 필요"))
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-retry-1",
                    sourceEventId = "raw-retry-1",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-retry",
                    displayName = "Steve",
                ),
            ),
        )
        newWorker().doWork()

        val api = mockk<RailwayApi>()
        val retryPatchSlot = slot<SourceEventParticipantPatchRequestDto>()
        coEvery {
            api.patchSourceEventParticipant(
                participantId = "participant-retry-1",
                request = capture(retryPatchSlot),
            )
        } throws IOException("offline")
        val repository = PersonManualMatchRepositoryImpl(
            personIndexDao = db.personIndexDao(),
            workScheduler = scheduler,
            apiProvider = Provider { api },
            logger = logger,
            ioDispatcher = dispatcher,
        )

        val result = repository.matchInteraction(
            userId = USER_ID,
            sourceType = SourceType.GMAIL,
            sourceRef = "raw:raw-retry-1",
            interactionKind = "email",
            personAnchor = CUSTOMER_EMAIL,
            nickname = "Customer",
        )

        assertTrue(result is BecalmResult.Success)
        assertEquals(1, scheduler.sourceParticipantMirrorEnqueueCount)
        val pending = db.personIndexDao().findPendingSourceParticipantMirrors(USER_ID, limit = 10).single()
        assertEquals("participant-retry-1", pending.participantId)
        assertEquals("resolved", pending.resolutionStatus)
        assertEquals(CUSTOMER_EMAIL, pending.normalizedValue)

        coEvery {
            api.patchSourceEventParticipant(
                participantId = "participant-retry-1",
                request = retryPatchSlot.captured,
            )
        } returns Response.success(sourceParticipantResponse("participant-retry-1"))

        newSourceParticipantMirrorWorker(api).doWork()

        assertTrue(db.personIndexDao().findPendingSourceParticipantMirrors(USER_ID, limit = 10).isEmpty())
    }

    private fun newWorker(): PersonInteractionIndexWorker =
        PersonInteractionIndexWorker(
            appContext = LocalIntegrationSupport.appContext(),
            workerParams = LocalIntegrationSupport.workerParams(),
            databaseProvider = Provider { db },
            rawDaoProvider = Provider { db.rawIngestionEventDao() },
            commitmentDaoProvider = Provider { db.commitmentDao() },
            personIndexDaoProvider = Provider { db.personIndexDao() },
            userPrefsStore = userPrefsStore,
            workScheduler = scheduler,
            logger = logger,
            ioDispatcher = dispatcher,
        )

    private fun newSourceParticipantMirrorWorker(api: RailwayApi): SourceParticipantMirrorWorker =
        SourceParticipantMirrorWorker(
            appContext = LocalIntegrationSupport.appContext(),
            workerParams = LocalIntegrationSupport.workerParams(),
            userPrefsStore = userPrefsStore,
            personIndexDao = db.personIndexDao(),
            api = api,
            logger = logger,
            ioDispatcher = dispatcher,
        )

    private fun rawEvent(id: String, snippet: String): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = id,
            userId = USER_ID,
            clientEventId = "client-$id",
            sourceType = SourceType.GMAIL,
            sourceRef = "gmail-$id",
            counterpartyRef = null,
            eventTitle = "예약 알림",
            eventSnippet = snippet,
            folder = "INBOX",
            timestamp = Instant.parse("2026-04-29T00:00:00Z"),
            syncStatus = "synced",
        )

    private fun person(id: String, displayName: String, primaryEmail: String?): PersonEntity =
        PersonEntity(
            id = id,
            userId = USER_ID,
            displayName = displayName,
            kind = "person",
            primaryEmail = primaryEmail,
            primaryPhone = null,
            confidence = 0.95,
            createdAt = Instant.parse("2026-04-29T00:00:00Z"),
            updatedAt = Instant.parse("2026-04-29T00:00:00Z"),
            archivedAt = null,
        )

    private fun identity(id: String, personId: String, rawValue: String): PersonIdentityEntity =
        PersonIdentityEntity(
            id = id,
            userId = USER_ID,
            personId = personId,
            identityKey = "email:${rawValue.lowercase()}",
            identityType = "email",
            rawValue = rawValue,
            displayNameHint = "Customer",
            identityValue = rawValue,
            normalizedValue = rawValue.lowercase(),
            displayName = "Customer",
            sourceType = SourceType.GMAIL,
            sourceRef = "raw:raw-relation-1",
            confidence = 0.95,
            isPrimary = true,
            verified = true,
            lastSeenAt = Instant.parse("2026-04-29T00:00:00Z"),
            createdAt = Instant.parse("2026-04-29T00:00:00Z"),
            updatedAt = Instant.parse("2026-04-29T00:00:00Z"),
        )

    private fun sourceParticipant(
        id: String,
        sourceEventId: String,
        sourceType: String,
        sourceRef: String,
        displayName: String,
    ): SourceEventParticipantEntity =
        SourceEventParticipantEntity(
            id = id,
            userId = USER_ID,
            sourceEventId = sourceEventId,
            sourceType = sourceType,
            sourceRef = sourceRef,
            personId = null,
            role = "mentioned",
            relationToUser = "referenced",
            identityType = null,
            normalizedValue = null,
            displayNameRaw = displayName,
            emailRaw = null,
            phoneRaw = null,
            organizationRaw = null,
            titleRaw = null,
            evidence = displayName,
            confidence = 0.45,
            resolutionStatus = "unresolved",
            createdAt = Instant.parse("2026-04-29T00:00:00Z"),
        )

    private fun sourceParticipantResponse(participantId: String): SourceEventParticipantResponse =
        SourceEventParticipantResponse(
            data = SourceEventParticipantDto(
                id = participantId,
                sourceEventId = "raw-relation-1",
                sourceType = SourceType.GMAIL,
                sourceRef = "gmail-message-1",
                personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, CUSTOMER_EMAIL)).personId,
                role = "mentioned",
                relationToUser = "referenced",
                identityType = "email",
                normalizedValue = CUSTOMER_EMAIL,
                displayNameRaw = "Customer",
                emailRaw = CUSTOMER_EMAIL,
                confidence = 0.95,
                resolutionStatus = "resolved",
                createdAt = Instant.parse("2026-04-29T00:00:00Z"),
            ),
        )

    private class RecordingWorkScheduler : WorkScheduler {
        var personIndexEnqueueCount: Int = 0
            private set
        var sourceParticipantMirrorEnqueueCount: Int = 0
            private set

        override fun enqueuePersonInteractionIndex(initialDelaySeconds: Long) {
            personIndexEnqueueCount += 1
        }

        override fun enqueueSourceParticipantMirrorRetry(initialDelaySeconds: Long) {
            sourceParticipantMirrorEnqueueCount += 1
        }

        override fun enqueueProfileMemory(personId: String, initialDelaySeconds: Long) = Unit
        override fun enqueueExpedited(sourceKey: String) = Unit
        override fun enqueuePeriodic(sourceKey: String) = Unit
        override fun enqueueUpload(attempt: Int) = Unit
        override fun scheduleUploadRedundancy() = Unit
        override fun scheduleBackendMailSync() = Unit
        override fun enqueueEnrichment() = Unit
        override fun scheduleEnrichmentSweep() = Unit
        override fun cancelEnrichmentSweep() = Unit
        override fun enqueueVoiceUpload(rawEventId: String, audioUri: String, selfSpeakerId: String?, speakerMappingsJson: String?, speakerPreviewId: String?) = Unit
        override fun enqueueMessageScreenshotUpload(rawEventId: String) = Unit
        override fun enqueueVoiceUploadWithDelay(
            rawEventId: String,
            audioUri: String,
            initialDelaySec: Long,
            rateLimitedAttempt: Int,
            selfSpeakerId: String?,
            speakerMappingsJson: String?,
            speakerPreviewId: String?,
        ) = Unit
        override fun scheduleRetentionSweep() = Unit
        override fun scheduleOverdueSweep() = Unit
        override fun enqueueDeferredColdSyncStage1() = Unit
        override fun enqueueColdSyncStage2() = Unit
        override fun cancelColdSyncStage2() = Unit
        override fun cancelVoiceUpload(rawEventId: String) = Unit
        override fun cancelMessageScreenshotUpload(rawEventId: String) = Unit
        override fun cancelAll() = Unit
        override fun cleanupLegacyWorkNames() = Unit
    }

    private companion object {
        private const val USER_ID = "user-1"
        private const val NAVER_EMAIL = "noreply@navercorp.com"
        private const val NAVER_NICKNAME = "네이버 예약팀"
        private const val CUSTOMER_EMAIL = "customer@example.com"
    }
}
