package com.becalm.android.unit.data.repository

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.entity.PersonEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.PersonListResponse
import com.becalm.android.data.remote.dto.PersonSummaryDto
import com.becalm.android.data.repository.PersonDetailRemoteRepository
import com.becalm.android.data.repository.PersonListRemoteRepositoryImpl
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

class PersonListRemoteRepositorySpecTest {
    private val dispatcher = StandardTestDispatcher()
    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun refreshPeopleMaterializesSummariesAndBoundedRecallRows() = runTest(dispatcher) {
        val dao = mockk<PersonIndexDao>(relaxed = true)
        val api = mockk<RailwayApi>()
        val detailRepository = mockk<PersonDetailRemoteRepository>()
        val persons = slot<List<PersonEntity>>()
        val identities = slot<List<PersonIdentityEntity>>()
        coEvery { dao.findPersonsByIds("user-1", listOf("person-remote")) } returns emptyList()
        coEvery { dao.upsertPersons(capture(persons)) } returns Unit
        coEvery { dao.upsertIdentities(capture(identities)) } returns Unit
        coEvery {
            api.getPersons(cursor = null, limit = 50, query = null)
        } returns Response.success(
            PersonListResponse(
                data = listOf(
                    PersonSummaryDto(
                        personId = "person-remote",
                        displayName = "Remote Person",
                        kind = "person",
                        primaryEmail = "remote@example.com",
                        primaryPhone = "+821012345678",
                        lastContactAt = Instant.parse("2026-06-03T02:00:00Z"),
                        openCommitmentsCount = 1,
                    ),
                ),
                cursor = "",
                hasMore = false,
            ),
        )
        coEvery {
            detailRepository.refreshPersonEvents(
                userId = "user-1",
                personId = "person-remote",
                limit = 50,
            )
        } returns BecalmResult.Success(
            PersonDetailRemoteRepository.RefreshStats(
                fetched = 2,
                upserted = 1,
                hasMore = false,
                nextCursor = null,
            ),
        )
        val repository = PersonListRemoteRepositoryImpl(dao, detailRepository, api, logger, dispatcher)

        val result = repository.refreshPeople(userId = "user-1")

        check(result is BecalmResult.Success)
        assertEquals(1, result.value.fetched)
        assertEquals(1, result.value.upserted)
        assertEquals(2, result.value.eventRowsFetched)
        assertEquals(1, result.value.eventRowsUpserted)
        assertEquals("person-remote", persons.captured.single().id)
        assertEquals("Remote Person", persons.captured.single().displayName)
        assertEquals(listOf("email", "phone"), identities.captured.map { it.identityType }.sorted())
    }

    @Test
    fun refreshPeopleReturnsServerFailureWithoutLocalMutation() = runTest(dispatcher) {
        val dao = mockk<PersonIndexDao>(relaxed = true)
        val api = mockk<RailwayApi>()
        val detailRepository = mockk<PersonDetailRemoteRepository>(relaxed = true)
        coEvery {
            api.getPersons(cursor = null, limit = 50, query = null)
        } returns Response.error(503, """{"error":"upstream_unavailable"}""".toResponseBody())
        val repository = PersonListRemoteRepositoryImpl(dao, detailRepository, api, logger, dispatcher)

        val result = repository.refreshPeople(userId = "user-1")

        check(result is BecalmResult.Failure)
        assertTrue(result.error is BecalmError.ServerError)
        coVerify(exactly = 0) { dao.upsertPersons(any()) }
        coVerify(exactly = 0) { detailRepository.refreshPersonEvents(any(), any(), any()) }
    }
}
