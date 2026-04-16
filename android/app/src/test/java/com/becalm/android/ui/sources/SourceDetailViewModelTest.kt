package com.becalm.android.ui.sources

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val sourceStatusRepository: SourceStatusRepository = mockk()
    private val rawIngestionRepository: RawIngestionRepository = mockk()
    private val logger: Logger = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(sourceTypeArg: String?): SourceDetailViewModel {
        val args = if (sourceTypeArg != null) mapOf(ARG_SOURCE_TYPE to sourceTypeArg) else emptyMap()
        return SourceDetailViewModel(
            savedStateHandle = SavedStateHandle(args),
            sourceStatusRepository = sourceStatusRepository,
            rawIngestionRepository = rawIngestionRepository,
            logger = logger,
        )
    }

    private fun makeRawEvent(
        id: String = "raw-1",
        sourceType: String = SourceType.GMAIL,
        now: Instant = Clock.System.now(),
    ): RawIngestionEventEntity = RawIngestionEventEntity(
        id = id,
        userId = "user-1",
        clientEventId = "client-$id",
        sourceType = sourceType,
        eventTitle = "Email subject",
        timestamp = now,
    )

    // ── DETAIL-VM-01: valid sourceType resolves status and empty events ────────

    @Test
    fun `valid sourceType combines status and filtered events`() = runTest {
        val sourceType = SourceType.GMAIL
        val now = Clock.System.now()

        val fakeStatus = SourceStatus(
            sourceType = sourceType,
            status = SourceConnectionStatus.CONNECTED,
            lastSyncedAt = now,
            errorMessage = null,
        )
        every { sourceStatusRepository.observeFor(sourceType) } returns flowOf(fakeStatus)
        every { rawIngestionRepository.observeTimelineForUser(any(), any()) } returns
            flowOf(listOf(makeRawEvent(sourceType = sourceType)))

        val viewModel = buildViewModel(sourceType)
        viewModel.setUserId("user-1")

        viewModel.state.test {
            var emission = awaitItem()
            if (emission.status.isEmpty()) emission = awaitItem()

            assertEquals(sourceType, emission.sourceType)
            assertEquals(SourceConnectionStatus.CONNECTED.name, emission.status)
            assertEquals(1, emission.recentEvents.size)
            assertNull(emission.error)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── DETAIL-VM-02: blank sourceType → error state ──────────────────────────

    @Test
    fun `blank sourceType emits error state`() = runTest {
        val viewModel = buildViewModel("")

        viewModel.state.test {
            val state = awaitItem()
            assertNotNull(state.error)
            assertTrue(
                "Error should mention blank or missing: ${state.error}",
                state.error!!.contains("blank") || state.error!!.contains("missing"),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── DETAIL-VM-03: missing sourceType arg → error state ────────────────────

    @Test
    fun `missing sourceType arg emits error state`() = runTest {
        val viewModel = buildViewModel(null)

        viewModel.state.test {
            val state = awaitItem()
            assertNotNull(state.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── DETAIL-VM-04: sourceType not in SourceType.ALL → error state ──────────

    @Test
    fun `sourceType not in SourceType ALL emits Invalid source type error`() = runTest {
        val viewModel = buildViewModel("unknown_source_xyz")

        viewModel.state.test {
            val state = awaitItem()
            assertEquals("Invalid source type", state.error)
            assertTrue(state.recentEvents.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── DETAIL-VM-05: valid sourceType filters out other source events ─────────

    @Test
    fun `valid sourceType filters out events from other sources`() = runTest {
        val sourceType = SourceType.VOICE
        val now = Clock.System.now()

        val fakeStatus = SourceStatus(
            sourceType = sourceType,
            status = SourceConnectionStatus.CONNECTED,
            lastSyncedAt = now,
            errorMessage = null,
        )
        every { sourceStatusRepository.observeFor(sourceType) } returns flowOf(fakeStatus)
        // Timeline returns one VOICE and one GMAIL event.
        every { rawIngestionRepository.observeTimelineForUser(any(), any()) } returns
            flowOf(
                listOf(
                    makeRawEvent(id = "v-1", sourceType = SourceType.VOICE),
                    makeRawEvent(id = "g-1", sourceType = SourceType.GMAIL),
                ),
            )

        val viewModel = buildViewModel(sourceType)
        viewModel.setUserId("user-1")

        viewModel.state.test {
            var emission = awaitItem()
            if (emission.status.isEmpty()) emission = awaitItem()

            assertEquals(1, emission.recentEvents.size)
            assertEquals("v-1", emission.recentEvents.first().id)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
