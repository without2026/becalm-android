package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.redact
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.MeetingSpeakerPreviewStatus
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.RawIngestionSyncStatus
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.worker.WorkScheduler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

@Singleton
public class AudioProcessingConfirmationRepository @Inject constructor(
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val userPrefsStore: UserPrefsStore,
    private val workScheduler: WorkScheduler,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    public suspend fun confirm(rawEventId: String): BecalmResult<Unit> =
        withContext(ioDispatcher) {
            try {
                val userId = userPrefsStore.observeCurrentUserId().first()
                    ?: return@withContext BecalmResult.Failure(BecalmError.Unauthorized)
                val event = rawIngestionEventDao.findById(rawEventId, userId)
                    ?: return@withContext BecalmResult.Failure(BecalmError.NotFound("raw_ingestion_event"))
                if (!event.sourceType.isConfirmableAudioSource()) {
                    return@withContext BecalmResult.Failure(BecalmError.Validation("source_type", "not confirmable audio"))
                }
                val sourceRef = event.sourceRef?.takeIf { it.isNotBlank() }
                    ?: return@withContext BecalmResult.Failure(BecalmError.Validation("source_ref", "audio source missing"))
                val hasConsent = userPrefsStore.observeThirdPartyProvisionConsent().first()
                val nextStatus = event.nextConfirmedStatus(hasConsent)
                val now = Clock.System.now()
                val updated = rawIngestionEventDao.markDetectedAudioProcessingConfirmed(
                    id = event.id,
                    userId = userId,
                    status = nextStatus,
                    now = now,
                )
                if (updated == 0) {
                    logger.d(TAG, "audio confirmation ignored id=${redact(event.id)} status=${event.syncStatus}")
                    return@withContext BecalmResult.Success(Unit)
                }
                if (!hasConsent) {
                    processingStatusRepository.recordBlocked(event.sourceType, "음성 처리 동의가 필요합니다")
                    return@withContext BecalmResult.Success(Unit)
                }
                when (event.sourceType) {
                    SourceType.CALL_RECORDING,
                    SourceType.MEETING,
                    -> workScheduler.enqueueMeetingSpeakerPreview(event.id, sourceRef)
                    SourceType.VOICE -> workScheduler.enqueueVoiceUpload(event.id, sourceRef)
                }
                val remaining = rawIngestionEventDao.countDetectedAudioConfirmationsForSource(userId, event.sourceType)
                if (remaining > 0) {
                    processingStatusRepository.recordAwaitingConfirmation(
                        sourceType = event.sourceType,
                        itemCount = remaining,
                        message = ProcessingStatusMessages.AUDIO_CONFIRMATION_REQUIRED,
                    )
                } else {
                    processingStatusRepository.recordUploading(event.sourceType, "선택한 파일을 정리합니다")
                }
                BecalmResult.Success(Unit)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                BecalmResult.Failure(BecalmError.Unknown(t))
            }
        }

    public suspend fun skip(rawEventId: String): BecalmResult<Unit> =
        withContext(ioDispatcher) {
            try {
                val userId = userPrefsStore.observeCurrentUserId().first()
                    ?: return@withContext BecalmResult.Failure(BecalmError.Unauthorized)
                val event = rawIngestionEventDao.findById(rawEventId, userId)
                    ?: return@withContext BecalmResult.Failure(BecalmError.NotFound("raw_ingestion_event"))
                if (!event.sourceType.isConfirmableAudioSource()) {
                    return@withContext BecalmResult.Failure(BecalmError.Validation("source_type", "not confirmable audio"))
                }
                val now = Clock.System.now()
                val updated = rawIngestionEventDao.markDetectedAudioSkippedByUser(
                    id = event.id,
                    userId = userId,
                    now = now,
                )
                if (updated == 0) {
                    return@withContext BecalmResult.Success(Unit)
                }
                val remaining = rawIngestionEventDao.countDetectedAudioConfirmationsForSource(userId, event.sourceType)
                if (remaining > 0) {
                    processingStatusRepository.recordAwaitingConfirmation(
                        sourceType = event.sourceType,
                        itemCount = remaining,
                        message = ProcessingStatusMessages.AUDIO_CONFIRMATION_REQUIRED,
                    )
                } else {
                    processingStatusRepository.recordNoNewItems(event.sourceType, "건너뛴 파일은 정리하지 않습니다")
                }
                BecalmResult.Success(Unit)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                BecalmResult.Failure(BecalmError.Unknown(t))
            }
        }

    private fun RawIngestionEventEntity.nextConfirmedStatus(hasConsent: Boolean): String =
        when {
            !hasConsent -> RawIngestionSyncStatus.AWAITING_CONSENT
            sourceType == SourceType.CALL_RECORDING || sourceType == SourceType.MEETING ->
                MeetingSpeakerPreviewStatus.PENDING
            else -> RawIngestionSyncStatus.PENDING
        }

    private fun String.isConfirmableAudioSource(): Boolean =
        this == SourceType.VOICE || this == SourceType.CALL_RECORDING || this == SourceType.MEETING

    private companion object {
        private const val TAG = "AudioProcessingConfirm"
    }
}
