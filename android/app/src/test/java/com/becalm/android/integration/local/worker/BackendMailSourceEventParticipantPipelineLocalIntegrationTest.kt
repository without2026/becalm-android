package com.becalm.android.integration.local.worker

import androidx.work.ListenableWorker
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.CommitmentParticipantEntity
import com.becalm.android.data.local.db.entity.CommitmentScheduleStatus
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
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

    @Test
    fun `source participant refresh keeps strongest display hint for duplicate person identities`() = runTest {
        val personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, "gogo20043@hnu.kr")).personId
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "id": "22222222-2222-4222-8222-222222222222",
                          "source_event_id": "naver-message-1",
                          "source_type": "naver_imap",
                          "source_ref": "naver-message-1",
                          "person_id": "$personId",
                          "role": "sender",
                          "relation_to_user": "counterparty",
                          "identity_type": "email",
                          "normalized_value": "gogo20043@hnu.kr",
                          "display_name_raw": "고주영",
                          "email_raw": "gogo20043@hnu.kr",
                          "organization_raw": "한남대학교 창업지원단",
                          "evidence": "고주영 <gogo20043@hnu.kr>",
                          "confidence": 1.0,
                          "resolution_status": "resolved",
                          "created_at": "2026-05-08T05:18:20Z"
                        },
                        {
                          "id": "33333333-3333-4333-8333-333333333333",
                          "source_event_id": "naver-message-2",
                          "source_type": "naver_imap",
                          "source_ref": "naver-message-2",
                          "person_id": "$personId",
                          "role": "recipient",
                          "relation_to_user": "counterparty",
                          "identity_type": "email",
                          "normalized_value": "gogo20043@hnu.kr",
                          "display_name_raw": null,
                          "email_raw": "gogo20043@hnu.kr",
                          "organization_raw": null,
                          "evidence": "gogo20043@hnu.kr",
                          "confidence": 1.0,
                          "resolution_status": "resolved",
                          "created_at": "2026-05-12T01:57:54Z"
                        }
                      ],
                      "cursor": "2",
                      "has_more": false
                    }
                    """.trimIndent(),
                ),
        )

        val refresh = SourceEventParticipantRepositoryImpl(
            personIndexDao = db.personIndexDao(),
            api = LocalIntegrationSupport.railwayApi(server),
            logger = logger,
        ).refreshSince(userId = USER_ID, sourceType = SourceType.NAVER_IMAP, since = null)

        assertTrue(refresh is BecalmResult.Success)
        val storedPerson = requireNotNull(db.personIndexDao().findPersonForMemory(USER_ID, personId))
        assertEquals("고주영", storedPerson.displayName)
        val identity = db.personIndexDao().observeIdentitiesForPerson(USER_ID, personId).first().single()
        assertEquals("고주영", identity.displayNameHint)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "id": "44444444-4444-4444-8444-444444444444",
                          "source_event_id": "naver-message-3",
                          "source_type": "naver_imap",
                          "source_ref": "naver-message-3",
                          "person_id": "$personId",
                          "role": "recipient",
                          "relation_to_user": "counterparty",
                          "identity_type": "email",
                          "normalized_value": "gogo20043@hnu.kr",
                          "display_name_raw": null,
                          "email_raw": "gogo20043@hnu.kr",
                          "organization_raw": null,
                          "evidence": "gogo20043@hnu.kr",
                          "confidence": 1.0,
                          "resolution_status": "resolved",
                          "created_at": "2026-05-13T01:57:54Z"
                        }
                      ],
                      "cursor": "3",
                      "has_more": false
                    }
                    """.trimIndent(),
                ),
        )

        val weakRefresh = SourceEventParticipantRepositoryImpl(
            personIndexDao = db.personIndexDao(),
            api = LocalIntegrationSupport.railwayApi(server),
            logger = logger,
        ).refreshSince(userId = USER_ID, sourceType = SourceType.NAVER_IMAP, since = null)

        assertTrue(weakRefresh is BecalmResult.Success)
        val preservedPerson = requireNotNull(db.personIndexDao().findPersonForMemory(USER_ID, personId))
        assertEquals("고주영", preservedPerson.displayName)
        val preservedIdentity = db.personIndexDao().observeIdentitiesForPerson(USER_ID, personId).first().single()
        assertEquals("고주영", preservedIdentity.displayNameHint)
    }

    @Test
    fun `source participant refresh coalesces plus address aliases into one local person`() = runTest {
        val firstLegacyPersonId = requireNotNull(
            PersonIdentityResolver.resolve(USER_ID, "bye+j57angcdxgs0t717kta8xcgkjh85c117@town.com"),
        ).personId
        val secondLegacyPersonId = requireNotNull(
            PersonIdentityResolver.resolve(USER_ID, "bye+j570x0rmjkvrg217n3kw0dp7dx85tbh9@town.com"),
        ).personId
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "id": "55555555-5555-4555-8555-555555555555",
                          "source_event_id": "gmail-bye-1",
                          "source_type": "gmail",
                          "source_ref": "gmail-bye-1",
                          "person_id": "$firstLegacyPersonId",
                          "role": "sender",
                          "relation_to_user": "counterparty",
                          "identity_type": "email",
                          "normalized_value": "bye+j57angcdxgs0t717kta8xcgkjh85c117@town.com",
                          "display_name_raw": "Bye",
                          "email_raw": "bye+j57angcdxgs0t717kta8xcgkjh85c117@town.com",
                          "phone_raw": null,
                          "organization_raw": null,
                          "evidence": "Bye <bye+j57angcdxgs0t717kta8xcgkjh85c117@town.com>",
                          "confidence": 0.95,
                          "resolution_status": "resolved",
                          "created_at": "2026-04-29T00:00:05Z"
                        },
                        {
                          "id": "66666666-6666-4666-8666-666666666666",
                          "source_event_id": "gmail-bye-2",
                          "source_type": "gmail",
                          "source_ref": "gmail-bye-2",
                          "person_id": "$secondLegacyPersonId",
                          "role": "sender",
                          "relation_to_user": "counterparty",
                          "identity_type": "email",
                          "normalized_value": "bye+j570x0rmjkvrg217n3kw0dp7dx85tbh9@town.com",
                          "display_name_raw": "Bye",
                          "email_raw": "bye+j570x0rmjkvrg217n3kw0dp7dx85tbh9@town.com",
                          "phone_raw": null,
                          "organization_raw": null,
                          "evidence": "Bye <bye+j570x0rmjkvrg217n3kw0dp7dx85tbh9@town.com>",
                          "confidence": 0.95,
                          "resolution_status": "resolved",
                          "created_at": "2026-04-30T00:00:05Z"
                        }
                      ],
                      "cursor": "2",
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
        val storedParticipants = db.personIndexDao().findSourceEventParticipantsForUser(USER_ID)
            .filter { it.displayNameRaw == "Bye" }
        assertEquals(2, storedParticipants.size)
        val canonicalPersonId = storedParticipants.first().personId
        assertTrue(canonicalPersonId?.isNotBlank() == true)
        assertEquals(setOf(canonicalPersonId), storedParticipants.map { it.personId }.toSet())
        val identities = db.personIndexDao().findIdentitiesForUser(USER_ID)
            .filter { it.displayNameHint == "Bye" }
        assertEquals(setOf("email:bye@town.com"), identities.map { it.identityKey }.toSet())
        assertEquals(setOf(canonicalPersonId), identities.map { it.personId }.toSet())
    }

    @Test
    fun `source participant refresh keeps local self confirmation as source of truth`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.rawIngestionEventDao().insert(
            RawIngestionEventEntity(
                id = "gmail-self-1",
                userId = USER_ID,
                clientEventId = "gmail-self-1",
                sourceType = SourceType.GMAIL,
                sourceRef = "gmail-message-self-1",
                eventTitle = "본인 기록",
                eventSnippet = "내가 받은 확인 메일",
                timestamp = Instant.parse("2026-04-29T00:00:00Z"),
                syncStatus = "synced",
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "local-self-confirmed",
                    sourceEventId = "gmail-self-1",
                    displayName = "지훈님",
                    email = "me@example.com",
                    relationToUser = "self",
                    resolutionStatus = "self_resolved",
                    confidence = 0.98,
                ),
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
                          "id": "server-self-stale",
                          "source_event_id": "gmail-self-1",
                          "source_type": "gmail",
                          "source_ref": "gmail-message-self-1",
                          "person_id": null,
                          "role": "recipient",
                          "relation_to_user": "self",
                          "identity_type": "email",
                          "normalized_value": "me@example.com",
                          "display_name_raw": "지훈님",
                          "email_raw": "me@example.com",
                          "phone_raw": null,
                          "organization_raw": null,
                          "evidence": "지훈님 <me@example.com>",
                          "confidence": 0.72,
                          "resolution_status": "suggested_self",
                          "created_at": "2026-04-29T00:00:05Z"
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
        val staleServerRow = db.personIndexDao()
            .findSourceEventParticipantsForUser(USER_ID)
            .single { it.id == "server-self-stale" }
        assertEquals("self", staleServerRow.relationToUser)
        assertEquals("self_resolved", staleServerRow.resolutionStatus)
        assertEquals(0.98, staleServerRow.confidence, 0.0)

        val indexResult = newPersonIndexWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, indexResult.javaClass)
        assertEquals(0, db.personIndexDao().countUnmatchedInteractions(USER_ID))
        assertTrue(db.personIndexDao().observeAggregates(USER_ID, limit = 10).first().isEmpty())
    }

    @Test
    fun `full commitment participant refresh removes stale local rows missing on server`() = runTest {
        db.personIndexDao().upsertCommitmentParticipants(
            listOf(
                commitmentParticipant(
                    id = "cp-keep",
                    commitmentId = "commitment-keep",
                    personId = "person-keep",
                ),
                commitmentParticipant(
                    id = "cp-stale",
                    commitmentId = "commitment-stale",
                    personId = "person-stale",
                ),
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
                          "id": "cp-keep",
                          "commitment_id": "commitment-keep",
                          "person_id": "person-keep",
                          "role": "attendee",
                          "evidence": "confirmed",
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

        val refresh = CommitmentParticipantRepositoryImpl(
            personIndexDao = db.personIndexDao(),
            api = LocalIntegrationSupport.railwayApi(server),
            logger = logger,
        ).refreshSince(userId = USER_ID, since = null)

        assertTrue(refresh is BecalmResult.Success)
        assertEquals("/v1/commitment_participants?limit=100", server.takeRequest().path)
        val stored = db.personIndexDao().findCommitmentParticipantsForUser(USER_ID)
        assertEquals(listOf("cp-keep"), stored.map { it.id })
        val dirtySources = db.personIndexDao().findDirtySourcesForUser(USER_ID, limit = 10)
        assertTrue(dirtySources.any { it.sourceRef == "commitment:commitment-stale" })
    }

    private fun newPersonIndexWorker(): PersonInteractionIndexWorker =
        PersonInteractionIndexWorker(
            appContext = LocalIntegrationSupport.appContext(),
            workerParams = LocalIntegrationSupport.workerParams(),
            databaseProvider = Provider { db },
            rawDaoProvider = Provider { db.rawIngestionEventDao() },
            commitmentDaoProvider = Provider { db.commitmentDao() },
            personIndexDaoProvider = Provider { db.personIndexDao() },
            selfIdentityAnchorDaoProvider = Provider { db.selfIdentityAnchorDao() },
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

    private fun commitmentParticipant(
        id: String,
        commitmentId: String,
        personId: String,
    ): CommitmentParticipantEntity =
        CommitmentParticipantEntity(
            id = id,
            userId = USER_ID,
            commitmentId = commitmentId,
            personId = personId,
            role = "attendee",
            evidence = null,
            confidence = 0.9,
            createdAt = Instant.parse("2026-04-29T00:00:10Z"),
        )

    private fun sourceParticipant(
        id: String,
        sourceEventId: String,
        displayName: String?,
        email: String?,
        relationToUser: String,
        resolutionStatus: String,
        confidence: Double,
    ): SourceEventParticipantEntity =
        SourceEventParticipantEntity(
            id = id,
            userId = USER_ID,
            sourceEventId = sourceEventId,
            sourceType = SourceType.GMAIL,
            sourceRef = "gmail-message-self-1",
            personId = null,
            role = "recipient",
            relationToUser = relationToUser,
            identityType = "email",
            normalizedValue = email,
            displayNameRaw = displayName,
            emailRaw = email,
            phoneRaw = null,
            organizationRaw = null,
            titleRaw = null,
            evidence = displayName,
            confidence = confidence,
            resolutionStatus = resolutionStatus,
            createdAt = Instant.parse("2026-04-29T00:00:05Z"),
        )

    private companion object {
        private const val USER_ID = "user-1"
        private const val CUSTOMER_EMAIL = "customer@example.com"
    }
}
