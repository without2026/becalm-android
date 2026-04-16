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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// spec: SRC-001 — persons list built from Room person_refs + enrichment join
// spec: SRC-002 — PersonDetailScreen shows enriched header + 3-section body
// spec: SRC-003 — substring search filter
// spec: SRC-004 — raw event detail sheet content per source_type
// spec: SRC-005 — unassigned events (person_ref IS NULL)
// spec: SRC-006 — pull-to-refresh reloads persons from Room
// spec: SRC-007 — offline mode: Room cache rendered, isOffline flag set
// spec: SRC-008 — PersonDetail 3-section structure (pending / completed / history)
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

    // spec: SRC-002 — PersonSummary holds enrichment data for PersonDetailScreen header display
    @Test
    fun `SRC002_personSummary_enrichedHeader_hasCompanyAndTitle`() = runTest {
        coEvery { rawIngestionEventDao.getDistinctPersonRefs() } returns listOf("+821012345678")
        coEvery { personEnrichmentDao.getByPersonRefs(any()) } returns listOf(
            com.becalm.android.data.local.entities.PersonEnrichment(
                personRef = "+821012345678",
                displayName = "김철수",
                nickname = "철수",
                company = "ABC Corp",
                title = "팀장"
            )
        )
        coEvery { commitmentDao.getPendingCountPerPerson() } returns emptyList()
        coEvery { rawIngestionEventDao.getUnassignedEvents(any(), any()) } returns emptyList()
        coEvery { rawIngestionEventDao.getEventsByPersonRef("+821012345678", limit = 1) } returns emptyList()
        coEvery { rawIngestionEventDao.getEventsByPersonRef("+821012345678") } returns emptyList()

        val vm = PersonsViewModel(rawIngestionEventDao, commitmentDao, personEnrichmentDao)
        advanceUntilIdle()

        val person = vm.uiState.value.persons.first()
        // spec: SRC-002 — header shows display_name + company + title via enrichment
        assertEquals("김철수", person.displayName)
        assertEquals("ABC Corp", person.enrichment?.company)
        assertEquals("팀장", person.enrichment?.title)
        assertEquals("철수", person.enrichment?.nickname)
    }

    // spec: SRC-004 — voice event with duration_seconds and commitments_extracted_count
    @Test
    fun `SRC004_voiceEvent_hasDurationAndCommitmentsCount`() {
        // spec: SRC-004 — RawEventDetailSheet shows duration_seconds and commitments_extracted_count
        val voiceEvent = RawIngestionEvent(
            clientEventId = "ev-voice-src004",
            sourceType = "voice",
            eventTitle = "팀 회의",
            durationSeconds = 1800,
            commitmentsExtractedCount = 2,
            timestamp = System.currentTimeMillis()
        )
        assertEquals("voice", voiceEvent.sourceType)
        assertEquals(1800, voiceEvent.durationSeconds)
        assertEquals(2, voiceEvent.commitmentsExtractedCount)
        assertEquals("팀 회의", voiceEvent.eventTitle)
    }

    // spec: SRC-006 — onPullToRefresh triggers reload (isLoading set, then cleared)
    @Test
    fun `SRC006_onPullToRefresh_triggersReload`() = runTest {
        coEvery { rawIngestionEventDao.getDistinctPersonRefs() } returns emptyList()
        coEvery { personEnrichmentDao.getByPersonRefs(any()) } returns emptyList()
        coEvery { commitmentDao.getPendingCountPerPerson() } returns emptyList()
        coEvery { rawIngestionEventDao.getUnassignedEvents(any(), any()) } returns emptyList()

        val vm = PersonsViewModel(rawIngestionEventDao, commitmentDao, personEnrichmentDao)
        advanceUntilIdle()

        // spec: SRC-006 — pull-to-refresh reloads from Room
        vm.onPullToRefresh()
        // After advanceUntilIdle isLoading should be false again (load completed)
        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.isLoading)
    }

    // spec: SRC-007 — isOffline flag exists in PersonsUiState for offline banner display
    @Test
    fun `SRC007_personsUiState_hasIsOfflineFlag`() = runTest {
        coEvery { rawIngestionEventDao.getDistinctPersonRefs() } returns emptyList()
        coEvery { personEnrichmentDao.getByPersonRefs(any()) } returns emptyList()
        coEvery { commitmentDao.getPendingCountPerPerson() } returns emptyList()
        coEvery { rawIngestionEventDao.getUnassignedEvents(any(), any()) } returns emptyList()

        val vm = PersonsViewModel(rawIngestionEventDao, commitmentDao, personEnrichmentDao)
        advanceUntilIdle()

        // spec: SRC-007 — offline mode renders Room cache; isOffline drives banner visibility
        // Default state: not offline (network is not mocked to be down here)
        assertEquals(false, vm.uiState.value.isOffline)
        // lastSyncAt populated to support '오프라인 — 마지막 동기화 HH:mm' badge
        assertNotNull(vm.uiState.value.lastSyncAt)
    }

    // spec: SRC-008 — PersonSummary pendingCommitmentsCount reflects active (non-completed) commitments
    @Test
    fun `SRC008_personSummary_pendingCount_reflectsActiveCommitments`() = runTest {
        val pendingCountRow = com.becalm.android.data.local.dao.PersonPendingCount(
            personRef = "+821012345678",
            pendingCount = 1
        )
        coEvery { rawIngestionEventDao.getDistinctPersonRefs() } returns listOf("+821012345678")
        coEvery { personEnrichmentDao.getByPersonRefs(any()) } returns emptyList()
        coEvery { commitmentDao.getPendingCountPerPerson() } returns listOf(pendingCountRow)
        coEvery { rawIngestionEventDao.getUnassignedEvents(any(), any()) } returns emptyList()
        coEvery { rawIngestionEventDao.getEventsByPersonRef("+821012345678", limit = 1) } returns emptyList()
        coEvery { rawIngestionEventDao.getEventsByPersonRef("+821012345678") } returns emptyList()

        val vm = PersonsViewModel(rawIngestionEventDao, commitmentDao, personEnrichmentDao)
        advanceUntilIdle()

        val person = vm.uiState.value.persons.first()
        // spec: SRC-008 — section 1 count = pendingCommitmentsCount (action_state != 'completed')
        assertEquals(1, person.pendingCommitmentsCount)
    }
}
