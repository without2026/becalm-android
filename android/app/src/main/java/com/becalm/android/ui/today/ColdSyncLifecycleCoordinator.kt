package com.becalm.android.ui.today

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.worker.WorkScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.datetime.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence + scheduling boundary for the cold-sync lifecycle contract.
 *
 * The view model owns the user-visible state/effects; this coordinator owns the
 * durable writes and WorkManager hand-off side effects so unit tests can fake the
 * whole contract without reaching into DataStore / WorkManager internals.
 */
public interface ColdSyncLifecycleCoordinator {

    /**
     * Persists Stage 1 completion (COLD-003 / COLD-008) and schedules Stage 2.
     */
    public suspend fun completeStage1(now: Instant): BecalmResult<ColdSyncTransitionSnapshot>

    /**
     * Persists the "[나중에 하기]" branch (COLD-006 / COLD-008) and hands Stage 1 off
     * to background work.
     */
    public suspend fun deferStage1(now: Instant): BecalmResult<ColdSyncTransitionSnapshot>
}

/**
 * Immutable outcome projected back to [ColdSyncViewModel] after a persistence/scheduling
 * transition succeeds.
 */
public data class ColdSyncTransitionSnapshot(
    val onboardingCompleted: Boolean,
    val stage1CompletedAt: Instant?,
    val stage1Deferred: Boolean,
    val deferredAt: Instant?,
    val stage2Scheduled: Boolean,
    val stage1DeferredScheduled: Boolean,
)

@Singleton
public class DefaultColdSyncLifecycleCoordinator @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    private val workScheduler: WorkScheduler,
) : ColdSyncLifecycleCoordinator {

    override suspend fun completeStage1(now: Instant): BecalmResult<ColdSyncTransitionSnapshot> =
        runCatching {
            userPrefsStore.setOnboardingCompleted(true)
            userPrefsStore.setColdSyncStage1CompletedAt(now.toEpochMilliseconds())
            userPrefsStore.setColdSyncStage1Deferred(false)
            userPrefsStore.setColdSyncDeferredAt(null)
            userPrefsStore.setColdSyncStage2Deferred(false)
            workScheduler.enqueueColdSyncStage2()
            ColdSyncTransitionSnapshot(
                onboardingCompleted = true,
                stage1CompletedAt = now,
                stage1Deferred = false,
                deferredAt = null,
                stage2Scheduled = true,
                stage1DeferredScheduled = false,
            )
        }.fold(
            onSuccess = { BecalmResult.Success(it) },
            onFailure = {
                BecalmResult.Failure(
                    com.becalm.android.core.result.BecalmError.Unknown(
                        IllegalStateException(it.message ?: "cold sync completion failed", it),
                    ),
                )
            },
        )

    override suspend fun deferStage1(now: Instant): BecalmResult<ColdSyncTransitionSnapshot> =
        runCatching {
            userPrefsStore.setOnboardingCompleted(true)
            userPrefsStore.setColdSyncStage1Deferred(true)
            userPrefsStore.setColdSyncDeferredAt(now.toEpochMilliseconds())
            userPrefsStore.setColdSyncStage2Deferred(false)
            workScheduler.enqueueDeferredColdSyncStage1()
            ColdSyncTransitionSnapshot(
                onboardingCompleted = true,
                stage1CompletedAt = null,
                stage1Deferred = true,
                deferredAt = now,
                stage2Scheduled = false,
                stage1DeferredScheduled = true,
            )
        }.fold(
            onSuccess = { BecalmResult.Success(it) },
            onFailure = {
                BecalmResult.Failure(
                    com.becalm.android.core.result.BecalmError.Unknown(
                        IllegalStateException(it.message ?: "cold sync deferral failed", it),
                    ),
                )
            },
        )
}

@Module
@InstallIn(SingletonComponent::class)
public abstract class ColdSyncLifecycleCoordinatorModule {
    @Binds
    @Singleton
    public abstract fun bindColdSyncLifecycleCoordinator(
        impl: DefaultColdSyncLifecycleCoordinator,
    ): ColdSyncLifecycleCoordinator
}
