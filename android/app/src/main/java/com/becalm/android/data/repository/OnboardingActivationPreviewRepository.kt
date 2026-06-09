package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.OnboardingActivationPreviewRow
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.CommitmentDto
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.worker.WorkScheduler
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    val actionItemId: String? = null,
    val actionKind: String? = null,
    val reasonCodes: List<String> = emptyList(),
)

public data class OnboardingActivationScanSummary(
    val gmailCount: Int? = null,
    val calendarCount: Int? = null,
)

public sealed interface OnboardingActivationPreviewResult {
    public data class Ready(
        val previews: List<OnboardingActivationPreview>,
        val scanSummary: OnboardingActivationScanSummary = OnboardingActivationScanSummary(),
    ) : OnboardingActivationPreviewResult

    public data object Empty : OnboardingActivationPreviewResult

    public data class Pending(
        val progress: OnboardingActivationProgress? = null,
        val scanSummary: OnboardingActivationScanSummary = OnboardingActivationScanSummary(),
    ) : OnboardingActivationPreviewResult

    public data class Failed(val retryable: Boolean) : OnboardingActivationPreviewResult
}

public data class OnboardingActivationProgress(
    val stage: String?,
    val progress: Double?,
    val message: String,
)

public interface OnboardingActivationPreviewRepository {
    public suspend fun syncConnectedSourcesAndLoadPreview(
        userId: String,
        includeGmail: Boolean,
        includeGoogleCalendar: Boolean,
        onProgress: suspend (OnboardingActivationProgress) -> Unit = {},
    ): OnboardingActivationPreviewResult

    public suspend fun refreshCachedPreview(
        userId: String,
        includeGmail: Boolean = true,
        includeGoogleCalendar: Boolean = true,
    ): OnboardingActivationPreviewResult

    public suspend fun loadCachedPreview(
        userId: String,
        includeGmail: Boolean = true,
        includeGoogleCalendar: Boolean = true,
    ): OnboardingActivationPreviewResult

    public suspend fun acceptPreviewAction(
        userId: String,
        actionItemId: String,
    ): BecalmResult<PersonActionMutationSyncStats>

    public suspend fun dismissPreviewAction(
        userId: String,
        actionItemId: String,
    ): BecalmResult<PersonActionMutationSyncStats>
}

