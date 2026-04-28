package com.becalm.android.worker

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.di.MainDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.BeCalmDatabaseProvider
import com.becalm.android.data.local.secure.ImapCredentialStoreMigrator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Auth-gated startup/runtime wiring. This must not be started from [android.app.Application.onCreate].
 *
 * WorkManager may start the process directly to deliver a [android.app.job.JobService] callback.
 * Deferring DataStore migrations, Room warm-open, and WorkManager enqueue/cancel until a signed-in
 * UI/auth owner resolves [startForUser] prevents `SystemJobService.onStartJob` and process-startup
 * ANRs under cold-start I/O pressure.
 */
@Singleton
public class AuthenticatedRuntimeBootstrap @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    private val imapCredentialStoreMigrator: ImapCredentialStoreMigrator,
    private val syncCursorStore: SyncCursorStore,
    private val databaseProvider: BeCalmDatabaseProvider,
    private val appRuntimeSyncCoordinator: AppRuntimeSyncCoordinator,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) {
    private val bootstrapLock = Any()
    private var bootstrappedUserId: String? = null
    private var inFlightUserId: String? = null

    /**
     * Backward-compatible entry point for non-UI callers that only know persisted auth state.
     * UI startup should prefer [startForUser] after it has already resolved a signed-in user.
     */
    public suspend fun startIfSignedIn() {
        val persistedUserId = withContext(ioDispatcher) {
            runCatching {
                userPrefsStore.observeCurrentUserId().first()
            }.getOrElse { error ->
                logger.e(TAG, "failed to read persisted current user", error)
                null
            }
        }

        if (persistedUserId.isNullOrBlank()) {
            logger.d(TAG, "pre-auth startup — runtime sync deferred")
            return
        }

        startForUser(persistedUserId)
    }

    public suspend fun startForUser(userId: String) {
        if (userId.isBlank()) {
            logger.d(TAG, "blank user id — runtime sync deferred")
            return
        }
        if (!markInFlight(userId)) {
            logger.d(TAG, "runtime bootstrap already started for current user")
            return
        }

        try {
            withContext(ioDispatcher) {
                runStep("IMAP credential migration") {
                    imapCredentialStoreMigrator.migrateIfNeeded()
                }
                runStep("Outlook mail cursor v2 migration") {
                    syncCursorStore.runOutlookMailCursorMigrationV2()
                }
                runStep("IMAP cursor v2 migration") {
                    syncCursorStore.runImapCursorMigrationV2()
                }
                runStep("database warm-open") {
                    databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash(userId))
                }
            }

            withContext(mainDispatcher) {
                runStep("deferred runtime sync start") {
                    appRuntimeSyncCoordinator.startAfterStartup()
                }
            }
            markBootstrapped(userId)
        } finally {
            clearInFlight(userId)
        }
    }

    private fun markInFlight(userId: String): Boolean =
        synchronized(bootstrapLock) {
            if (bootstrappedUserId == userId || inFlightUserId == userId) {
                false
            } else {
                inFlightUserId = userId
                true
            }
        }

    private fun markBootstrapped(userId: String) {
        synchronized(bootstrapLock) {
            bootstrappedUserId = userId
        }
    }

    private fun clearInFlight(userId: String) {
        synchronized(bootstrapLock) {
            if (inFlightUserId == userId) {
                inFlightUserId = null
            }
        }
    }

    private suspend fun runStep(name: String, block: suspend () -> Unit) {
        runCatching { block() }
            .onFailure { logger.e(TAG, "$name failed", it) }
    }

    private companion object {
        private const val TAG: String = "BecalmApplication"
    }
}
