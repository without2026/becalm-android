package com.becalm.android.ui.today

import app.cash.turbine.test
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.dto.SourceType
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
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ColdSyncViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val sourceStatusRepository: SourceStatusRepository = mockk()
    private val logger: Logger = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── COLD-VM-01: done=true when all sources are CONNECTED ─────────────────

    @Test
    fun `state emits done=true when all sources are CONNECTED`() = runTest {
        val statuses = SourceType.ALL.map { st ->
            SourceStatus(
                sourceType = st,
                status = SourceConnectionStatus.CONNECTED,
                lastSyncedAt = Clock.System.now(),
                errorMessage = null,
            )
        }
        every { sourceStatusRepository.observeAll() } returns flowOf(statuses)

        val viewModel = ColdSyncViewModel(
            sourceStatusRepository = sourceStatusRepository,
            logger = logger,
        )

        viewModel.state.test {
            // Initial emission may be the default; advance to get the real one.
            var emission = awaitItem()
            // The stateIn initialValue is ColdSyncUiState() — skip if overallProgress is 0 and done is false.
            if (!emission.done) emission = awaitItem()
            assertTrue("done should be true when all sources are CONNECTED", emission.done)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
