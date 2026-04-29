package com.becalm.android.worker

import com.becalm.android.core.di.ApplicationScope
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.di.MainDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.sources.ContactsPermissionChecker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Singleton
public class AppRuntimeSyncCoordinator @Inject constructor(
    @ApplicationScope
    private val scope: CoroutineScope,
    private val foregroundCatchUpScheduler: ForegroundCatchUpScheduler,
    private val contentObserverBootstrap: ContentObserverBootstrap,
    private val workScheduler: WorkScheduler,
    private val userPrefsStore: UserPrefsStore,
    private val runtimeSyncSourceResolver: RuntimeSyncSourceResolver,
    private val contactsPermissionChecker: ContactsPermissionChecker,
    private val mediaAudioPermissionChecker: MediaAudioPermissionChecker,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) {
    private var lifecycleRegistered: Boolean = false
    private var startupRefreshJob: Job? = null
    private var scheduledPeriodicSources: Set<String> = emptySet()
    private var backendMailScheduled: Boolean = false
    private var commonRecurringWorkScheduled: Boolean = false

    public fun start() {
        registerForegroundCatchUp()
        refresh()
    }

    /**
     * Cold-start path used after auth resolution. It registers lifecycle catch-up immediately
     * but defers WorkManager cleanup/scheduling and observer work until the first screen has
     * had time to draw.
     */
    public fun startAfterStartup() {
        registerForegroundCatchUp()
        if (startupRefreshJob?.isActive == true) {
            logger.d(TAG, "startup runtime sync already pending")
            return
        }
        startupRefreshJob = scope.launch(ioDispatcher) {
            delay(STARTUP_RUNTIME_DELAY_MS)
            runCatching {
                workScheduler.cleanupLegacyWorkNames()
            }.onFailure { error ->
                logger.e(TAG, "legacy work cleanup failed", error)
            }
            refreshNow()
        }
    }

    private fun registerForegroundCatchUp() {
        scope.launch(mainDispatcher) {
            if (!lifecycleRegistered) {
                foregroundCatchUpScheduler.start()
                lifecycleRegistered = true
            }
        }
    }

    /** Recomputes runtime registrations after same-process auth or permission changes. */
    public fun refresh() {
        scope.launch(ioDispatcher) {
            refreshNow()
        }
    }

    private suspend fun refreshNow() {
        if (hasSignedInUser()) {
            scheduleAuthenticatedRecurringWork()
        } else {
            scheduledPeriodicSources = emptySet()
            backendMailScheduled = false
            commonRecurringWorkScheduled = false
        }
        refreshPermissionManagedRegistrations()
    }

    private suspend fun scheduleAuthenticatedRecurringWork() {
        val periodicSources = runtimeSyncSourceResolver.periodicSources()
        val newlyEnabledPeriodicSources = periodicSources - scheduledPeriodicSources
        newlyEnabledPeriodicSources.forEach(workScheduler::enqueuePeriodic)
        if (newlyEnabledPeriodicSources.isEmpty() && scheduledPeriodicSources.isNotEmpty()) {
            logger.d(TAG, "periodic source work already scheduled for this process")
        }
        scheduledPeriodicSources = scheduledPeriodicSources + periodicSources

        val hasBackendMailSource = runtimeSyncSourceResolver.hasBackendMailSource()
        if (hasBackendMailSource && !backendMailScheduled) {
            workScheduler.scheduleBackendMailSync()
            backendMailScheduled = true
        } else if (hasBackendMailSource) {
            logger.d(TAG, "backend mail sync already scheduled for this process")
        }

        if (!commonRecurringWorkScheduled) {
            workScheduler.scheduleUploadRedundancy()
            workScheduler.scheduleRetentionSweep()
            workScheduler.scheduleOverdueSweep()
            commonRecurringWorkScheduled = true
        }
    }

    private suspend fun refreshPermissionManagedRegistrations() {
        if (!hasSignedInUser()) {
            contentObserverBootstrap.stop()
            logger.d(TAG, "runtime sync disabled — no signed-in user")
            return
        }

        val voiceEnabled = userPrefsStore.observeSourceEnabled(SourceType.VOICE).first()
        val recordingsTreeUri = userPrefsStore.observeRecordingFolderTreeUri().first()
        val audioGranted = mediaAudioPermissionChecker.isGranted()
        if (voiceEnabled && audioGranted && !recordingsTreeUri.isNullOrBlank()) {
            contentObserverBootstrap.start()
            logger.d(TAG, "voice realtime observer enabled")
        } else {
            contentObserverBootstrap.stop()
            logger.d(
                TAG,
                "voice realtime observer disabled voiceEnabled=$voiceEnabled audioGranted=$audioGranted hasTree=${!recordingsTreeUri.isNullOrBlank()}",
            )
        }

        if (contactsPermissionChecker.isGranted()) {
            workScheduler.scheduleEnrichmentSweep()
            logger.d(TAG, "contacts enrichment periodic sweep enabled")
        } else {
            workScheduler.cancelEnrichmentSweep()
            logger.d(TAG, "contacts enrichment periodic sweep disabled")
        }
    }

    private suspend fun hasSignedInUser(): Boolean =
        !userPrefsStore.observeCurrentUserId().first().isNullOrBlank()

    private companion object {
        private const val TAG = "AppRuntimeSync"
        private const val STARTUP_RUNTIME_DELAY_MS: Long = 5_000L
    }
}