@Singleton
public class OnboardingActivationPreviewRepositoryImpl @Inject constructor(
    private val apiProvider: Provider<RailwayApi>,
    private val personActionRepository: PersonActionRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val commitmentDao: CommitmentDao,
    private val calendarEventRepository: CalendarEventRepository,
    private val workScheduler: WorkScheduler,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : OnboardingActivationPreviewRepository {

    override suspend fun syncConnectedSourcesAndLoadPreview(
        userId: String,
        includeGmail: Boolean,
        includeGoogleCalendar: Boolean,
        onProgress: suspend (OnboardingActivationProgress) -> Unit,
    ): OnboardingActivationPreviewResult =
        withContext(ioDispatcher) {
            val normalizedUserId = userId.trim()
            if (normalizedUserId.isEmpty()) return@withContext OnboardingActivationPreviewResult.Failed(retryable = false)

            val sourceTypes = buildList {
                if (includeGmail) add(SourceType.GMAIL)
                if (includeGoogleCalendar) add(SourceType.GOOGLE_CALENDAR)
            }
            if (sourceTypes.isEmpty()) {
                return@withContext loadOnboardingActionRows(normalizedUserId).toActivationPreviews(
                    includeIdentityReviewOnly = true,
                )?.let { previews ->
                    OnboardingActivationPreviewResult.Ready(previews)
                } ?: OnboardingActivationPreviewResult.Empty
            }

            val api = apiProvider.get()
            val completedSourceCounts = mutableMapOf<String, Int>()
            var sawFailure = false
            var sawRetryableFailure = false
            sourceTypes.forEach { sourceType ->
                when (
                    val syncResult = syncSourceForActivationPreview(
                        api = api,
                        sourceType = sourceType,
                        onProgress = onProgress,
                    )
                ) {
                    is ActivationSourceSyncResult.Completed -> {
                        completedSourceCounts[syncResult.sourceType] = syncResult.synced
                    }
                    is ActivationSourceSyncResult.Pending -> return@withContext OnboardingActivationPreviewResult.Pending(
                        progress = syncResult.progress,
                        scanSummary = completedSourceCounts.toActivationScanSummary(),
                    )
                    is ActivationSourceSyncResult.Failed -> {
                        sawFailure = true
                        sawRetryableFailure = sawRetryableFailure || syncResult.retryable
                    }
                }
            }

            if (completedSourceCounts.isNotEmpty()) {
                val actionProgress = OnboardingActivationProgress(
                    stage = "action_scan",
                    progress = 0.92,
                    message = "연결한 자료에서 다음 행동을 정리하고 있습니다",
                )
                onProgress(actionProgress)
                completedSourceCounts.keys.forEach { sourceType ->
                    processingStatusRepository.recordScanning(sourceType, actionProgress.message)
                }
                refreshActivationActions(normalizedUserId)
            }

            val scanSummary = completedSourceCounts.toActivationScanSummary()
            loadActivationPreviewFallback(
                userId = normalizedUserId,
                includeGmail = includeGmail,
                includeGoogleCalendar = includeGoogleCalendar,
                api = api,
            )?.let { previews ->
                return@withContext OnboardingActivationPreviewResult.Ready(
                    previews = previews,
                    scanSummary = scanSummary,
                )
            }
            if (completedSourceCounts.isEmpty() && sawFailure) {
                OnboardingActivationPreviewResult.Failed(retryable = sawRetryableFailure)
            } else {
                OnboardingActivationPreviewResult.Empty
            }
        }

    override suspend fun refreshCachedPreview(
        userId: String,
        includeGmail: Boolean,
        includeGoogleCalendar: Boolean,
    ): OnboardingActivationPreviewResult =
        withContext(ioDispatcher) {
            val normalizedUserId = userId.trim()
            if (normalizedUserId.isEmpty()) {
                return@withContext OnboardingActivationPreviewResult.Failed(retryable = false)
            }
            refreshActivationActions(normalizedUserId)
            loadActivationPreviewFallback(
                userId = normalizedUserId,
                includeGmail = includeGmail,
                includeGoogleCalendar = includeGoogleCalendar,
                api = null,
            )?.let { previews ->
                OnboardingActivationPreviewResult.Ready(previews)
            } ?: OnboardingActivationPreviewResult.Empty
        }

    override suspend fun loadCachedPreview(
        userId: String,
        includeGmail: Boolean,
        includeGoogleCalendar: Boolean,
    ): OnboardingActivationPreviewResult =
        withContext(ioDispatcher) {
            val normalizedUserId = userId.trim()
            if (normalizedUserId.isEmpty()) {
                return@withContext OnboardingActivationPreviewResult.Failed(retryable = false)
            }
            loadActivationPreviewFallback(
                userId = normalizedUserId,
                includeGmail = includeGmail,
                includeGoogleCalendar = includeGoogleCalendar,
                api = null,
            )?.let { previews ->
                OnboardingActivationPreviewResult.Ready(previews)
            } ?: OnboardingActivationPreviewResult.Empty
        }

    override suspend fun acceptPreviewAction(
        userId: String,
        actionItemId: String,
    ): BecalmResult<PersonActionMutationSyncStats> =
        withContext(ioDispatcher) {
            personActionRepository.submitActionFeedback(
                userId = userId,
                actionItemId = actionItemId,
                feedbackType = "useful",
                reason = "onboarding_user_accepted",
            )
        }

    override suspend fun dismissPreviewAction(
        userId: String,
        actionItemId: String,
    ): BecalmResult<PersonActionMutationSyncStats> =
        withContext(ioDispatcher) {
            personActionRepository.dismissAction(
                userId = userId,
                actionItemId = actionItemId,
                reason = "onboarding_user_dismissed",
            )
        }

    private suspend fun syncSourceForActivationPreview(
        api: RailwayApi,
        sourceType: String,
        onProgress: suspend (OnboardingActivationProgress) -> Unit,
    ): ActivationSourceSyncResult {
        sourceStatusRepository.recordSyncStart(sourceType)
        val initialProgress = sourceType.initialActivationProgress()
        onProgress(initialProgress)
        processingStatusRepository.recordScanning(sourceType, initialProgress.message)

        val initialSnapshot = when (val requestResult = requestActivationPreviewSync(api, sourceType)) {
            is ActivationSyncRequestResult.Success -> requestResult.snapshot
            is ActivationSyncRequestResult.Failed -> {
                sourceStatusRepository.recordSyncError(sourceType, requestResult.message, Clock.System.now())
                processingStatusRepository.recordError(sourceType, requestResult.message)
                return ActivationSourceSyncResult.Failed(requestResult.retryable)
            }
        }

        if (
            !initialSnapshot.jobId.isNullOrBlank() &&
            initialSnapshot.syncMode != ACTIVATION_PREVIEW_MODE
        ) {
            val progress = OnboardingActivationProgress(
                stage = initialSnapshot.stage ?: "background_sync",
                progress = initialSnapshot.progress ?: 0.35,
                message = "연결한 자료를 백그라운드에서 정리하고 있습니다",
            )
            onProgress(progress)
            processingStatusRepository.recordScanning(sourceType, progress.message)
            workScheduler.enqueueSourceRelationRefresh(
                sourceType,
                initialDelaySeconds = FULL_SYNC_FOLLOW_UP_REFRESH_DELAY_SECONDS,
                resetBeforeRefresh = true,
            )
            return ActivationSourceSyncResult.Pending(progress)
        }

        return when (
            val pollResult = SourceSyncJobPoller(
                api = api,
                logger = logger,
                maxPollAttempts = FOREGROUND_POLL_ATTEMPTS,
                delayMillis = { waitMs -> delay(waitMs.coerceAtMost(FOREGROUND_MAX_WAIT_MS)) },
            ).awaitTerminal(
                sourceType,
                initialSnapshot,
                onSnapshot = { snapshot ->
                    val progress = snapshot.toActivationProgress(sourceType)
                    onProgress(progress)
                    processingStatusRepository.recordScanning(sourceType, progress.message)
                },
            )
        ) {
            is SourceSyncJobPollResult.Completed -> {
                sourceStatusRepository.recordSyncSuccess(sourceType, Clock.System.now())
                processingStatusRepository.recordSynced(
                    sourceType = sourceType,
                    itemCount = pollResult.synced,
                    message = "${sourceType.activationSourceLabel()} 확인 완료",
                )
                workScheduler.enqueueSourceRelationRefresh(
                    sourceType,
                    initialDelaySeconds = FULL_SYNC_FOLLOW_UP_REFRESH_DELAY_SECONDS,
                    resetBeforeRefresh = true,
                )
                ActivationSourceSyncResult.Completed(sourceType = sourceType, synced = pollResult.synced)
            }
            is SourceSyncJobPollResult.Pending -> {
                val progress = OnboardingActivationProgress(
                    stage = pollResult.stage,
                    progress = pollResult.progress,
                    message = pollResult.stage.activationProgressMessage(
                        sourceType = sourceType,
                        fallback = pollResult.message,
                    ),
                )
                processingStatusRepository.recordScanning(sourceType, progress.message)
                workScheduler.enqueueSourceRelationRefresh(
                    sourceType,
                    initialDelaySeconds = FULL_SYNC_FOLLOW_UP_REFRESH_DELAY_SECONDS,
                    resetBeforeRefresh = true,
                )
                ActivationSourceSyncResult.Pending(progress)
            }
            is SourceSyncJobPollResult.Failed -> {
                sourceStatusRepository.recordSyncError(sourceType, pollResult.message, Clock.System.now())
                processingStatusRepository.recordError(sourceType, pollResult.message)
                ActivationSourceSyncResult.Failed(pollResult.retryable)
            }
        }
    }

    private suspend fun requestActivationPreviewSync(
        api: RailwayApi,
        sourceType: String,
    ): ActivationSyncRequestResult {
        return when (sourceType) {
            SourceType.GMAIL -> {
                val response = try {
                    api.syncMailSource(provider = SourceType.GMAIL, mode = ACTIVATION_PREVIEW_MODE)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logger.w(TAG, "activation preview sync request failed sourceType=$sourceType type=${error.javaClass.simpleName}")
                    return ActivationSyncRequestResult.Failed("Gmail sync request failed", retryable = true)
                }
                if (!response.isSuccessful) {
                    return ActivationSyncRequestResult.Failed(
                        message = "HTTP ${response.code()}",
                        retryable = response.code() == 429 || response.code() in 500..599,
                    )
                }
                val body = response.body()
                    ?: return ActivationSyncRequestResult.Failed("Empty Gmail sync response", retryable = true)
                ActivationSyncRequestResult.Success(body.toSourceSyncJobSnapshot())
            }
            SourceType.GOOGLE_CALENDAR -> {
                val response = try {
                    api.syncCalendarEvents(provider = SourceType.GOOGLE_CALENDAR, mode = ACTIVATION_PREVIEW_MODE)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logger.w(TAG, "activation preview sync request failed sourceType=$sourceType type=${error.javaClass.simpleName}")
                    return ActivationSyncRequestResult.Failed("Google Calendar sync request failed", retryable = true)
                }
                if (!response.isSuccessful) {
                    return ActivationSyncRequestResult.Failed(
                        message = "HTTP ${response.code()}",
                        retryable = response.code() == 429 || response.code() in 500..599,
                    )
                }
                val body = response.body()
                    ?: return ActivationSyncRequestResult.Failed("Empty Google Calendar sync response", retryable = true)
                ActivationSyncRequestResult.Success(body.toSourceSyncJobSnapshot())
            }
            else -> ActivationSyncRequestResult.Failed("Unsupported activation preview source", retryable = false)
        }
    }

    private suspend fun refreshActivationActions(userId: String) {
        when (val result = personActionRepository.refresh(userId = userId, surface = ONBOARDING_ACTION_SURFACE)) {
            is BecalmResult.Success -> Unit
            is BecalmResult.Failure -> logger.w(TAG, "gmail activation action refresh failed: ${result.error}")
        }
    }

    private suspend fun loadOnboardingActionRows(userId: String): List<PersonActionItemCacheEntity> =
        personActionRepository.observeActiveForSurface(
            userId = userId,
            surface = ONBOARDING_ACTION_SURFACE,
            limit = PREVIEW_LIMIT,
        ).first()

    private fun List<PersonActionItemCacheEntity>.toActivationPreviews(
        includeIdentityReviewOnly: Boolean,
    ): List<OnboardingActivationPreview>? =
        asSequence()
            .filter { row -> includeIdentityReviewOnly || !row.isIdentityReviewOnly() }
            .map { row -> row.toActivationPreview() }
            .toList()
            .takeIf { it.isNotEmpty() }

    private fun PersonActionItemCacheEntity.isIdentityReviewOnly(): Boolean =
        actionKind == REVIEW_MATCH_ACTION_KIND && commitmentId == null

    private fun List<PersonActionItemCacheEntity>.identityReviewSourceTitles(): Set<String> =
        asSequence()
            .filter { row -> row.isIdentityReviewOnly() }
            .mapNotNull { row -> row.primaryEvidenceLabel?.trim()?.takeIf { it.isNotEmpty() } }
            .toSet()

    private suspend fun loadActivationPreviewFallback(
        userId: String,
        includeGmail: Boolean,
        includeGoogleCalendar: Boolean,
        api: RailwayApi?,
    ): List<OnboardingActivationPreview>? {
        val actionRows = loadOnboardingActionRows(userId)
        actionRows.toActivationPreviews(includeIdentityReviewOnly = false)?.let { return it }
        val identityReviewSourceTitles = actionRows.identityReviewSourceTitles()
        val fallbackPreviews = buildList {
            if (includeGmail) {
                addAll(loadLocalPreview(userId).orEmpty())
                if (isEmpty() && api != null) {
                    addAll(loadRemotePreview(api).orEmpty())
                }
            }
            if (includeGoogleCalendar) {
                addAll(loadCalendarPreview(userId).orEmpty())
            }
        }
        return fallbackPreviews
            .distinctBy { preview ->
                listOf(
                    preview.actionItemId,
                    preview.commitmentId,
                    preview.sourceType,
                    preview.sourceTitle,
                    preview.sourceEventOccurredAt.toString(),
                ).joinToString("|")
            }
            .sortedWith(
                compareByDescending<OnboardingActivationPreview> { preview ->
                    preview.sourceTitle?.trim() in identityReviewSourceTitles
                }.thenByDescending { preview -> preview.sourceEventOccurredAt },
            )
            .take(PREVIEW_LIMIT)
            .takeIf { it.isNotEmpty() }
            ?: actionRows.toActivationPreviews(includeIdentityReviewOnly = true)
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

    private suspend fun loadCalendarPreview(userId: String): List<OnboardingActivationPreview>? {
        val now = Clock.System.now()
        val rangeEnd = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + CALENDAR_PREVIEW_RANGE_MILLIS)
        return calendarEventRepository.observeForUser(
            userId = userId,
            fromInstant = now,
            toInstant = rangeEnd,
        ).first()
            .take(PREVIEW_LIMIT)
            .map { event -> event.toActivationPreview() }
            .takeIf { it.isNotEmpty() }
    }

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
        private const val ONBOARDING_ACTION_SURFACE = "onboarding"
        private const val REVIEW_MATCH_ACTION_KIND = "review_match"
        private const val MIN_PREVIEW_CONFIDENCE = 0.64
        private const val PREVIEW_LIMIT = 3
        private const val REMOTE_PREVIEW_FETCH_LIMIT = 20
        private val PREVIEW_ITEM_TYPES = setOf("action", "schedule", "decision")
        private const val FOREGROUND_POLL_ATTEMPTS = 10
        private const val FOREGROUND_MAX_WAIT_MS = 3_000L
        private const val FULL_SYNC_FOLLOW_UP_REFRESH_DELAY_SECONDS: Long = 45L
        private const val CALENDAR_PREVIEW_RANGE_MILLIS = 183L * 24L * 60L * 60L * 1000L
    }
}

