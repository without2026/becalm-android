package com.becalm.android.unit.ui.today

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.ui.today.ColdSyncEffect
import com.becalm.android.ui.today.ColdSyncLifecycleCoordinator
import com.becalm.android.ui.today.ColdSyncRuntimeCoordinator
import com.becalm.android.ui.today.ColdSyncTransitionSnapshot
import com.becalm.android.ui.today.ColdSyncUiState
import com.becalm.android.ui.today.ColdSyncViewModel
import com.becalm.android.ui.today.DefaultColdSyncRuntimeCoordinator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ColdSyncViewModelSpecTest {

    private val testDispatcher = StandardTestDispatcher()
    private val sourceStatusFlow = MutableStateFlow<List<SourceStatus>>(emptyList())
    private val enabledSourcesFlow = MutableStateFlow<Set<String>>(emptySet())
    private val sourceStatusRepository: SourceStatusRepository = mockk()
    private val userPrefsStore: UserPrefsStore = mockk()
    private lateinit var lifecycleCoordinator: FakeColdSyncLifecycleCoordinator
    private lateinit var runtimeCoordinator: FakeColdSyncRuntimeCoordinator
    private val logger: Logger = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        lifecycleCoordinator = FakeColdSyncLifecycleCoordinator()
        runtimeCoordinator = FakeColdSyncRuntimeCoordinator()
        sourceStatusFlow.value = emptyList()
        enabledSourcesFlow.value = emptySet()
        every { sourceStatusRepository.observeAll() } returns sourceStatusFlow
        every { userPrefsStore.observeEnabledSources() } returns enabledSourcesFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `TDY-010 COLD-001 progress excludes voice and call recording from stage1 tracking`() = runTest {
        sourceStatusFlow.value = listOf(
            sourceStatus(SourceType.VOICE, SourceConnectionStatus.CONNECTED),
            sourceStatus(SourceType.CALL_RECORDING, SourceConnectionStatus.CONNECTED),
        )

        val viewModel = buildViewModel()

        viewModel.state.test {
            val state = awaitMappedState(this@runTest, viewModel, this)

            assertFalse(state.perSourceProgress.containsKey(SourceType.VOICE))
            assertFalse(state.perSourceProgress.containsKey(SourceType.CALL_RECORDING))
            assertEquals(setOf(DefaultColdSyncRuntimeCoordinator.USER_PROFILE_SOURCE_ID), state.perSourceProgress.keys)
            assertEquals(0f, state.perSourceProgress.getValue(DefaultColdSyncRuntimeCoordinator.USER_PROFILE_SOURCE_ID))
            assertEquals(0f, state.overallProgress)
            assertFalse(state.done)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `TDY-010 COLD-002 progress keeps syncing and never connected at zero while terminal rows are one`() = runTest {
        runtimeCoordinator.userProfileReady.value = false
        enabledSourcesFlow.value = setOf(
            SourceType.GMAIL,
            SourceType.OUTLOOK_MAIL,
            SourceType.NAVER_IMAP,
            SourceType.GOOGLE_CALENDAR,
        )
        sourceStatusFlow.value = listOf(
            sourceStatus(SourceType.GMAIL, SourceConnectionStatus.SYNCING),
            sourceStatus(SourceType.OUTLOOK_MAIL, SourceConnectionStatus.NEVER_CONNECTED),
            sourceStatus(SourceType.NAVER_IMAP, SourceConnectionStatus.ERROR),
            sourceStatus(SourceType.GOOGLE_CALENDAR, SourceConnectionStatus.CONNECTED),
        )

        val viewModel = buildViewModel()

        viewModel.state.test {
            val state = awaitMappedState(this@runTest, viewModel, this)

            assertEquals(0f, state.perSourceProgress.getValue(SourceType.GMAIL))
            assertEquals(0f, state.perSourceProgress.getValue(SourceType.OUTLOOK_MAIL))
            assertEquals(1f, state.perSourceProgress.getValue(SourceType.NAVER_IMAP))
            assertEquals(1f, state.perSourceProgress.getValue(SourceType.GOOGLE_CALENDAR))
            assertEquals(0f, state.perSourceProgress.getValue(DefaultColdSyncRuntimeCoordinator.USER_PROFILE_SOURCE_ID))
            assertEquals(0.4f, state.overallProgress)
            assertFalse(state.done)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `TDY-010 COLD-003 done flips true once every tracked stage1 source is terminal`() = runTest {
        runtimeCoordinator.userProfileReady.value = true
        enabledSourcesFlow.value = setOf(
            SourceType.GMAIL,
            SourceType.OUTLOOK_MAIL,
            SourceType.NAVER_IMAP,
            SourceType.DAUM_IMAP,
            SourceType.GOOGLE_CALENDAR,
            SourceType.OUTLOOK_CALENDAR,
        )
        sourceStatusFlow.value = listOf(
            sourceStatus(SourceType.GMAIL, SourceConnectionStatus.ERROR),
            sourceStatus(SourceType.OUTLOOK_MAIL, SourceConnectionStatus.CONNECTED),
            sourceStatus(SourceType.NAVER_IMAP, SourceConnectionStatus.ERROR),
            sourceStatus(SourceType.DAUM_IMAP, SourceConnectionStatus.CONNECTED),
            sourceStatus(SourceType.GOOGLE_CALENDAR, SourceConnectionStatus.CONNECTED),
            sourceStatus(SourceType.OUTLOOK_CALENDAR, SourceConnectionStatus.ERROR),
        )

        val viewModel = buildViewModel()

        viewModel.state.test {
            val state = awaitMappedState(this@runTest, viewModel, this)

            assertTrue(state.done)
            assertEquals(1f, state.overallProgress)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `COLD-002b done flips when only the user-enabled source reaches terminal state`() = runTest {
        runtimeCoordinator.userProfileReady.value = true
        enabledSourcesFlow.value = setOf(SourceType.GMAIL)
        sourceStatusFlow.value = listOf(
            sourceStatus(SourceType.GMAIL, SourceConnectionStatus.CONNECTED),
            sourceStatus(SourceType.OUTLOOK_MAIL, SourceConnectionStatus.NEVER_CONNECTED),
            sourceStatus(SourceType.NAVER_IMAP, SourceConnectionStatus.NEVER_CONNECTED),
            sourceStatus(SourceType.GOOGLE_CALENDAR, SourceConnectionStatus.NEVER_CONNECTED),
        )

        val viewModel = buildViewModel()

        viewModel.state.test {
            val state = awaitMappedState(this@runTest, viewModel, this)

            assertTrue(state.done)
            assertEquals(setOf(SourceType.GMAIL, DefaultColdSyncRuntimeCoordinator.USER_PROFILE_SOURCE_ID), state.perSourceProgress.keys)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `COLD-002c done flips on profile readiness alone when no source is enabled`() = runTest {
        runtimeCoordinator.userProfileReady.value = true
        enabledSourcesFlow.value = emptySet()
        sourceStatusFlow.value = listOf(
            sourceStatus(SourceType.GMAIL, SourceConnectionStatus.NEVER_CONNECTED),
            sourceStatus(SourceType.GOOGLE_CALENDAR, SourceConnectionStatus.NEVER_CONNECTED),
        )

        val viewModel = buildViewModel()

        viewModel.state.test {
            val state = awaitMappedState(this@runTest, viewModel, this)

            assertTrue(state.done)
            assertEquals(setOf(DefaultColdSyncRuntimeCoordinator.USER_PROFILE_SOURCE_ID), state.perSourceProgress.keys)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `COLD-002d done stays false while any enabled source is still syncing`() = runTest {
        runtimeCoordinator.userProfileReady.value = true
        enabledSourcesFlow.value = setOf(SourceType.GMAIL, SourceType.OUTLOOK_MAIL)
        sourceStatusFlow.value = listOf(
            sourceStatus(SourceType.GMAIL, SourceConnectionStatus.CONNECTED),
            sourceStatus(SourceType.OUTLOOK_MAIL, SourceConnectionStatus.SYNCING),
        )

        val viewModel = buildViewModel()

        viewModel.state.test {
            val state = awaitMappedState(this@runTest, viewModel, this)

            assertFalse(state.done)
            assertEquals(0f, state.perSourceProgress.getValue(SourceType.OUTLOOK_MAIL))
            assertEquals(1f, state.perSourceProgress.getValue(SourceType.GMAIL))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `COLD-001 onScreenVisible starts stage1 exactly once`() = runTest {
        val viewModel = buildViewModel()

        viewModel.onScreenVisible()
        viewModel.onScreenVisible()
        advanceUntilIdle()

        assertEquals(1, runtimeCoordinator.startStage1Calls.size)
    }

    @Test
    fun `COLD-001 stage1 start failure projects visible transition error`() = runTest {
        runtimeCoordinator.startStage1Result = BecalmResult.Failure(BecalmError.Io("boom"))
        val viewModel = buildViewModel()

        viewModel.onScreenVisible()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.transitionError)
        assertFalse(viewModel.state.value.transitioning)
    }

    @Test
    fun `COLD-003 onStage1Completed emits navigate effect after lifecycle handoff succeeds`() = runTest {
        val at = Instant.parse("2026-04-23T00:00:00Z")
        val snapshot = ColdSyncTransitionSnapshot(
            onboardingCompleted = true,
            stage1CompletedAt = at,
            stage1Deferred = false,
            deferredAt = null,
            stage2Scheduled = true,
            stage1DeferredScheduled = false,
        )
        sourceStatusFlow.value = listOf(sourceStatus(SourceType.GMAIL, SourceConnectionStatus.CONNECTED))
        lifecycleCoordinator.completeStage1Result = BecalmResult.Success(snapshot)
        val viewModel = buildViewModel()

        viewModel.effects.test {
            viewModel.onStage1Completed(at)
            advanceUntilIdle()

            assertEquals(listOf(at), lifecycleCoordinator.completeStage1Calls)
            assertEquals(ColdSyncEffect.NavigateToToday, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `COLD-008 stage1 completion should project transition snapshot into state`() = runTest {
        val at = Instant.parse("2026-04-23T00:00:00Z")
        val snapshot = ColdSyncTransitionSnapshot(
            onboardingCompleted = true,
            stage1CompletedAt = at,
            stage1Deferred = false,
            deferredAt = null,
            stage2Scheduled = true,
            stage1DeferredScheduled = false,
        )
        sourceStatusFlow.value = listOf(sourceStatus(SourceType.GMAIL, SourceConnectionStatus.CONNECTED))
        lifecycleCoordinator.completeStage1Result = BecalmResult.Success(snapshot)
        val viewModel = buildViewModel()

        advanceUntilIdle()
        viewModel.onStage1Completed(at)
        advanceUntilIdle()

        assertEquals(snapshot, viewModel.state.value.lastTransition)
        assertFalse(viewModel.state.value.transitioning)
    }

    @Test
    fun `COLD-008 stage1 completion failure projects visible transition error`() = runTest {
        lifecycleCoordinator.completeStage1Result = BecalmResult.Failure(BecalmError.Io("boom"))
        val viewModel = buildViewModel()

        viewModel.onStage1Completed(Instant.parse("2026-04-23T00:00:00Z"))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.transitionError)
        assertFalse(viewModel.state.value.transitioning)
        assertNull(viewModel.state.value.lastTransition)
    }

    @Test
    fun `COLD-006 skip stays disabled for five seconds then becomes enabled`() = runTest {
        sourceStatusFlow.value = listOf(sourceStatus(SourceType.GMAIL, SourceConnectionStatus.SYNCING))
        val viewModel = buildViewModel()
        viewModel.onScreenVisible()
        assertFalse(viewModel.state.value.skipEnabled)

        advanceTimeBy(5_000)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.skipEnabled)
    }

    @Test
    fun `COLD-006 onSkipForNow after enable emits navigate effect and defers stage1`() = runTest {
        val at = Instant.parse("2026-04-23T00:05:00Z")
        val snapshot = ColdSyncTransitionSnapshot(
            onboardingCompleted = true,
            stage1CompletedAt = null,
            stage1Deferred = true,
            deferredAt = at,
            stage2Scheduled = false,
            stage1DeferredScheduled = true,
        )
        sourceStatusFlow.value = listOf(sourceStatus(SourceType.GMAIL, SourceConnectionStatus.SYNCING))
        lifecycleCoordinator.deferStage1Result = BecalmResult.Success(snapshot)
        val viewModel = buildViewModel()
        viewModel.onScreenVisible()

        assertFalse(viewModel.state.value.skipEnabled)
        advanceTimeBy(5_000)
        advanceUntilIdle()

        viewModel.effects.test {
            viewModel.onSkipForNow(at)
            advanceUntilIdle()

            assertEquals(listOf(at), lifecycleCoordinator.deferStage1Calls)
            assertEquals(ColdSyncEffect.NavigateToToday, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `COLD-008 skip branch should project deferred snapshot into state`() = runTest {
        val at = Instant.parse("2026-04-23T00:05:00Z")
        val snapshot = ColdSyncTransitionSnapshot(
            onboardingCompleted = true,
            stage1CompletedAt = null,
            stage1Deferred = true,
            deferredAt = at,
            stage2Scheduled = false,
            stage1DeferredScheduled = true,
        )
        sourceStatusFlow.value = listOf(sourceStatus(SourceType.GMAIL, SourceConnectionStatus.SYNCING))
        lifecycleCoordinator.deferStage1Result = BecalmResult.Success(snapshot)
        val viewModel = buildViewModel()
        viewModel.onScreenVisible()

        assertFalse(viewModel.state.value.skipEnabled)
        advanceTimeBy(5_000)
        advanceUntilIdle()
        viewModel.onSkipForNow(at)
        advanceUntilIdle()

        assertEquals(snapshot, viewModel.state.value.lastTransition)
        assertFalse(viewModel.state.value.transitioning)
    }

    @Test
    fun `COLD-008 skip branch failure projects visible transition error`() = runTest {
        lifecycleCoordinator.deferStage1Result = BecalmResult.Failure(BecalmError.Io("boom"))
        val viewModel = buildViewModel()
        viewModel.onScreenVisible()
        advanceTimeBy(5_000)
        advanceUntilIdle()

        viewModel.onSkipForNow(Instant.parse("2026-04-23T00:05:00Z"))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.transitionError)
        assertFalse(viewModel.state.value.transitioning)
        assertNull(viewModel.state.value.lastTransition)
    }

    @Test
    fun `COLD-006 onSkipForNow before enable is ignored`() = runTest {
        val viewModel = buildViewModel()
        viewModel.onScreenVisible()

        viewModel.onSkipForNow(Instant.parse("2026-04-23T00:02:00Z"))
        advanceUntilIdle()

        assertTrue(lifecycleCoordinator.deferStage1Calls.isEmpty())
        assertNull(viewModel.state.value.lastTransition)
    }

    private fun buildViewModel(): ColdSyncViewModel = ColdSyncViewModel(
        sourceStatusRepository = sourceStatusRepository,
        lifecycleCoordinator = lifecycleCoordinator,
        runtimeCoordinator = runtimeCoordinator,
        userPrefsStore = userPrefsStore,
        logger = logger,
    )

    private fun sourceStatus(
        sourceType: String,
        status: SourceConnectionStatus,
    ): SourceStatus = SourceStatus(
        sourceType = sourceType,
        status = status,
        lastSyncedAt = Instant.fromEpochMilliseconds(1_000),
        errorMessage = null,
    )

    private suspend fun awaitMappedState(
        scope: TestScope,
        viewModel: ColdSyncViewModel,
        turbine: ReceiveTurbine<ColdSyncUiState>,
    ): ColdSyncUiState {
        turbine.awaitItem()
        scope.advanceUntilIdle()
        return viewModel.state.value
    }

    private class FakeColdSyncLifecycleCoordinator : ColdSyncLifecycleCoordinator {
        var completeStage1Result: BecalmResult<ColdSyncTransitionSnapshot> = BecalmResult.Success(
            ColdSyncTransitionSnapshot(
                onboardingCompleted = true,
                stage1CompletedAt = Instant.parse("2026-04-23T00:00:00Z"),
                stage1Deferred = false,
                deferredAt = null,
                stage2Scheduled = true,
                stage1DeferredScheduled = false,
            ),
        )
        var deferStage1Result: BecalmResult<ColdSyncTransitionSnapshot> = BecalmResult.Success(
            ColdSyncTransitionSnapshot(
                onboardingCompleted = true,
                stage1CompletedAt = null,
                stage1Deferred = true,
                deferredAt = Instant.parse("2026-04-23T00:05:00Z"),
                stage2Scheduled = false,
                stage1DeferredScheduled = true,
            ),
        )
        val completeStage1Calls = mutableListOf<Instant>()
        val deferStage1Calls = mutableListOf<Instant>()

        override suspend fun completeStage1(now: Instant): BecalmResult<ColdSyncTransitionSnapshot> {
            completeStage1Calls += now
            return completeStage1Result
        }

        override suspend fun deferStage1(now: Instant): BecalmResult<ColdSyncTransitionSnapshot> {
            deferStage1Calls += now
            return deferStage1Result
        }
    }

    private class FakeColdSyncRuntimeCoordinator : ColdSyncRuntimeCoordinator {
        val userProfileReady = MutableStateFlow(false)
        val startStage1Calls = mutableListOf<Instant>()
        val startStage2Calls = mutableListOf<Instant>()
        var startStage1Result: BecalmResult<Unit> = BecalmResult.Success(Unit)

        override fun observeUserProfileReady() = userProfileReady

        override suspend fun startStage1(now: Instant): BecalmResult<Unit> {
            startStage1Calls += now
            return startStage1Result
        }

        override suspend fun startStage2(now: Instant): BecalmResult<Unit> {
            startStage2Calls += now
            return BecalmResult.Success(Unit)
        }
    }
}
