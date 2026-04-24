package com.becalm.android.unit.ui.today

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.ui.today.ColdSyncTransitionSnapshot
import com.becalm.android.ui.today.DefaultColdSyncLifecycleCoordinator
import com.becalm.android.worker.WorkScheduler
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ColdSyncLifecycleCoordinatorSpecTest {

    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)

    @Test
    fun `COLD-004 completeStage1 persists onboarding stage1 completion and schedules stage2`() = runTest {
        val now = Instant.parse("2026-04-23T01:00:00Z")
        val coordinator = DefaultColdSyncLifecycleCoordinator(
            userPrefsStore = userPrefsStore,
            workScheduler = workScheduler,
        )

        val result = coordinator.completeStage1(now)

        val snapshot = result.expectSuccess()
        assertEquals(
            ColdSyncTransitionSnapshot(
                onboardingCompleted = true,
                stage1CompletedAt = now,
                stage1Deferred = false,
                deferredAt = null,
                stage2Scheduled = true,
                stage1DeferredScheduled = false,
            ),
            snapshot,
        )
        coVerify(exactly = 1) { userPrefsStore.setOnboardingCompleted(true) }
        coVerify(exactly = 1) { userPrefsStore.setColdSyncStage1CompletedAt(now.toEpochMilliseconds()) }
        coVerify(exactly = 1) { userPrefsStore.setColdSyncStage1Deferred(false) }
        coVerify(exactly = 1) { userPrefsStore.setColdSyncDeferredAt(null) }
        verify(exactly = 1) { workScheduler.enqueueColdSyncStage2() }
    }

    @Test
    fun `COLD-008 deferStage1 marks onboarding completed records deferral and schedules deferred stage1`() = runTest {
        val now = Instant.parse("2026-04-23T01:05:00Z")
        val coordinator = DefaultColdSyncLifecycleCoordinator(
            userPrefsStore = userPrefsStore,
            workScheduler = workScheduler,
        )

        val result = coordinator.deferStage1(now)

        val snapshot = result.expectSuccess()
        assertEquals(
            ColdSyncTransitionSnapshot(
                onboardingCompleted = true,
                stage1CompletedAt = null,
                stage1Deferred = true,
                deferredAt = now,
                stage2Scheduled = false,
                stage1DeferredScheduled = true,
            ),
            snapshot,
        )
        coVerify(exactly = 1) { userPrefsStore.setOnboardingCompleted(true) }
        coVerify(exactly = 1) { userPrefsStore.setColdSyncStage1Deferred(true) }
        coVerify(exactly = 1) { userPrefsStore.setColdSyncDeferredAt(now.toEpochMilliseconds()) }
        verify(exactly = 1) { workScheduler.enqueueDeferredColdSyncStage1() }
    }

    private fun BecalmResult<ColdSyncTransitionSnapshot>.expectSuccess(): ColdSyncTransitionSnapshot = when (this) {
        is BecalmResult.Success -> value
        is BecalmResult.Failure -> throw AssertionError("Expected success, got $error")
    }
}
