package com.becalm.android.integration.local.worker

import androidx.work.ListenableWorker
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.CommitmentParticipantEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.PersonIndexDirtySources
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.worker.PersonInteractionIndexWorker
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PersonInteractionIndexWorkerLocalIntegrationTest {

    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val userPrefsStore = UserPrefsStoreImpl(
        LocalIntegrationSupport.prefsDataStore("person-index-worker-prefs"),
    )
    private val logger = RecordingLogger()
    private val dispatcher = UnconfinedTestDispatcher()

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `relation participants are the only projection source and suppress legacy person refs`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        val personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, CUSTOMER_EMAIL)).personId
        val legacyPersonId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, "legacy@example.com")).personId
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-mail-1",
                sourceType = SourceType.GMAIL,
                counterpartyRef = "legacy@example.com",
            ),
        )
        db.commitmentDao().insertAll(
            listOf(
                commitment(
                    id = "commitment-1",
                    counterpartyRef = "legacy@example.com",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "raw:raw-mail-1",
                ),
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-1",
                    sourceEventId = "raw-mail-1",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-1",
                    personId = personId,
                    email = CUSTOMER_EMAIL,
                    role = "sender",
                    relationToUser = "counterparty",
                ),
            ),
        )
        db.personIndexDao().upsertCommitmentParticipants(
            listOf(
                commitmentParticipant(
                    id = "commitment-participant-1",
                    commitmentId = "commitment-1",
                    personId = personId,
                    role = "owner",
                ),
            ),
        )

        val result = newWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        val interactions = db.personIndexDao().observeInteractionsForPerson(USER_ID, personId, limit = 20).first()
        assertEquals(setOf("raw:raw-mail-1", "commitment:commitment-1"), interactions.map { it.sourceRef }.toSet())
        val legacyInteractions = db.personIndexDao().observeInteractionsForPerson(USER_ID, legacyPersonId, limit = 20).first()
        assertTrue(legacyInteractions.isEmpty())
    }

    @Test
    fun `raw commitments and calendar rows without relation rows do not create people index rows`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-legacy-only",
                sourceType = SourceType.GMAIL,
                counterpartyRef = CUSTOMER_EMAIL,
            ),
        )
        db.commitmentDao().insertAll(
            listOf(
                commitment(
                    id = "commitment-legacy-only",
                    counterpartyRef = CUSTOMER_EMAIL,
                    sourceType = SourceType.GMAIL,
                    sourceRef = "raw:raw-legacy-only",
                ),
            ),
        )

        val result = newWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertTrue(db.personIndexDao().observeAggregates(USER_ID, limit = 20).first().isEmpty())
        assertTrue(db.personIndexDao().findUnmatchedInteractions(USER_ID, limit = 20).isEmpty())
    }

    @Test
    fun `unresolved source participants go to review without creating people rows`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-unresolved-1",
                sourceType = SourceType.GMAIL,
                counterpartyRef = null,
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-unresolved",
                    sourceEventId = "raw-unresolved-1",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-2",
                    personId = null,
                    email = null,
                    displayName = "Steve",
                    role = "mentioned",
                    relationToUser = "referenced",
                    resolutionStatus = "unresolved",
                ),
            ),
        )

        val result = newWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertTrue(db.personIndexDao().observeAggregates(USER_ID, limit = 20).first().isEmpty())
        val unmatched = db.personIndexDao().findUnmatchedInteractions(USER_ID, limit = 20)
        assertEquals(listOf("Steve"), unmatched.map { it.suggestedLabel })
    }

    @Test
    fun `blocked relation participants are removed on next index rebuild`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        val personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, CUSTOMER_EMAIL)).personId
        db.rawIngestionEventDao().insert(rawEvent(id = "raw-blocked-1", sourceType = SourceType.GMAIL, counterpartyRef = null))
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-blocked",
                    sourceEventId = "raw-blocked-1",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-3",
                    personId = personId,
                    email = CUSTOMER_EMAIL,
                    role = "sender",
                    relationToUser = "counterparty",
                ),
            ),
        )

        newWorker().doWork()
        assertEquals(1, db.personIndexDao().observeInteractionsForPerson(USER_ID, personId, limit = 20).first().size)

        userPrefsStore.blockPersonRef(CUSTOMER_EMAIL)
        newWorker().doWork()

        assertTrue(db.personIndexDao().observeInteractionsForPerson(USER_ID, personId, limit = 20).first().isEmpty())
    }

    @Test
    fun `dirty source queue reindexes only queued source projection`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        val firstPersonId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, CUSTOMER_EMAIL)).personId
        val updatedPersonId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, UPDATED_EMAIL)).personId
        val untouchedPersonId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, UNTOUCHED_EMAIL)).personId
        db.rawIngestionEventDao().insert(rawEvent(id = "raw-dirty-1", sourceType = SourceType.GMAIL, counterpartyRef = null))
        db.rawIngestionEventDao().insert(rawEvent(id = "raw-dirty-2", sourceType = SourceType.GMAIL, counterpartyRef = null))
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-dirty-1",
                    sourceEventId = "raw-dirty-1",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-1",
                    personId = firstPersonId,
                    email = CUSTOMER_EMAIL,
                    role = "sender",
                    relationToUser = "counterparty",
                ),
                sourceParticipant(
                    id = "participant-dirty-2",
                    sourceEventId = "raw-dirty-2",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-2",
                    personId = untouchedPersonId,
                    email = UNTOUCHED_EMAIL,
                    role = "sender",
                    relationToUser = "counterparty",
                ),
            ),
        )

        newWorker().doWork()
        logger.clear()

        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-dirty-1",
                    sourceEventId = "raw-dirty-1",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-1",
                    personId = updatedPersonId,
                    email = UPDATED_EMAIL,
                    displayName = "Updated Customer",
                    role = "sender",
                    relationToUser = "counterparty",
                ),
            ),
        )
        db.personIndexDao().upsertDirtySources(
            listOf(
                PersonIndexDirtySources.rawEvent(
                    userId = USER_ID,
                    sourceType = SourceType.GMAIL,
                    sourceEventId = "raw-dirty-1",
                    reason = "test",
                    now = Instant.parse("2026-04-29T05:00:00Z"),
                ),
            ),
        )

        newWorker().doWork()

        assertTrue(db.personIndexDao().observeInteractionsForPerson(USER_ID, firstPersonId, limit = 20).first().isEmpty())
        assertEquals(
            listOf("raw:raw-dirty-1"),
            db.personIndexDao().observeInteractionsForPerson(USER_ID, updatedPersonId, limit = 20).first()
                .map { it.sourceRef },
        )
        assertEquals(
            listOf("raw:raw-dirty-2"),
            db.personIndexDao().observeInteractionsForPerson(USER_ID, untouchedPersonId, limit = 20).first()
                .map { it.sourceRef },
        )
        assertTrue(db.personIndexDao().findDirtySourcesForUser(USER_ID, limit = 10).isEmpty())
        assertTrue(logger.entries.any { "indexed mode=dirty dirtySources=1 changedSources=1" in it.message })
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
            logger = logger,
            ioDispatcher = dispatcher,
        )

    private fun rawEvent(
        id: String,
        sourceType: String,
        counterpartyRef: String?,
    ): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = id,
            userId = USER_ID,
            clientEventId = "client-$id",
            sourceType = sourceType,
            sourceRef = "$sourceType-ref-$id",
            counterpartyRef = counterpartyRef,
            eventTitle = "event-$id",
            eventSnippet = "snippet-$id",
            folder = "INBOX",
            timestamp = Instant.parse("2026-04-29T04:00:00Z"),
        )

    private fun commitment(
        id: String,
        counterpartyRef: String?,
        sourceType: String,
        sourceRef: String?,
    ): CommitmentEntity =
        CommitmentEntity(
            id = id,
            userId = USER_ID,
            itemType = CommitmentItemType.ACTION,
            direction = "give",
            scheduleStatus = null,
            decisionStatus = null,
            counterpartyRaw = counterpartyRef,
            counterpartyRef = counterpartyRef,
            title = "commitment-$id",
            description = null,
            quote = "quote-$id",
            sourceEventTitle = "event-$id",
            sourceEventOccurredAt = Instant.parse("2026-04-29T04:00:00Z"),
            dueAt = null,
            dueHint = null,
            dueIsApproximate = false,
            actionState = "pending",
            sourceType = sourceType,
            sourceRef = sourceRef,
            confidence = 0.9,
            commitmentState = CommitmentLifecycleLegacy.DRAFT,
            syncStatus = "synced",
            createdAt = Instant.parse("2026-04-29T04:00:00Z"),
            updatedAt = Instant.parse("2026-04-29T04:00:00Z"),
            lastEditedBy = null,
            lastEditedAt = null,
            quoteDisputed = false,
            quoteDisputedAt = null,
            deletedAt = null,
            supersedesCommitmentId = null,
        )

    private fun sourceParticipant(
        id: String,
        sourceEventId: String,
        sourceType: String,
        sourceRef: String,
        personId: String?,
        email: String?,
        displayName: String? = "Customer",
        role: String,
        relationToUser: String,
        resolutionStatus: String = if (personId == null) "unresolved" else "resolved",
    ): SourceEventParticipantEntity =
        SourceEventParticipantEntity(
            id = id,
            userId = USER_ID,
            sourceEventId = sourceEventId,
            sourceType = sourceType,
            sourceRef = sourceRef,
            personId = personId,
            role = role,
            relationToUser = relationToUser,
            identityType = email?.let { "email" },
            normalizedValue = email,
            displayNameRaw = displayName,
            emailRaw = email,
            phoneRaw = null,
            organizationRaw = null,
            evidence = email ?: displayName,
            confidence = 0.95,
            resolutionStatus = resolutionStatus,
            createdAt = Instant.parse("2026-04-29T04:00:00Z"),
        )

    private fun commitmentParticipant(
        id: String,
        commitmentId: String,
        personId: String,
        role: String,
    ): CommitmentParticipantEntity =
        CommitmentParticipantEntity(
            id = id,
            userId = USER_ID,
            commitmentId = commitmentId,
            personId = personId,
            role = role,
            evidence = "quote",
            confidence = 0.9,
            createdAt = Instant.parse("2026-04-29T04:00:00Z"),
        )

    private companion object {
        const val USER_ID = "user-1"
        const val CUSTOMER_EMAIL = "customer@example.com"
        const val UPDATED_EMAIL = "updated@example.com"
        const val UNTOUCHED_EMAIL = "untouched@example.com"
    }
}
