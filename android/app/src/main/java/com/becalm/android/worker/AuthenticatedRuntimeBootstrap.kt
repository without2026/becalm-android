package com.becalm.android.worker

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.di.MainDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.BeCalmDatabaseProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Auth-gated startup/runtime wiring that must not run on Application.onCreate's main thread.
 *
 * WorkManager may start the process directly to deliver a [android.app.job.JobService] callback.
 * Keeping DataStore reads, Room open, and WorkManager enqueue/cancel off the main thread prevents
 * `SystemJobService.onStartJob` ANRs under cold-start I/O pressure.
 */
@Singleton
public class AuthenticatedRuntimeBootstrap @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    private val databaseProvider: BeCalmDatabaseProvider,
    private val workScheduler: WorkScheduler,
    private val appRuntimeSyncCoordinator: AppRuntimeSyncCoordinator,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) {

    public suspend fun startIfSignedIn() {
        withContext(ioDispatcher) {
            val persistedUserId = runCatching {
                userPrefsStore.observeCurrentUserId().first()
            }.getOrElse { error ->
                logger.e(TAG, "failed to read persisted current user", error)
                null
            }

            if (persistedUserId.isNullOrBlank()) {
                logger.d(TAG, "pre-auth startup — runtime sync deferred")
                return@withContext
            }

            runCatching {
                databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash(persistedUserId))
            }.onFailure { logger.e(TAG, "database warm-open failed", it) }

            workScheduler.cleanupLegacyWorkNames()
            withContext(mainDispatcher) {
                appRuntimeSyncCoordinator.start()
            }
        }
    }

    private companion object {
        private const val TAG: String = "BecalmApplication"
    }
}
