package com.becalm.android.ui.persons

import app.cash.turbine.test
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.repository.PersonEnrichmentRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PersonsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val personEnrichmentRepository: PersonEnrichmentRepository = mockk()

    private val enrichmentFlow = MutableStateFlow<List<PersonEnrichmentEntity>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { personEnrichmentRepository.observeAll() } returns enrichmentFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeEntity(ref: String, name: String?) = PersonEnrichmentEntity(
        personRef = ref,
        displayName = name,
        lastSyncedAt = Clock.System.now(),
    )

    // ─── Test 1: list emits people rows from enrichment flow ──────────────────

    @Test
    fun `list emits PersonRow for each enrichment entity`() = runTest(testDispatcher) {
        val vm = PersonsViewModel(personEnrichmentRepository)

        vm.uiState.test {
            // Initial loading state
            val initial = awaitItem()
            assertTrue(initial.loading)

            // Push one entity into the flow
            enrichmentFlow.value = listOf(makeEntity("alice@example.com", "Alice"))
            advanceTimeBy(400) // past debounce

            val populated = awaitItem()
            assertEquals(false, populated.loading)
            assertEquals(1, populated.people.size)
            assertEquals("alice@example.com", populated.people[0].personRef)
            assertEquals("Alice", populated.people[0].displayName)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Test 2: query debounce filters list ─────────────────────────────────

    @Test
    fun `query debounce filters people by displayName`() = runTest(testDispatcher) {
        enrichmentFlow.value = listOf(
            makeEntity("alice@example.com", "Alice"),
            makeEntity("bob@example.com", "Bob"),
        )

        val vm = PersonsViewModel(personEnrichmentRepository)
        advanceTimeBy(400)

        vm.uiState.test {
            // Drain to loaded state
            var state = awaitItem()
            while (state.loading || state.people.isEmpty()) {
                state = awaitItem()
            }
            assertEquals(2, state.people.size)

            // Type "ali" — should filter to only Alice after debounce settles
            vm.onQueryChange("ali")
            // Before debounce: still 2 (no new emission yet)
            advanceTimeBy(100)
            // After debounce window
            advanceTimeBy(300)

            val filtered = awaitItem()
            assertEquals("ali", filtered.query)
            assertEquals(1, filtered.people.size)
            assertEquals("Alice", filtered.people[0].displayName)

            cancelAndIgnoreRemainingEvents()
        }
    }

}
