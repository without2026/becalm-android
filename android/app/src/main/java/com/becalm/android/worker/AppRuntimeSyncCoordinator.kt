package com.becalm.android.worker

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.di.MainDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.sources.ContactsPermissionChecker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Singleton
public class AppRuntimeSyncCoordinator @Inject constructor(
    private val scope: CoroutineScope,
    private val foregroundCatchUpScheduler: ForegroundCatchUpScheduler,
    private val contentObserverBootstrap: ContentObserverBootstrap,
    private val workScheduler: WorkScheduler,
    private val userPrefsStore: UserPrefsStore,
    private val contactsPermissionChecker: ContactsPermissionChecker,
    private val mediaAudioPermissionChecker: MediaAudioPermissionChecker,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) {
    private var lifecycleRegistered: Boolean = false

    public fun start() {
        scope.launch(mainDispatcher) {
            if (!lifecycleRegistered) {
                foregroundCatchUpScheduler.start()
                lifecycleRegistered = true
            }
        }
        refresh()
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
        }
        refreshPermissionManagedRegistrations()
    }

    private fun scheduleAuthenticatedRecurringWork() {
        PERIODIC_SOURCES.forEach(workScheduler::enqueuePeriodic)
        workScheduler.scheduleUploadRedundancy()
        workScheduler.scheduleRetentionSweep()
        workScheduler.scheduleOverdueSweep()
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
        private val PERIODIC_SOURCES: List<String> = listOf(
            SourceType.NAVER_IMAP,
            SourceType.DAUM_IMAP,
            SourceType.GOOGLE_CALENDAR,
            SourceType.OUTLOOK_CALENDAR,
        )
    }
}
