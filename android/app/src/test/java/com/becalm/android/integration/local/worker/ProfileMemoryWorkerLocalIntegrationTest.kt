package com.becalm.android.integration.local.worker

import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.CommitmentParticipantEntity
import com.becalm.android.data.local.db.entity.PersonEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.repository.PersonMemoryInputCollector
import com.becalm.android.data.repository.PersonMemoryRemoteMirror
import com.becalm.android.data.repository.PersonMemoryRemoteRepository
import com.becalm.android.data.repository.PersonMemorySemanticIndexStore
import com.becalm.android.data.repository.PersonMemoryStore
import com.becalm.android.domain.person.PersonMemoryHash
import com.becalm.android.domain.person.PersonMemoryMarkdownValidator
import com.becalm.android.domain.person.PersonMemoryPathResolver
import com.becalm.android.domain.person.PersonMemoryValidationError
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.worker.ProfileMemoryWorker
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProfileMemoryWorkerLocalIntegrationTest {

    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val userPrefsStore = UserPrefsStoreImpl(
        LocalIntegrationSupport.prefsDataStore("profile-memory-worker-prefs"),
    )
    private val logger = RecordingLogger()
    private val dispatcher = UnconfinedTestDispatcher()

    @After
    fun tearDown() {
        db.close()
        memoryFile(USER_ID, PERSON_ID).delete()
    }

    @Test
    fun `writes validated person memory markdown and uploads mirror from local graph`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        seedPersonGraph()

        val result = newWorker(PERSON_ID).doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertEquals(ProfileMemoryWorker.STATUS_WRITTEN_UPLOADED, result.outputData.getString(ProfileMemoryWorker.KEY_STATUS))
        assertEquals(PERSON_ID, result.outputData.getString(ProfileMemoryWorker.KEY_PERSON_ID))
        assertEquals("$USER_ID/$PERSON_ID/memory.md", result.outputData.getString(ProfileMemoryWorker.KEY_REMOTE_OBJECT_PATH))

        val relativePath = requireNotNull(result.outputData.getString(ProfileMemoryWorker.KEY_RELATIVE_PATH))
        assertEquals(PersonMemoryPathResolver.localRelativePath(USER_ID, PERSON_ID), relativePath)
        val markdown = memoryFile(USER_ID, PERSON_ID).readText(Charsets.UTF_8)
        assertTrue(markdown.contains("Works with Acme as CEO."))
        assertTrue(markdown.contains("Send revised terms"))
        val semanticIndex = db.personIndexDao().findSemanticIndexForPerson(USER_ID, PERSON_ID)
        assertEquals(PERSON_ID, semanticIndex?.personId)
        assertTrue(requireNotNull(semanticIndex).organizationsJson.contains("acme"))
        assertTrue(semanticIndex.openCommitmentTermsJson.contains("revised"))
        assertEquals(PersonMemoryHash.sha256(markdown), result.outputData.getString(ProfileMemoryWorker.KEY_CONTENT_HASH))
        assertEquals(
            emptyList<PersonMemoryValidationError>(),
            PersonMemoryMarkdownValidator.validate(markdown, USER_ID, PERSON_ID).errors,
        )
    }

    @Test
    fun `keeps local memory and reports pending when mirror upload fails`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        seedPersonGraph()

        val result = newWorker(
            personId = PERSON_ID,
            remoteResult = BecalmResult.Failure(BecalmError.Network(503, "offline")),
        ).doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertEquals(ProfileMemoryWorker.STATUS_WRITTEN_UPLOAD_PENDING, result.outputData.getString(ProfileMemoryWorker.KEY_STATUS))
        assertEquals("Network", result.outputData.getString(ProfileMemoryWorker.KEY_UPLOAD_ERROR))
        assertTrue(memoryFile(USER_ID, PERSON_ID).exists())
        val markdown = memoryFile(USER_ID, PERSON_ID).readText(Charsets.UTF_8)
        assertEquals(
            emptyList<PersonMemoryValidationError>(),
            PersonMemoryMarkdownValidator.validate(markdown, USER_ID, PERSON_ID).errors,
        )
    }

    @Test
    fun `e2e 064 offline mirror failure leaves local memory retryable`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        seedPersonGraph()

        val result = newWorker(
            personId = PERSON_ID,
            remoteResult = BecalmResult.Failure(BecalmError.Network(0, "offline")),
        ).doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertEquals(ProfileMemoryWorker.STATUS_WRITTEN_UPLOAD_PENDING, result.outputData.getString(ProfileMemoryWorker.KEY_STATUS))
        assertTrue(memoryFile(USER_ID, PERSON_ID).exists())
        assertEquals(PERSON_ID, result.outputData.getString(ProfileMemoryWorker.KEY_PERSON_ID))
    }

    @Test
    fun `skips without writing when user is absent`() = runTest {
        seedPersonGraph()

        val result = newWorker(PERSON_ID).doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertEquals(ProfileMemoryWorker.STATUS_SKIPPED_NO_USER, result.outputData.getString(ProfileMemoryWorker.KEY_STATUS))
        assertFalse(memoryFile(USER_ID, PERSON_ID).exists())
    }

    @Test
    fun `fails when person id input is missing`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)

        val result = newWorker(personId = null).doWork()

        assertEquals(ListenableWorker.Result.failure().javaClass, result.javaClass)
        assertEquals(ProfileMemoryWorker.STATUS_MISSING_PERSON_ID, result.outputData.getString(ProfileMemoryWorker.KEY_STATUS))
    }

    private fun newWorker(
        personId: String?,
        remoteResult: BecalmResult<PersonMemoryRemoteMirror> = BecalmResult.Success(
            PersonMemoryRemoteMirror(
                bucket = "person-memory",
                objectPath = "$USER_ID/$PERSON_ID/memory.md",
                personId = PERSON_ID,
                contentHash = "hash",
                generatedAt = NOW,
            ),
        ),
    ): ProfileMemoryWorker =
        ProfileMemoryWorker(
            appContext = LocalIntegrationSupport.appContext(),
            workerParams = LocalIntegrationSupport.workerParams(
                inputData = personId?.let { workDataOf(ProfileMemoryWorker.KEY_PERSON_ID to it) } ?: workDataOf(),
            ),
            userPrefsStore = userPrefsStore,
            inputCollector = PersonMemoryInputCollector(
                personIndexDao = db.personIndexDao(),
                commitmentDao = db.commitmentDao(),
                ioDispatcher = dispatcher,
            ),
            memoryStore = PersonMemoryStore(LocalIntegrationSupport.appContext()),
            semanticIndexStore = PersonMemorySemanticIndexStore(
                personIndexDao = db.personIndexDao(),
                ioDispatcher = dispatcher,
            ),
            remoteRepository = FakePersonMemoryRemoteRepository(remoteResult),
            logger = logger,
            ioDispatcher = dispatcher,
        )

    private suspend fun seedPersonGraph() {
        db.personIndexDao().upsertPersons(
            listOf(
                PersonEntity(
                    id = PERSON_ID,
                    userId = USER_ID,
                    displayName = "Jane Kim",
                    kind = "person",
                    primaryEmail = "jane@acme.com",
                    primaryPhone = null,
                    confidence = 0.95,
                    createdAt = NOW,
                    updatedAt = NOW,
                    archivedAt = null,
                ),
            ),
        )
        db.personIndexDao().upsertIdentities(
            listOf(
                PersonIdentityEntity(
                    id = "identity-email",
                    userId = USER_ID,
                    personId = PERSON_ID,
                    identityKey = "email:jane@acme.com",
                    identityType = "email",
                    rawValue = "Jane@Acme.com",
                    displayNameHint = "Jane Kim",
                    identityValue = "Jane@Acme.com",
                    normalizedValue = "jane@acme.com",
                    displayName = "Jane Kim",
                    sourceType = "gmail",
                    sourceRef = "raw:raw-1",
                    confidence = 0.95,
                    isPrimary = true,
                    verified = true,
                    lastSeenAt = NOW,
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                SourceEventParticipantEntity(
                    id = "participant-1",
                    userId = USER_ID,
                    sourceEventId = "raw-1",
                    sourceType = "gmail",
                    sourceRef = "gmail-message-1",
                    personId = PERSON_ID,
                    role = "sender",
                    relationToUser = "counterparty",
                    identityType = "email",
                    normalizedValue = "jane@acme.com",
                    displayNameRaw = "Jane Kim",
                    emailRaw = "jane@acme.com",
                    phoneRaw = null,
                    organizationRaw = "Acme",
                    titleRaw = "CEO",
                    evidence = "Jane Kim, Acme CEO",
                    confidence = 0.9,
                    resolutionStatus = "resolved",
                    createdAt = NOW,
                ),
            ),
        )
        db.personIndexDao().upsertInteractions(
            listOf(
                PersonInteractionEntity(
                    id = "interaction-1",
                    userId = USER_ID,
                    personId = PERSON_ID,
                    sourceType = "gmail",
                    sourceRef = "raw:raw-1",
                    interactionKind = "email",
                    role = "sender",
                    direction = "inbound",
                    status = "active",
                    occurredAt = NOW,
                    title = "Pricing renewal",
                    snippet = "Jane asked for revised terms",
                    confidence = 0.9,
                    createdAt = NOW,
                ),
                PersonInteractionEntity(
                    id = "interaction-commitment",
                    userId = USER_ID,
                    personId = PERSON_ID,
                    sourceType = "gmail",
                    sourceRef = "commitment:commitment-1",
                    interactionKind = "commitment",
                    role = "counterparty",
                    direction = "give",
                    status = "pending",
                    occurredAt = NOW,
                    title = "Send revised terms",
                    snippet = "Please send revised terms",
                    confidence = 0.9,
                    createdAt = NOW,
                ),
            ),
        )
        db.personIndexDao().upsertCommitmentParticipants(
            listOf(
                CommitmentParticipantEntity(
                    id = "commitment-participant-1",
                    userId = USER_ID,
                    commitmentId = "commitment-1",
                    personId = PERSON_ID,
                    role = "counterparty",
                    evidence = "Please send revised terms",
                    confidence = 0.9,
                    createdAt = NOW,
                ),
            ),
        )
        db.commitmentDao().insert(
            CommitmentEntity(
                id = "commitment-1",
                userId = USER_ID,
                itemType = CommitmentItemType.ACTION,
                direction = "give",
                counterpartyRaw = "Jane Kim",
                counterpartyRef = "jane@acme.com",
                title = "Send revised terms",
                description = null,
                quote = "Please send revised terms by Friday",
                sourceEventTitle = "Pricing renewal",
                sourceEventOccurredAt = NOW,
                dueAt = null,
                dueHint = null,
                actionState = "pending",
                sourceType = "gmail",
                sourceRef = "raw:raw-1",
                confidence = 0.9,
                commitmentState = CommitmentLifecycleLegacy.DRAFT,
                syncStatus = "synced",
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
    }

    private fun memoryFile(userId: String, personId: String): File {
        val relativePath = PersonMemoryPathResolver.localRelativePath(userId, personId)
        return relativePath.split('/')
            .filter { it.isNotBlank() && it != ".." }
            .fold(LocalIntegrationSupport.appContext().filesDir) { parent, child -> File(parent, child) }
    }

    private class FakePersonMemoryRemoteRepository(
        private val result: BecalmResult<PersonMemoryRemoteMirror>,
    ) : PersonMemoryRemoteRepository {
        override suspend fun uploadLocalMemory(userId: String, personId: String): BecalmResult<PersonMemoryRemoteMirror> = result
    }

    private companion object {
        const val USER_ID = "user-1"
        const val PERSON_ID = "person-1"
        val NOW: Instant = Instant.parse("2026-05-06T00:00:00Z")
    }
}
