package com.becalm.android.worker

import com.becalm.android.core.di.ApplicationScope
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.di.MainDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.MeetingSpeakerPreviewDao
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.repository.PersonIndexDirtySources
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.isActive
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.sources.ContactsPermissionChecker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.datetime.Clock
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
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val meetingSpeakerPreviewDao: MeetingSpeakerPreviewDao,
    private val personIndexDao: PersonIndexDao,
    private val processingStatusRepository: ProcessingStatusRepository,
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
    private var sourceParticipantMirrorRetryScheduledForUser: String? = null
    private var staleLinkedSourceProjectionRepairScheduledForUser: String? = null
    private var staleRawSourceProjectionRepairScheduledForUser: String? = null
    private var staleLocalProcessingStateRepairScheduledForUser: String? = null
    private var contactsEnrichmentScheduledForUser: String? = null

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
        val currentUserId = currentUserId()
        if (currentUserId != null) {
            scheduleAuthenticatedRecurringWork(currentUserId)
        } else {
            scheduledPeriodicSources = emptySet()
            backendMailScheduled = false
            commonRecurringWorkScheduled = false
            sourceParticipantMirrorRetryScheduledForUser = null
            staleLinkedSourceProjectionRepairScheduledForUser = null
            staleRawSourceProjectionRepairScheduledForUser = null
            staleLocalProcessingStateRepairScheduledForUser = null
            contactsEnrichmentScheduledForUser = null
        }
        refreshPermissionManagedRegistrations(currentUserId)
    }

    private suspend fun scheduleAuthenticatedRecurringWork(userId: String) {
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
            workScheduler.scheduleProcessDoneSweep()
            commonRecurringWorkScheduled = true
        }
        enqueuePendingSourceParticipantMirrorsIfNeeded(userId)
        enqueueStaleLinkedSourceProjectionRepairIfNeeded(userId)
        enqueueStaleRawSourceProjectionRepairIfNeeded(userId)
        repairStaleLocalProcessingStateIfNeeded(userId)
    }

    private suspend fun enqueuePendingSourceParticipantMirrorsIfNeeded(userId: String) {
        if (sourceParticipantMirrorRetryScheduledForUser == userId) return
        val hasPendingMirror = personIndexDao.findPendingSourceParticipantMirrors(
            userId = userId,
            limit = 1,
        ).isNotEmpty()
        if (hasPendingMirror) {
            workScheduler.enqueueSourceParticipantMirrorRetry()
            sourceParticipantMirrorRetryScheduledForUser = userId
            logger.d(TAG, "pending source participant mirror retry scheduled")
        }
    }

    private suspend fun enqueueStaleLinkedSourceProjectionRepairIfNeeded(userId: String) {
        if (staleLinkedSourceProjectionRepairScheduledForUser == userId) return
        val staleRows = personIndexDao.findStaleLinkedSourceProjectionRows(
            userId = userId,
            limit = STALE_LINKED_SOURCE_REPAIR_LIMIT,
        )
        staleLinkedSourceProjectionRepairScheduledForUser = userId
        if (staleRows.isEmpty()) return
        val now = Clock.System.now()
        personIndexDao.upsertDirtySources(
            staleRows.map { row ->
                PersonIndexDirtySources.rawEvent(
                    userId = userId,
                    sourceType = row.sourceType,
                    sourceEventId = row.sourceEventId,
                    reason = "stale_linked_source_projection_repair",
                    now = now,
                )
            },
        )
        workScheduler.enqueuePersonInteractionIndex(initialDelaySeconds = 0L)
        logger.d(TAG, "stale linked source projection repair scheduled count=${staleRows.size}")
    }

    private suspend fun enqueueStaleRawSourceProjectionRepairIfNeeded(userId: String) {
        if (staleRawSourceProjectionRepairScheduledForUser == userId) return
        val staleRows = personIndexDao.findStaleRawSourceProjectionRows(
            userId = userId,
            limit = STALE_LINKED_SOURCE_REPAIR_LIMIT,
        )
        staleRawSourceProjectionRepairScheduledForUser = userId
        if (staleRows.isEmpty()) return
        val now = Clock.System.now()
        personIndexDao.upsertDirtySources(
            staleRows.map { row ->
                PersonIndexDirtySources.rawEvent(
                    userId = userId,
                    sourceType = row.sourceType,
                    sourceEventId = row.sourceEventId,
                    reason = "stale_raw_source_projection_repair",
                    now = now,
                )
            },
        )
        workScheduler.enqueuePersonInteractionIndex(initialDelaySeconds = 0L)
        logger.d(TAG, "stale raw source projection repair scheduled count=${staleRows.size}")
    }

    private suspend fun repairStaleLocalProcessingStateIfNeeded(userId: String) {
        if (staleLocalProcessingStateRepairScheduledForUser == userId) return
        staleLocalProcessingStateRepairScheduledForUser = userId
        runCatching {
            val now = Clock.System.now()
            val repairedDone = meetingSpeakerPreviewDao.markProcessingDoneForSyncedRawEvents(
                userId = userId,
                updatedAt = now,
            )
            val repairedFailed = meetingSpeakerPreviewDao.markProcessingFailedForFailedRawEvents(
                userId = userId,
                lastError = STALE_RAW_EVENT_FAILED_ERROR,
                updatedAt = now,
            )
            val ignoredParticipants = personIndexDao.ignoreNonReviewableEvidenceImportParticipants(userId)
            val deletedUnmatched =
                personIndexDao.deleteEvidenceImportUnmatchedWithoutReviewableParticipants(userId)
            val currentStates = processingStatusRepository.observeAll()
                .first()
                .associateBy { it.sourceType }
            LOCAL_AUDIO_SOURCES.forEach { sourceType ->
                val state = currentStates[sourceType] ?: return@forEach
                if (!state.phase.isActive) return@forEach
                val activeCount =
                    rawIngestionEventDao.countActiveProcessingForSource(userId, sourceType) +
                        meetingSpeakerPreviewDao.countProcessingForSource(userId, sourceType)
                if (activeCount > 0) return@forEach
                val failedCount =
                    rawIngestionEventDao.countFailedProcessingItemsForSource(userId, sourceType)
                if (failedCount > 0) {
                    processingStatusRepository.recordError(sourceType, itemCount = failedCount)
                } else {
                    processingStatusRepository.recordSynced(sourceType)
                }
            }
            if (repairedDone + repairedFailed + ignoredParticipants + deletedUnmatched > 0) {
                logger.d(
                    TAG,
                    "stale local processing state repaired done=$repairedDone failed=$repairedFailed " +
                        "ignoredParticipants=$ignoredParticipants deletedUnmatched=$deletedUnmatched",
                )
            }
        }.onFailure { error ->
            logger.w(TAG, "stale local processing state repair failed", error)
        }
    }

    private suspend fun refreshPermissionManagedRegistrations(currentUserId: String?) {
        if (currentUserId == null) {
            contentObserverBootstrap.stop()
            logger.d(TAG, "runtime sync disabled — no signed-in user")
            return
        }

        val voiceEnabled = userPrefsStore.observeSourceEnabled(SourceType.VOICE).first()
        val callRecordingEnabled = userPrefsStore.observeSourceEnabled(SourceType.CALL_RECORDING).first()
        val meetingEnabled = userPrefsStore.observeSourceEnabled(SourceType.MEETING).first()
        val voicePathSelected = userPrefsStore.observeRecordingFolderTreeUri(SourceType.VOICE).first().isNullOrBlank().not()
        val callRecordingPathSelected =
            userPrefsStore.observeRecordingFolderTreeUri(SourceType.CALL_RECORDING).first().isNullOrBlank().not()
        val meetingPathSelected = userPrefsStore.observeRecordingFolderTreeUri(SourceType.MEETING).first().isNullOrBlank().not()
        val audioGranted = mediaAudioPermissionChecker.isGranted()
        val canObserveRecordings =
            ((voiceEnabled && voicePathSelected) ||
                (callRecordingEnabled && callRecordingPathSelected) ||
                (meetingEnabled && meetingPathSelected)) && audioGranted
        if (canObserveRecordings) {
            contentObserverBootstrap.start()
            logger.d(TAG, "recordings realtime observer enabled")
        } else {
            contentObserverBootstrap.stop()
            logger.d(
                TAG,
                "recordings realtime observer disabled voiceEnabled=$voiceEnabled " +
                    "callRecordingEnabled=$callRecordingEnabled meetingEnabled=$meetingEnabled " +
                    "audioGranted=$audioGranted hasVoicePath=$voicePathSelected " +
                    "hasCallPath=$callRecordingPathSelected hasMeetingPath=$meetingPathSelected",
            )
        }

        if (contactsPermissionChecker.isGranted()) {
            workScheduler.scheduleEnrichmentSweep()
            if (contactsEnrichmentScheduledForUser != currentUserId) {
                workScheduler.enqueueEnrichment()
                contactsEnrichmentScheduledForUser = currentUserId
            }
            logger.d(TAG, "contacts enrichment periodic sweep enabled")
        } else {
            workScheduler.cancelEnrichmentSweep()
            contactsEnrichmentScheduledForUser = null
            logger.d(TAG, "contacts enrichment periodic sweep disabled")
        }
    }

    private suspend fun currentUserId(): String? =
        userPrefsStore.observeCurrentUserId().first()?.takeUnless { it.isBlank() }

    private companion object {
        private const val TAG = "AppRuntimeSync"
        private const val STARTUP_RUNTIME_DELAY_MS: Long = 5_000L
        private const val STALE_LINKED_SOURCE_REPAIR_LIMIT: Int = 500
        private const val STALE_RAW_EVENT_FAILED_ERROR: String = "raw_event_failed"
        private val LOCAL_AUDIO_SOURCES: Set<String> = setOf(
            SourceType.VOICE,
            SourceType.CALL_RECORDING,
            SourceType.MEETING,
        )
    }
}