private sealed interface ActivationSourceSyncResult {
    data class Completed(val sourceType: String, val synced: Int) : ActivationSourceSyncResult
    data class Pending(val progress: OnboardingActivationProgress) : ActivationSourceSyncResult
    data class Failed(val retryable: Boolean) : ActivationSourceSyncResult
}

private sealed interface ActivationSyncRequestResult {
    data class Success(val snapshot: SourceSyncJobSnapshot) : ActivationSyncRequestResult
    data class Failed(val message: String, val retryable: Boolean) : ActivationSyncRequestResult
}

private fun SourceSyncJobSnapshot.toActivationProgress(sourceType: String): OnboardingActivationProgress =
    OnboardingActivationProgress(
        stage = stage,
        progress = progress,
        message = stage.activationProgressMessage(sourceType = sourceType, fallback = message),
    )

private fun String?.activationProgressMessage(
    sourceType: String = SourceType.GMAIL,
    fallback: String? = null,
): String =
    when (sourceType) {
        SourceType.GOOGLE_CALENDAR -> when (this) {
            "queued" -> "Google Calendar 연결을 확인하고 있습니다"
            "fetching" -> "최근 일정을 가져오고 있습니다"
            "extracting" -> "일정 속 약속 후보를 확인하고 있습니다"
            "retry_waiting" -> "서버 사용량 제한으로 잠시 대기 중입니다. 곧 자동으로 다시 확인합니다"
            "mirroring" -> "화면에 보여줄 자료를 준비하고 있습니다"
            "complete" -> "Google Calendar 확인을 마쳤습니다"
            else -> fallback ?: "Google Calendar 자료를 정리하고 있습니다"
        }
        else -> when (this) {
            "queued" -> "Gmail 연결을 확인하고 있습니다"
            "fetching" -> "최근 메일을 가져오고 있습니다"
            "extracting" -> "메일 속 약속 후보를 확인하고 있습니다"
            "retry_waiting" -> "서버 사용량 제한으로 잠시 대기 중입니다. 곧 자동으로 다시 확인합니다"
            "mirroring" -> "화면에 보여줄 자료를 준비하고 있습니다"
            "complete" -> "Gmail 확인을 마쳤습니다"
            else -> fallback ?: "Gmail 자료를 정리하고 있습니다"
        }
    }

