package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.OnboardingActivationPreviewRow
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.worker.SourceRelationRefreshCoordinator
import com.becalm.android.worker.SourceRelationRefreshPlan
import com.becalm.android.worker.WorkScheduler
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

public data class OnboardingActivationPreview(
    val commitmentId: String,
    val personId: String?,
    val personName: String?,
    val participantId: String?,
    val participantName: String?,
    val participantEmail: String?,
    val participantPhone: String?,
    val contactMatched: Boolean,
    val title: String,
    val itemType: String,
    val direction: String?,
    val scheduleStatus: String?,
    val decisionStatus: String?,
    val dueAt: Instant?,
    val dueHint: String?,
    val sourceType: String,
    val sourceTitle: String?,
    val sourceEventOccurredAt: Instant,
    val confidence: Double,
)

public sealed interface OnboardingActivationPreviewResult {
    public data class Ready(val previews: List<OnboardingActivationPreview>) : OnboardingActivationPreviewResult
    public data object Empty : OnboardingActivationPreviewResult
    public data class Pending(val progress: OnboardingActivationProgress? = null) : OnboardingActivationPreviewResult
    public data class Failed(val retryable: Boolean) : OnboardingActivationPreviewResult
}

public data class OnboardingActivationProgress(
    val stage: String?,
    val progress: Double?,
    val message: String,
)

public interface OnboardingActivationPreviewRepository {
    public suspend fun syncGmailAndLoadPreview(
        userId: String,
        onProgress: suspend (OnboardingActivationProgress) -> Unit = {},
    ): OnboardingActivationPreviewResult
}

