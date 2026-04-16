package com.becalm.android.ui.persons

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.repository.RawIngestionRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RawEventDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val rawIngestionRepository: RawIngestionRepository = mockk()
    private val logger: Logger = mockk(relaxed = true)

    // The VM looks up by primary-key id (findById), not by clientEventId.
    private val eventId = "raw-primary-id-abc-123"
    private val now = Clock.System.now()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): RawEventDetailViewModel {
        val handle = SavedStateHandle(mapOf(ARG_EVENT_ID to eventId))
        return RawEventDetailViewModel(
            rawIngestionRepository = rawIngestionRepository,
            savedStateHandle = handle,
            logger = logger,
        )
    }

    private fun makeEntity(): RawIngestionEventEntity = RawIngestionEventEntity(
        id = eventId,
        userId = "user-1",
        clientEventId = "client-event-abc",
        sourceType = "voice",
        eventTitle = "Team stand-up recording",
        timestamp = now,
    )

    // ─── Test 1: loads event when repository returns a match ─────────────────

    @Test
    fun `loads event and emits it in uiState when repository returns non-null`() =
        runTest(testDispatcher) {
            val entity = makeEntity()
            coEvery { rawIngestionRepository.findById(eventId) } returns entity

            val vm = buildViewModel()

            vm.uiState.test {
                var state = awaitItem()
                while (state.loading) state = awaitItem()

                assertNotNull(state.event)
                assertEquals(entity, state.event)
                assertEquals(false, state.loading)
                assertNull(state.error)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── Test 2: null event sets generic error state ──────────────────────────

    /**
     * The VM emits a generic "Event not found" message (R6: no id interpolation).
     */
    @Test
    fun `null result from repository sets generic error in uiState`() = runTest(testDispatcher) {
        coEvery { rawIngestionRepository.findById(eventId) } returns null

        val vm = buildViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            assertNull(state.event)
            assertEquals(false, state.loading)
            assertNotNull(state.error)
            assertEquals("Event not found", state.error)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
