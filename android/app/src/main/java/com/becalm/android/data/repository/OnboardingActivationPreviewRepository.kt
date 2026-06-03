package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.OnboardingActivationPreviewRow
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.CommitmentDto
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
    private val personActionRepository: PersonActionRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val commitmentDao: CommitmentDao,
    private val userCorrectionRepository: UserCorrectionRepository,
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
                refreshActivationActions(normalizedUserId)
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

            val initialSnapshot = body.toSourceSyncJobSnapshot()
            if (
                !initialSnapshot.jobId.isNullOrBlank() &&
                initialSnapshot.syncMode != ACTIVATION_PREVIEW_MODE
            ) {
                loadRemotePreview(api)?.let { previews ->
                    refreshActivationActions(normalizedUserId)
                    return@withContext OnboardingActivationPreviewResult.Ready(previews)
                }
                val progress = OnboardingActivationProgress(
                    stage = initialSnapshot.stage ?: "background_sync",
                    progress = initialSnapshot.progress ?: 0.35,
                    message = "Gmail 자료를 백그라운드에서 정리하고 있습니다",
                )
                onProgress(progress)
                processingStatusRepository.recordScanning(SourceType.GMAIL, progress.message)
                workScheduler.enqueueSourceRelationRefresh(
                    SourceType.GMAIL,
                    initialDelaySeconds = FULL_SYNC_FOLLOW_UP_REFRESH_DELAY_SECONDS,
                    resetBeforeRefresh = true,
                )
                return@withContext OnboardingActivationPreviewResult.Pending(progress)
            }

            when (
                val pollResult = SourceSyncJobPoller(
                    api = api,
                    logger = logger,
                    maxPollAttempts = FOREGROUND_POLL_ATTEMPTS,
                    delayMillis = { waitMs -> delay(waitMs.coerceAtMost(FOREGROUND_MAX_WAIT_MS)) },
                ).awaitTerminal(
                    SourceType.GMAIL,
                    initialSnapshot,
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
	                        resetBeforeRefresh = true,
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
	                        resetBeforeRefresh = true,
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
                    userCorrectionRepository = userCorrectionRepository,
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
                is BecalmResult.Success -> Unit
                is BecalmResult.Failure -> {
                    logger.w(TAG, "gmail activation preview relation refresh failed")
                    return@withContext OnboardingActivationPreviewResult.Failed(retryable = true)
                }
            }

            val actionProgress = OnboardingActivationProgress(
                stage = "action_scan",
                progress = 0.92,
                message = "찾은 약속을 다음 행동으로 정리하고 있습니다",
            )
            onProgress(actionProgress)
            processingStatusRepository.recordScanning(SourceType.GMAIL, actionProgress.message)
            refreshActivationActions(normalizedUserId)

            loadLocalPreview(normalizedUserId)?.let { previews ->
                OnboardingActivationPreviewResult.Ready(previews)
            }
                ?: loadRemotePreview(api)?.let { previews ->
                    OnboardingActivationPreviewResult.Ready(previews)
                }
                ?: OnboardingActivationPreviewResult.Empty
        }

    private suspend fun refreshActivationActions(userId: String) {
        when (val result = personActionRepository.refresh(userId = userId, surface = null)) {
            is BecalmResult.Success -> Unit
            is BecalmResult.Failure -> logger.w(TAG, "gmail activation action refresh failed: ${result.error}")
        }
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

    private suspend fun loadRemotePreview(api: RailwayApi): List<OnboardingActivationPreview>? {
        val response = runCatching {
            api.getCommitments(
                limit = REMOTE_PREVIEW_FETCH_LIMIT,
                sourceType = SourceType.GMAIL,
                includeUnresolvedCounterparty = true,
            )
        }.getOrElse { error ->
            logger.w(TAG, "gmail activation preview fallback fetch failed type=${error.javaClass.simpleName}")
            return null
        }
        if (!response.isSuccessful) {
            logger.w(TAG, "gmail activation preview fallback fetch HTTP ${response.code()}")
            return null
        }
        return response.body()
            ?.data
            .orEmpty()
            .asSequence()
            .filter { it.sourceType == SourceType.GMAIL }
            .filter { it.deletedAt == null }
            .filter { it.itemType in PREVIEW_ITEM_TYPES }
            .filter { it.actionState != "completed" }
            .filter { it.confidence >= MIN_PREVIEW_CONFIDENCE }
            .sortedWith(
                compareByDescending<CommitmentDto> { it.sourceEventOccurredAt }
                    .thenByDescending { it.createdAt },
            )
            .take(PREVIEW_LIMIT)
            .map { it.toActivationPreview() }
            .toList()
            .takeIf { it.isNotEmpty() }
    }

    private companion object {
        private const val TAG = "OnboardingActivationPreview"
        private const val ACTIVATION_PREVIEW_MODE = "activation_preview"
        private const val MIN_PREVIEW_CONFIDENCE = 0.64
        private const val PREVIEW_LIMIT = 2
        private const val REMOTE_PREVIEW_FETCH_LIMIT = 20
        private val PREVIEW_ITEM_TYPES = setOf("action", "schedule", "decision")
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
        "retry_waiting" -> "서버 사용량 제한으로 잠시 대기 중입니다. 곧 자동으로 다시 확인합니다"
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

private fun CommitmentDto.toActivationPreview(): OnboardingActivationPreview =
    OnboardingActivationPreview(
        commitmentId = id,
        personId = null,
        personName = null,
        participantId = null,
        participantName = null,
        participantEmail = null,
        participantPhone = null,
        contactMatched = false,
        title = title,
        itemType = itemType,
        direction = direction,
        scheduleStatus = scheduleStatus,
        decisionStatus = decisionStatus,
        dueAt = dueAt,
        dueHint = dueHint,
        sourceType = sourceType,
        sourceTitle = sourceEventTitle,
        sourceEventOccurredAt = sourceEventOccurredAt,
        confidence = confidence,
    )
