package com.becalm.android.ui.persons

import app.cash.turbine.test
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import io.mockk.coEvery
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PersonsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val personEnrichmentRepository: PersonEnrichmentRepository = mockk()
    private val rawIngestionRepository: RawIngestionRepository = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

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
        val vm = PersonsViewModel(personEnrichmentRepository, rawIngestionRepository, logger)

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

        val vm = PersonsViewModel(personEnrichmentRepository, rawIngestionRepository, logger)
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

    // ─── Test 3: toggleStar surfaces R3 error in uiState ─────────────────────

    @Test
    fun `onToggleStar surfaces starred-not-yet-supported error from setStarred`() =
        runTest(testDispatcher) {
            enrichmentFlow.value = listOf(makeEntity("carol@example.com", "Carol"))

            // Simulate the R3 fail-loud behaviour from PersonEnrichmentRepositoryImpl
            coEvery {
                personEnrichmentRepository.setStarred("carol@example.com", true)
            } returns BecalmResult.Failure(
                BecalmError.Unknown(
                    UnsupportedOperationException(
                        "starred column not yet present on PersonEnrichmentEntity (SP-05 pending)",
                    ),
                ),
            )

            val vm = PersonsViewModel(personEnrichmentRepository, rawIngestionRepository, logger)
            advanceTimeBy(400)

            vm.uiState.test {
                // Drain to loaded state
                var state = awaitItem()
                while (state.loading) {
                    state = awaitItem()
                }

                vm.onToggleStar("carol@example.com", true)
                advanceTimeBy(100)

                val errorState = awaitItem()
                assertNotNull(errorState.error)
                assertTrue(
                    "Error message should mention SP-05",
                    errorState.error!!.contains("SP-05", ignoreCase = true) ||
                        errorState.error.contains("not yet supported", ignoreCase = true),
                )

                cancelAndIgnoreRemainingEvents()
            }
        }
}
