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
import com.becalm.android.data.repository.SourcePersonCandidateRepositoryImpl
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.worker.PersonInteractionIndexWorker
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
class BackendMailPersonCandidatePipelineLocalIntegrationTest {

    private lateinit var server: MockWebServer
    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val userPrefsStore = UserPrefsStoreImpl(
        LocalIntegrationSupport.prefsDataStore("backend-mail-person-candidates"),
    )
    private val logger = RecordingLogger()
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
    fun `backend mail AI candidates are pulled as references and index source commitments for same person`() = runTest {
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
                          "id": "candidate-mail-1",
                          "source_type": "gmail",
                          "source_ref": "raw:gmail-message-1",
                          "candidate_ref": "sender:customer@example.com",
                          "role": "sender",
                          "name": "김고객",
                          "email": "customer@example.com",
                          "phone": null,
                          "organization": "Acme",
                          "evidence": "김고객 <customer@example.com>",
                          "confidence": 0.95,
                          "created_at": "2026-04-29T00:00:05Z"
                        }
                      ],
                      "cursor": "1",
                      "has_more": false
                    }
                    """.trimIndent(),
                ),
        )

        val refresh = SourcePersonCandidateRepositoryImpl(
            personIndexDao = db.personIndexDao(),
            api = LocalIntegrationSupport.railwayApi(server),
            logger = logger,
        ).refreshSince(userId = USER_ID, sourceType = SourceType.GMAIL, since = null)

        assertTrue(refresh is BecalmResult.Success)
        assertEquals("/v1/source_person_candidates?limit=100&source_type=gmail", server.takeRequest().path)
        val storedCandidate = db.personIndexDao().findCandidatesForUser(USER_ID).single()
        assertEquals(CUSTOMER_EMAIL, storedCandidate.email)

        val indexResult = newPersonIndexWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, indexResult.javaClass)
        val personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, CUSTOMER_EMAIL)).personId
        val aggregate = db.personIndexDao().observeAggregates(USER_ID, limit = 10).first()
            .single { it.personId == personId }
        assertEquals(1, aggregate.pendingCommitmentCount)
        assertTrue(aggregate.channelSources.orEmpty().contains(SourceType.GMAIL))

        val interactions = db.personIndexDao()
            .observeInteractionsForPerson(USER_ID, personId, limit = 10)
            .first()
        assertTrue(interactions.none { it.interactionKind == "email" && it.sourceRef == "raw:gmail-message-1" })
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
            calendarDaoProvider = Provider { db.calendarEventDao() },
            personIndexDaoProvider = Provider { db.personIndexDao() },
            userPrefsStore = userPrefsStore,
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
            personRef = null,
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
