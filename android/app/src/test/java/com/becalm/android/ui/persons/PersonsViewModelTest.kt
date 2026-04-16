package com.becalm.android.ui.persons

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.becalm.android.data.local.dao.CommitmentDao
import com.becalm.android.data.local.dao.PersonEnrichmentDao
import com.becalm.android.data.local.dao.RawIngestionEventDao
import com.becalm.android.data.local.entities.PersonEnrichment
import com.becalm.android.data.local.entities.RawIngestionEvent
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// spec: SRC-001 — persons list built from Room person_refs + enrichment join
// spec: SRC-003 — substring search filter
// spec: SRC-005 — unassigned events (person_ref IS NULL)
// spec: ENR-006 — fallback: person_ref shown when no enrichment

@OptIn(ExperimentalCoroutinesApi::class)
class PersonsViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val rawIngestionEventDao: RawIngestionEventDao = mockk()
    private val commitmentDao: CommitmentDao = mockk()
    private val personEnrichmentDao: PersonEnrichmentDao = mockk()

    private fun makeEvent(ref: String, sourceType: String = "voice") = RawIngestionEvent(
        clientEventId = "ev-$ref",
        sourceType = sourceType,
        personRef = ref,
        eventTitle = "Meeting with $ref",
        timestamp = System.currentTimeMillis()
    )

    // spec: SRC-001 — persons list loaded from distinct person_refs
    @Test
    fun `loadPersons builds PersonSummary list from distinct person_refs`() = runTest {
        coEvery { rawIngestionEventDao.getDistinctPersonRefs() } returns listOf("+821012345678")
        coEvery { personEnrichmentDao.getByPersonRefs(any()) } returns listOf(
            PersonEnrichment(personRef = "+821012345678", displayName = "김철수", company = "ABC Corp")
        )
        coEvery { commitmentDao.getPendingCountPerPerson() } returns emptyList()
        coEvery { rawIngestionEventDao.getUnassignedEvents(any(), any()) } returns emptyList()
        coEvery { rawIngestionEventDao.getEventsByPersonRef("+821012345678", limit = 1) } returns
            listOf(makeEvent("+821012345678"))
        coEvery { rawIngestionEventDao.getEventsByPersonRef("+821012345678") } returns
            listOf(makeEvent("+821012345678"))

        val vm = PersonsViewModel(rawIngestionEventDao, commitmentDao, personEnrichmentDao)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(1, state.persons.size)
        assertEquals("김철수", state.persons[0].displayName)
    }

    // spec: ENR-006 — fallback: person_ref shown when no enrichment
    @Test
    fun `person without enrichment shows person_ref as displayName`() = runTest {
        coEvery { rawIngestionEventDao.getDistinctPersonRefs() } returns listOf("unknown@domain.com")
        coEvery { personEnrichmentDao.getByPersonRefs(any()) } returns emptyList()
        coEvery { commitmentDao.getPendingCountPerPerson() } returns emptyList()
        coEvery { rawIngestionEventDao.getUnassignedEvents(any(), any()) } returns emptyList()
        coEvery { rawIngestionEventDao.getEventsByPersonRef("unknown@domain.com", limit = 1) } returns emptyList()
        coEvery { rawIngestionEventDao.getEventsByPersonRef("unknown@domain.com") } returns emptyList()

        val vm = PersonsViewModel(rawIngestionEventDao, commitmentDao, personEnrichmentDao)
        advanceUntilIdle()

        assertEquals("unknown@domain.com", vm.uiState.value.persons[0].displayName)
    }

    // spec: SRC-003 — search filters by substring
    @Test
    fun `onSearchQueryChange filters persons by substring`() = runTest {
        coEvery { rawIngestionEventDao.getDistinctPersonRefs() } returns
            listOf("lee@corp.com", "+821012345678")
        coEvery { personEnrichmentDao.getByPersonRefs(any()) } returns listOf(
            PersonEnrichment(personRef = "+821012345678", displayName = "김철수")
        )
        coEvery { commitmentDao.getPendingCountPerPerson() } returns emptyList()
        coEvery { rawIngestionEventDao.getUnassignedEvents(any(), any()) } returns emptyList()
        coEvery { rawIngestionEventDao.getEventsByPersonRef(any(), limit = 1) } returns emptyList()
        coEvery { rawIngestionEventDao.getEventsByPersonRef(any()) } returns emptyList()

        val vm = PersonsViewModel(rawIngestionEventDao, commitmentDao, personEnrichmentDao)
        advanceUntilIdle()
        vm.onSearchQueryChange("lee")
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.filteredPersons.size)
        assertEquals("lee@corp.com", vm.uiState.value.filteredPersons[0].personRef)
    }

    // spec: SRC-003 — empty search restores full list
    @Test
    fun `onSearchQueryChange with empty query restores all persons`() = runTest {
        coEvery { rawIngestionEventDao.getDistinctPersonRefs() } returns
            listOf("lee@corp.com", "+821012345678")
        coEvery { personEnrichmentDao.getByPersonRefs(any()) } returns emptyList()
        coEvery { commitmentDao.getPendingCountPerPerson() } returns emptyList()
        coEvery { rawIngestionEventDao.getUnassignedEvents(any(), any()) } returns emptyList()
        coEvery { rawIngestionEventDao.getEventsByPersonRef(any(), limit = 1) } returns emptyList()
        coEvery { rawIngestionEventDao.getEventsByPersonRef(any()) } returns emptyList()

        val vm = PersonsViewModel(rawIngestionEventDao, commitmentDao, personEnrichmentDao)
        advanceUntilIdle()
        vm.onSearchQueryChange("lee")
        advanceUntilIdle()
        vm.onSearchQueryChange("")
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.filteredPersons.size)
    }

    // spec: SRC-005 — unassigned events shown separately
    @Test
    fun `unassigned events appear in unassignedEvents list`() = runTest {
        val unassignedEvent = RawIngestionEvent(
            clientEventId = "ev-unassigned",
            sourceType = "gmail",
            personRef = null,
            timestamp = System.currentTimeMillis()
        )
        coEvery { rawIngestionEventDao.getDistinctPersonRefs() } returns emptyList()
        coEvery { personEnrichmentDao.getByPersonRefs(any()) } returns emptyList()
        coEvery { commitmentDao.getPendingCountPerPerson() } returns emptyList()
        coEvery { rawIngestionEventDao.getUnassignedEvents(any(), any()) } returns listOf(unassignedEvent)

        val vm = PersonsViewModel(rawIngestionEventDao, commitmentDao, personEnrichmentDao)
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.unassignedEvents.size)
    }
}