private fun String.initialActivationProgress(): OnboardingActivationProgress =
    OnboardingActivationProgress(
        stage = "queued",
        progress = 0.08,
        message = "queued".activationProgressMessage(sourceType = this),
    )

private fun String.activationSourceLabel(): String =
    when (this) {
        SourceType.GOOGLE_CALENDAR -> "Google Calendar"
        else -> "Gmail"
    }

private fun Map<String, Int>.toActivationScanSummary(): OnboardingActivationScanSummary =
    OnboardingActivationScanSummary(
        gmailCount = this[SourceType.GMAIL],
        calendarCount = this[SourceType.GOOGLE_CALENDAR],
    )

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

private fun CalendarEventEntity.toActivationPreview(): OnboardingActivationPreview =
    OnboardingActivationPreview(
        commitmentId = id,
        personId = null,
        personName = null,
        participantId = null,
        participantName = null,
        participantEmail = null,
        participantPhone = null,
        contactMatched = false,
        title = title.ifBlank { "제목 없는 일정" },
        itemType = "schedule",
        direction = null,
        scheduleStatus = status,
        decisionStatus = null,
        dueAt = startAt,
        dueHint = null,
        sourceType = sourceType,
        sourceTitle = title.ifBlank { "제목 없는 일정" },
        sourceEventOccurredAt = startAt,
        confidence = 1.0,
    )

