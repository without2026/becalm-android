package com.becalm.android.integration.local.data.repository

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.repository.PersonMemoryRemoteMirror
import com.becalm.android.data.repository.PersonMemoryRemoteRepositoryImpl
import com.becalm.android.data.repository.PersonMemoryStore
import com.becalm.android.domain.person.PersonMemoryCommitment
import com.becalm.android.domain.person.PersonMemoryHash
import com.becalm.android.domain.person.PersonMemoryIdentity
import com.becalm.android.domain.person.PersonMemoryInput
import com.becalm.android.domain.person.PersonMemoryInteraction
import com.becalm.android.domain.person.PersonMemoryMarkdownBuilder
import com.becalm.android.domain.person.PersonMemoryParticipant
import com.becalm.android.integration.local.LocalIntegrationSupport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
class PersonMemoryRemoteRepositoryLocalIntegrationTest {

    private val server = MockWebServer()
    private val store = PersonMemoryStore(LocalIntegrationSupport.appContext())
    private val logger = RecordingLogger()
    private val dispatcher = UnconfinedTestDispatcher()

    @After
    fun tearDown() {
        store.delete(USER_ID, PERSON_ID)
        server.shutdown()
    }

    @Test
    fun `uploadLocalMemory sends validated markdown to Railway`() = runTest {
        val markdown = PersonMemoryMarkdownBuilder.build(memoryInput())
        val digest = PersonMemoryHash.sha256(markdown)
        store.write(USER_ID, PERSON_ID, markdown)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "bucket": "person-memory",
                      "object_path": "$USER_ID/$PERSON_ID/memory.md",
                      "person_id": "$PERSON_ID",
                      "content_hash": "$digest",
                      "generated_at": "$GENERATED_AT"
                    }
                    """.trimIndent(),
                ),
        )

        val result = repository().uploadLocalMemory(USER_ID, PERSON_ID)

        assertTrue(result is BecalmResult.Success<PersonMemoryRemoteMirror>)
        val mirror = (result as BecalmResult.Success).value
        assertEquals("person-memory", mirror.bucket)
        assertEquals("$USER_ID/$PERSON_ID/memory.md", mirror.objectPath)
        assertEquals(digest, mirror.contentHash)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/persons/$PERSON_ID/memory", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"content_hash\":\"$digest\""))
        assertTrue(body.contains("\"content_markdown\""))
        assertTrue(body.contains("Works with Acme as CEO."))
    }

    @Test
    fun `uploadLocalMemory returns NotFound when local memory is missing`() = runTest {
        val result = repository().uploadLocalMemory(USER_ID, PERSON_ID)

        assertTrue(result is BecalmResult.Failure)
        assertTrue((result as BecalmResult.Failure).error is BecalmError.NotFound)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `uploadLocalMemory maps validation response`() = runTest {
        val markdown = PersonMemoryMarkdownBuilder.build(memoryInput())
        store.write(USER_ID, PERSON_ID, markdown)
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"validation_failed","message":"content_hash mismatch"}"""),
        )

        val result = repository().uploadLocalMemory(USER_ID, PERSON_ID)

        assertTrue(result is BecalmResult.Failure)
        assertTrue((result as BecalmResult.Failure).error is BecalmError.Validation)
    }

    private fun repository(): PersonMemoryRemoteRepositoryImpl =
        PersonMemoryRemoteRepositoryImpl(
            api = LocalIntegrationSupport.railwayApi(server),
            memoryStore = store,
            logger = logger,
            ioDispatcher = dispatcher,
        )

    private fun memoryInput(): PersonMemoryInput =
        PersonMemoryInput(
            userId = USER_ID,
            personId = PERSON_ID,
            displayName = "Jane Kim",
            generatedAt = GENERATED_AT,
            identities = listOf(
                PersonMemoryIdentity(
                    identityType = "email",
                    value = "jane@acme.com",
                    verified = true,
                    sourceRef = "raw:raw-1",
                ),
            ),
            participants = listOf(
                PersonMemoryParticipant(
                    sourceRef = "raw:raw-1",
                    sourceType = "gmail",
                    role = "sender",
                    relationToUser = "counterparty",
                    displayName = "Jane Kim",
                    organization = "Acme",
                    title = "CEO",
                    evidence = "Jane Kim, Acme CEO",
                    occurredAt = GENERATED_AT,
                ),
            ),
            interactions = listOf(
                PersonMemoryInteraction(
                    sourceRef = "raw:raw-1",
                    sourceType = "gmail",
                    interactionKind = "email",
                    title = "Pricing renewal",
                    snippet = "Jane asked for revised terms",
                    occurredAt = GENERATED_AT,
                ),
            ),
            commitments = listOf(
                PersonMemoryCommitment(
                    commitmentId = "commitment-1",
                    sourceRef = "commitment:commitment-1",
                    itemType = "action",
                    title = "Send revised terms",
                    status = "pending",
                    quote = "Please send revised terms by Friday",
                    occurredAt = GENERATED_AT,
                ),
            ),
        )

    private companion object {
        const val USER_ID = "user-1"
        const val PERSON_ID = "person-1"
        val GENERATED_AT: Instant = Instant.parse("2026-05-06T00:00:00Z")
    }
}
