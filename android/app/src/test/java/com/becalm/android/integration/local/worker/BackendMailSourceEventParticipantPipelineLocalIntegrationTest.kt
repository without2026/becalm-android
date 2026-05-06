package com.becalm.android.integration.local.worker

import androidx.work.ListenableWorker
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.CommitmentScheduleStatus
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.CommitmentParticipantRepositoryImpl
import com.becalm.android.data.repository.SourceEventParticipantRepositoryImpl
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.worker.PersonInteractionIndexWorker
import com.becalm.android.worker.WorkScheduler
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BackendMailSourceEventParticipantPipelineLocalIntegrationTest {

    private lateinit var server: MockWebServer
    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val userPrefsStore = UserPrefsStoreImpl(
        LocalIntegrationSupport.prefsDataStore("backend-mail-source-event-participants"),
    )
    private val logger = RecordingLogger()
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun `backend mail source participants are pulled as references and index source commitments for same person`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.commitmentDao().insertAll(listOf(scheduleCommitment()))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "id": "11111111-1111-4111-8111-111111111111",
                          "source_event_id": "gmail-message-1",
                          "source_type": "gmail",
                          "source_ref": "gmail-message-1",
                          "person_id": "28d6aa3a-bf40-5f85-9f00-4a08263036bd",
                          "role": "sender",
                          "relation_to_user": "counterparty",
                          "identity_type": "email",
                          "normalized_value": "customer@example.com",
                          "display_name_raw": "김고객",
                          "email_raw": "customer@example.com",
                          "phone_raw": null,
                          "organization_raw": "Acme",
                          "evidence": "김고객 <customer@example.com>",
                          "confidence": 0.95,
                          "resolution_status": "resolved",
                          "created_at": "2026-04-29T00:00:05Z"
                        }
                      ],
                      "cursor": "1",
                      "has_more": false
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "id": "commitment-participant-mail-schedule-1",
                          "commitment_id": "commitment-mail-schedule-1",
                          "person_id": "28d6aa3a-bf40-5f85-9f00-4a08263036bd",
                          "role": "attendee",
                          "evidence": "2026년 5월 1일 오전 10시에 데모 미팅을 확정하겠습니다.",
                          "confidence": 0.95,
                          "created_at": "2026-04-29T00:00:10Z"
                        }
                      ],
                      "cursor": "1",
                      "has_more": false
                    }
                    """.trimIndent(),
                ),
        )

        val refresh = SourceEventParticipantRepositoryImpl(
            personIndexDao = db.personIndexDao(),
            api = LocalIntegrationSupport.railwayApi(server),
            logger = logger,
        ).refreshSince(userId = USER_ID, sourceType = SourceType.GMAIL, since = null)

        assertTrue(refresh is BecalmResult.Success)
        assertEquals("/v1/source_event_participants?limit=100&source_type=gmail", server.takeRequest().path)
        val storedParticipant = db.personIndexDao().findSourceEventParticipantsForUser(USER_ID).single()
        assertEquals(CUSTOMER_EMAIL, storedParticipant.emailRaw)
        val personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, CUSTOMER_EMAIL)).personId
        assertEquals(personId, storedParticipant.personId)
        val storedIdentity = db.personIndexDao().observeIdentitiesForPerson(USER_ID, personId).first().single()
        assertEquals("email:$CUSTOMER_EMAIL", storedIdentity.identityKey)
        assertEquals("김고객", storedIdentity.displayNameHint)

        val commitmentParticipantRefresh = CommitmentParticipantRepositoryImpl(
            personIndexDao = db.personIndexDao(),
            api = LocalIntegrationSupport.railwayApi(server),
            logger = logger,
        ).refreshSince(userId = USER_ID, since = null)

        assertTrue(commitmentParticipantRefresh is BecalmResult.Success)
        assertEquals("/v1/commitment_participants?limit=100", server.takeRequest().path)
        val storedCommitmentParticipant = db.personIndexDao().findCommitmentParticipantsForUser(USER_ID).single()
        assertEquals("commitment-mail-schedule-1", storedCommitmentParticipant.commitmentId)
        assertEquals(personId, storedCommitmentParticipant.personId)

        val indexResult = newPersonIndexWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, indexResult.javaClass)
        val aggregate = db.personIndexDao().observeAggregates(USER_ID, limit = 10).first()
            .single { it.personId == personId }
        assertEquals(1, aggregate.pendingCommitmentCount)
        assertTrue(aggregate.channelSources.orEmpty().contains(SourceType.GMAIL))

        val interactions = db.personIndexDao()
            .observeInteractionsForPerson(USER_ID, personId, limit = 10)
            .first()
        assertTrue(interactions.any { it.interactionKind == "email" && it.sourceRef == "raw:gmail-message-1" })
        assertTrue(
            interactions.any {
                it.interactionKind == "commitment" &&
                    it.role == CommitmentItemType.SCHEDULE &&
                    it.status == CommitmentScheduleStatus.CONFIRMED
            },
        )
    }

    private fun newPersonIndexWorker(): PersonInteractionIndexWorker =
        PersonInteractionIndexWorker(
            appContext = LocalIntegrationSupport.appContext(),
            workerParams = LocalIntegrationSupport.workerParams(),
            databaseProvider = Provider { db },
            rawDaoProvider = Provider { db.rawIngestionEventDao() },
            commitmentDaoProvider = Provider { db.commitmentDao() },
            personIndexDaoProvider = Provider { db.personIndexDao() },
            userPrefsStore = userPrefsStore,
            workScheduler = workScheduler,
            logger = logger,
            ioDispatcher = dispatcher,
        )

    private fun scheduleCommitment(): CommitmentEntity =
        CommitmentEntity(
            id = "commitment-mail-schedule-1",
            userId = USER_ID,
            itemType = CommitmentItemType.SCHEDULE,
            direction = null,
            scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
            decisionStatus = null,
            counterpartyRaw = null,
            counterpartyRef = null,
            title = "데모 미팅 확정",
            description = null,
            quote = "2026년 5월 1일 오전 10시에 데모 미팅을 확정하겠습니다.",
            sourceEventTitle = "데모 미팅 확정 및 견적서 요청",
            sourceEventOccurredAt = Instant.parse("2026-04-29T00:00:00Z"),
            dueAt = Instant.parse("2026-05-01T01:00:00Z"),
            dueHint = "2026년 5월 1일 오전 10시",
            dueIsApproximate = false,
            actionState = "pending",
            sourceType = SourceType.GMAIL,
            sourceRef = "gmail-message-1",
            confidence = 0.95,
            commitmentState = CommitmentLifecycleLegacy.DRAFT,
            syncStatus = "synced",
            createdAt = Instant.parse("2026-04-29T00:00:10Z"),
            updatedAt = Instant.parse("2026-04-29T00:00:10Z"),
        )

    private companion object {
        private const val USER_ID = "user-1"
        private const val CUSTOMER_EMAIL = "customer@example.com"
    }
}