private fun PersonActionItemCacheEntity.toActivationPreview(): OnboardingActivationPreview =
    OnboardingActivationPreview(
        commitmentId = commitmentId ?: id,
        personId = personId,
        personName = personDisplayName,
        participantId = null,
        participantName = personDisplayName,
        participantEmail = null,
        participantPhone = null,
        contactMatched = !personId.isNullOrBlank(),
        title = title,
        itemType = activationPreviewItemType(),
        direction = reasonCodesCsv.reasonCodeValue("direction"),
        scheduleStatus = null,
        decisionStatus = null,
        dueAt = dueAt,
        dueHint = dueHint,
        sourceType = sourceType ?: SourceType.GMAIL,
        sourceTitle = primaryEvidenceLabel,
        sourceEventOccurredAt = primaryEvidenceOccurredAt ?: inputWatermark,
        confidence = confidence,
        actionItemId = id,
        actionKind = actionKind,
        reasonCodes = reasonCodesCsv.reasonCodeList(),
    )

private fun PersonActionItemCacheEntity.activationPreviewItemType(): String =
    when {
        calendarEventId != null -> "schedule"
        actionKind in setOf("add_to_calendar", "confirm_schedule") -> "schedule"
        reasonCodesCsv.split(",").any { it.trim() in setOf("calendar_gap", "schedule_diff") } -> "schedule"
        actionKind == "decision" -> "decision"
        else -> "action"
    }

private fun String.reasonCodeValue(prefix: String): String? =
    split(",")
        .asSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$prefix:") }
        ?.substringAfter(":")
        ?.takeIf { it.isNotBlank() }

private fun String.reasonCodeList(): List<String> =
    split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }

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