@Singleton
public class OnboardingActivationPreviewRepositoryImpl @Inject constructor(
    private val apiProvider: Provider<RailwayApi>,
    private val rawIngestionRepository: RawIngestionRepository,
    private val commitmentRepository: CommitmentRepository,
    private val sourceEventParticipantRepository: SourceEventParticipantRepository,
    private val commitmentParticipantRepository: CommitmentParticipantRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val commitmentDao: CommitmentDao,
    private val workScheduler: WorkScheduler,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : OnboardingActivationPreviewRepository {

    override suspend fun syncGmailAndLoadPreview(
        userId: String,
        onProgress: suspend (OnboardingActivationProgress) -> Unit,
    ): OnboardingActivationPreviewResult =
        withContext(ioDispatcher) {
            val normalizedUserId = userId.trim()
            if (normalizedUserId.isEmpty()) return@withContext OnboardingActivationPreviewResult.Failed(retryable = false)

            loadLocalPreview(normalizedUserId)?.let { previews ->
                return@withContext OnboardingActivationPreviewResult.Ready(previews)
            }

            sourceStatusRepository.recordSyncStart(SourceType.GMAIL)
            val initialProgress = OnboardingActivationProgress(
                stage = "queued",
                progress = 0.08,
                message = "Gmail 연결을 확인하고 있습니다",
            )
            onProgress(initialProgress)
            processingStatusRepository.recordScanning(SourceType.GMAIL, initialProgress.message)

            val api = apiProvider.get()
            val response = runCatching {
                api.syncMailSource(provider = SourceType.GMAIL, mode = ACTIVATION_PREVIEW_MODE)
            }.getOrElse { error ->
                logger.w(TAG, "gmail activation preview sync request failed type=${error.javaClass.simpleName}")
                sourceStatusRepository.recordSyncError(SourceType.GMAIL, "Gmail sync request failed", Clock.System.now())
                processingStatusRepository.recordError(SourceType.GMAIL, "Gmail sync request failed")
                return@withContext OnboardingActivationPreviewResult.Failed(retryable = true)
            }

            if (!response.isSuccessful) {
                val retryable = response.code() == 429 || response.code() in 500..599
                sourceStatusRepository.recordSyncError(SourceType.GMAIL, "HTTP ${response.code()}", Clock.System.now())
                processingStatusRepository.recordError(SourceType.GMAIL, "HTTP ${response.code()}")
                return@withContext OnboardingActivationPreviewResult.Failed(retryable = retryable)
            }

            val body = response.body()
                ?: return@withContext OnboardingActivationPreviewResult.Failed(retryable = true)

            when (
                val pollResult = SourceSyncJobPoller(
                    api = api,
                    logger = logger,
                    maxPollAttempts = FOREGROUND_POLL_ATTEMPTS,
                    delayMillis = { waitMs -> delay(waitMs.coerceAtMost(FOREGROUND_MAX_WAIT_MS)) },
                ).awaitTerminal(
                    SourceType.GMAIL,
                    body.toSourceSyncJobSnapshot(),
                    onSnapshot = { snapshot ->
                        val progress = snapshot.toActivationProgress()
                        onProgress(progress)
                        processingStatusRepository.recordScanning(SourceType.GMAIL, progress.message)
                    },
                )
            ) {
	                is SourceSyncJobPollResult.Completed -> {
	                    sourceStatusRepository.recordSyncSuccess(SourceType.GMAIL, Clock.System.now())
	                    processingStatusRepository.recordSynced(
	                        sourceType = SourceType.GMAIL,
	                        itemCount = pollResult.synced,
	                        message = "Gmail 확인 완료",
	                    )
	                    workScheduler.enqueueSourceRelationRefresh(
	                        SourceType.GMAIL,
	                        initialDelaySeconds = FULL_SYNC_FOLLOW_UP_REFRESH_DELAY_SECONDS,
	                    )
	                }
	                is SourceSyncJobPollResult.Pending -> {
	                    val progress = OnboardingActivationProgress(
	                        stage = pollResult.stage,
	                        progress = pollResult.progress,
	                        message = pollResult.stage.activationProgressMessage(pollResult.message),
	                    )
	                    processingStatusRepository.recordScanning(SourceType.GMAIL, progress.message)
	                    workScheduler.enqueueSourceRelationRefresh(
	                        SourceType.GMAIL,
	                        initialDelaySeconds = FULL_SYNC_FOLLOW_UP_REFRESH_DELAY_SECONDS,
	                    )
	                    return@withContext OnboardingActivationPreviewResult.Pending(
	                        progress,
	                    )
                }
                is SourceSyncJobPollResult.Failed -> {
                    sourceStatusRepository.recordSyncError(SourceType.GMAIL, pollResult.message, Clock.System.now())
                    processingStatusRepository.recordError(SourceType.GMAIL, pollResult.message)
                    return@withContext OnboardingActivationPreviewResult.Failed(retryable = pollResult.retryable)
                }
            }

            when (
                val refresh = SourceRelationRefreshCoordinator(
                    rawIngestionRepository = rawIngestionRepository,
                    commitmentRepository = commitmentRepository,
                    sourceEventParticipantRepository = sourceEventParticipantRepository,
                    commitmentParticipantRepository = commitmentParticipantRepository,
                    workScheduler = workScheduler,
                    logger = logger,
                ).refresh(
                    userId = normalizedUserId,
                    plan = SourceRelationRefreshPlan(
                        sourceType = SourceType.GMAIL,
                        rawSourceType = SourceType.GMAIL,
                    ),
                )
            ) {
                is com.becalm.android.core.result.BecalmResult.Success -> Unit
                is com.becalm.android.core.result.BecalmResult.Failure -> {
                    logger.w(TAG, "gmail activation preview relation refresh failed")
                    return@withContext OnboardingActivationPreviewResult.Failed(retryable = true)
                }
            }

            loadLocalPreview(normalizedUserId)?.let { previews ->
                OnboardingActivationPreviewResult.Ready(previews)
            } ?: OnboardingActivationPreviewResult.Empty
        }

    private suspend fun loadLocalPreview(userId: String): List<OnboardingActivationPreview>? =
        commitmentDao.findOnboardingActivationPreview(
            userId = userId,
            sourceType = SourceType.GMAIL,
            minConfidence = MIN_PREVIEW_CONFIDENCE,
            limit = PREVIEW_LIMIT,
        ).map { row ->
            row.toActivationPreview()
        }.takeIf { it.isNotEmpty() }

    private companion object {
        private const val TAG = "OnboardingActivationPreview"
        private const val ACTIVATION_PREVIEW_MODE = "activation_preview"
        private const val MIN_PREVIEW_CONFIDENCE = 0.64
	        private const val PREVIEW_LIMIT = 2
	        private const val FOREGROUND_POLL_ATTEMPTS = 10
	        private const val FOREGROUND_MAX_WAIT_MS = 3_000L
	        private const val FULL_SYNC_FOLLOW_UP_REFRESH_DELAY_SECONDS: Long = 45L
	    }
	}

private fun SourceSyncJobSnapshot.toActivationProgress(): OnboardingActivationProgress =
    OnboardingActivationProgress(
        stage = stage,
        progress = progress,
        message = stage.activationProgressMessage(message),
    )

private fun String?.activationProgressMessage(fallback: String? = null): String =
    when (this) {
        "queued" -> "Gmail 연결을 확인하고 있습니다"
        "fetching" -> "최근 메일을 가져오고 있습니다"
        "extracting" -> "메일 속 약속 후보를 확인하고 있습니다"
        "mirroring" -> "화면에 보여줄 자료를 준비하고 있습니다"
        "complete" -> "Gmail 확인을 마쳤습니다"
        else -> fallback ?: "Gmail 자료를 정리하고 있습니다"
    }

private fun OnboardingActivationPreviewRow.toActivationPreview(): OnboardingActivationPreview =
    OnboardingActivationPreview(
        commitmentId = commitmentId,
        personId = personId,
        personName = personName,
        participantId = participantId,
        participantName = participantName,
        participantEmail = participantEmail,
        participantPhone = participantPhone,
        contactMatched = contactMatched,
        title = title,
        itemType = itemType,
        direction = direction,
        scheduleStatus = scheduleStatus,
        decisionStatus = decisionStatus,
        dueAt = dueAt,
        dueHint = dueHint,
        sourceType = sourceType,
        sourceTitle = sourceTitle,
        sourceEventOccurredAt = sourceEventOccurredAt,
        confidence = confidence,
    )
