package com.becalm.android.unit.data.repository

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.PersonEventDto
import com.becalm.android.data.remote.dto.PersonEventsResponse
import com.becalm.android.data.repository.PersonDetailRemoteRepositoryImpl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class PersonDetailRemoteRepositorySpecTest {
    private val dispatcher = StandardTestDispatcher()
    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun refreshPersonEventsMaterializesRecallRowsIntoPersonInteractions() = runTest(dispatcher) {
        val dao = mockk<PersonIndexDao>(relaxed = true)
        val api = mockk<RailwayApi>()
        val interactions = slot<List<PersonInteractionEntity>>()
        coEvery { dao.upsertInteractions(capture(interactions)) } returns Unit
        coEvery {
            api.getPersonEvents(personId = "person-1", cursor = null, limit = 100)
        } returns Response.success(
            PersonEventsResponse(
                data = listOf(
                    personEvent(
                        id = "source-event-1",
                        sourceType = "gmail",
                        sourceRef = "gmail-message-1",
                        sourceEventId = "source-event-1",
                        eventKind = "email",
                        role = "sender",
                        title = "Project update",
                    ),
                    personEvent(
                        id = "manual-source-1",
                        sourceType = "manual",
                        sourceRef = null,
                        interactionKey = "user-1:person-1:manual_memory:memory-1::manual_onboarding",
                        eventKind = "manual",
                        title = "Manual memory",
                    ),
                    personEvent(
                        id = "manual-commitment-1",
                        sourceType = "manual",
                        interactionKey = "user-1:person-1:manual_memory:memory-1:commitment-1:commitment",
                        interactionType = "commitment",
                        commitmentId = "commitment-1",
                        eventKind = "commitment",
                        title = "Commitment row",
                    ),
                ),
                cursor = "",
                hasMore = false,
            ),
        )
        val repository = PersonDetailRemoteRepositoryImpl(dao, api, logger, dispatcher)

        val result = repository.refreshPersonEvents(userId = "user-1", personId = "person-1")

        check(result is BecalmResult.Success)
        assertEquals(3, result.value.fetched)
        assertEquals(2, result.value.upserted)
        val rows = interactions.captured
        assertEquals("gmail-message-1", rows[0].sourceRef)
        assertEquals("source-event-1", rows[0].sourceEventId)
        assertEquals("email", rows[0].interactionKind)
        assertEquals("sender", rows[0].role)
        assertEquals("manual_memory:memory-1", rows[1].sourceRef)
        assertEquals("onboarding", rows[1].status)
        assertEquals("manual", rows[1].interactionKind)
    }

    @Test
    fun refreshPersonEventsReturnsRetryableFailureWithoutLocalMutationOnServerError() = runTest(dispatcher) {
        val dao = mockk<PersonIndexDao>(relaxed = true)
        val api = mockk<RailwayApi>()
        coEvery {
            api.getPersonEvents(personId = "person-1", cursor = null, limit = 100)
        } returns Response.error(503, """{"error":"upstream_unavailable"}""".toResponseBody())
        val repository = PersonDetailRemoteRepositoryImpl(dao, api, logger, dispatcher)

        val result = repository.refreshPersonEvents(userId = "user-1", personId = "person-1")

        check(result is BecalmResult.Failure)
        assertTrue(result.error is BecalmError.ServerError)
        coVerify(exactly = 0) { dao.upsertInteractions(any()) }
    }

    private fun personEvent(
        id: String,
        sourceType: String,
        sourceRef: String? = "source-ref-1",
        sourceEventId: String? = null,
        commitmentId: String? = null,
        interactionKey: String? = null,
        interactionType: String? = null,
        eventKind: String? = null,
        role: String? = null,
        title: String,
    ): PersonEventDto =
        PersonEventDto(
            id = id,
            userId = "user-1",
            personId = "person-1",
            sourceEventId = sourceEventId,
            commitmentId = commitmentId,
            interactionKey = interactionKey,
            interactionType = interactionType,
            sourceType = sourceType,
            sourceRef = sourceRef,
            eventKind = eventKind,
            role = role,
            occurredAt = Instant.parse("2026-06-03T02:00:00Z"),
            title = title,
            snippet = "Snippet",
            confidence = 0.9,
            createdAt = Instant.parse("2026-06-03T02:00:01Z"),
        )
}
