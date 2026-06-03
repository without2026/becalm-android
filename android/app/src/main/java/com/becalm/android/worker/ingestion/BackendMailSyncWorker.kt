package com.becalm.android.worker.ingestion

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.data.repository.SourceSyncJobPollResult
import com.becalm.android.data.repository.SourceSyncJobPoller
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.repository.UserCorrectionRepository
import com.becalm.android.data.repository.toSourceSyncJobSnapshot
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.SourceRelationRefreshPlan
import com.becalm.android.worker.WorkScheduler
import com.becalm.android.worker.WorkerRunGuard
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Periodically nudges backend-managed mail providers so new Gmail / Outlook Mail
 * messages are discovered without user interaction.
 *
 * Naver / Daum IMAP remain local workers. This worker only calls Railway's
 * `/v1/mail_sources:sync` endpoint for OAuth providers whose local connection state
 * is marked backend-managed.
 */
@HiltWorker
public class BackendMailSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val authRepositoryProvider: Provider<AuthRepository>,
    private val apiProvider: Provider<RailwayApi>,
    private val commitmentRepositoryProvider: Provider<CommitmentRepository>,
    private val rawIngestionRepositoryProvider: Provider<RawIngestionRepository>,
    private val sourceEventParticipantRepositoryProvider: Provider<SourceEventParticipantRepository>,
    private val commitmentParticipantRepositoryProvider: Provider<CommitmentParticipantRepository>,
    private val userCorrectionRepositoryProvider: Provider<UserCorrectionRepository>,
    private val userPrefsStore: UserPrefsStore,
    private val syncCursorStore: SyncCursorStore,
    private val sourceStatusRepository: SourceStatusRepository,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val processingPauseGate: ProcessingPauseGate,
    private val workScheduler: WorkScheduler,
    private val clock: Clock,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, workerParams) {

    public override suspend fun doWork(): Result = withContext(ioDispatcher) {
        WorkerRunGuard(
            tag = TAG,
            runAttemptCount = runAttemptCount,
            maxRetries = MAX_RETRIES,
            processingPauseGate = processingPauseGate,
            logger = logger,
        ).terminalResultOrNull()?.let { return@withContext it }

        val userId = authRepositoryProvider.get().currentSession()?.userId
        if (userId == null) {
            logger.w(TAG, "no active session — skipping backend mail sync")
            return@withContext Result.success()
        }

        val providers = enabledBackendMailProviders()
        if (providers.isEmpty()) {
            logger.d(TAG, "no backend-managed mail providers enabled")
            return@withContext Result.success()
        }

        var shouldRetry = false
        providers.forEach { provider ->
            val result = syncProvider(provider, userId)
            if (result == ServerBackedSourceSyncResult.RETRY) shouldRetry = true
        }
        if (shouldRetry) Result.retry() else Result.success()
    }

    private suspend fun enabledBackendMailProviders(): List<MailProviderSpec> =
        buildList {
            if (isConnectedBackendProvider(EmailPipaProvider.GMAIL)) add(MailProviderSpec.Gmail)
            if (isConnectedBackendProvider(EmailPipaProvider.OUTLOOK_MAIL)) add(MailProviderSpec.OutlookMail)
        }

    private suspend fun isConnectedBackendProvider(provider: EmailPipaProvider): Boolean =
        userPrefsStore.observeEmailSourceConnected(provider).first() &&
            userPrefsStore.observeEmailSourceManagedByBackend(provider).first()

    private suspend fun syncProvider(provider: MailProviderSpec, userId: String): ServerBackedSourceSyncResult {
        val runner = ServerBackedSourceSyncRunner(
            rawIngestionRepository = rawIngestionRepositoryProvider.get(),
            commitmentRepository = commitmentRepositoryProvider.get(),
            sourceEventParticipantRepository = sourceEventParticipantRepositoryProvider.get(),
            commitmentParticipantRepository = commitmentParticipantRepositoryProvider.get(),
            userCorrectionRepository = userCorrectionRepositoryProvider.get(),
            syncCursorStore = syncCursorStore,
            sourceStatusRepository = sourceStatusRepository,
            processingStatusRepository = processingStatusRepository,
            workScheduler = workScheduler,
            clock = clock,
            logger = logger,
            tag = TAG,
        )
        return runner.run(
            userId = userId,
            request = ServerBackedSourceSyncRequest(
                sourceType = provider.sourceType,
                scanMessage = "Checking new mail",
                geminiMessage = "내용 정리 중",
                syncedMessage = "Checked recent mail",
                recordSyncSuccessBeforeRefresh = true,
                refreshFailureMessage = { "Relation refresh failed" },
                refreshFailureRetryable = { true },
                refreshPlan = SourceRelationRefreshPlan(
                    sourceType = provider.sourceType,
                    rawSourceType = provider.sourceType,
                    resetMirrorCursorBeforeRefresh = true,
                ),
                trigger = { triggerMailSync(provider) },
            ),
        )
    }

    private suspend fun triggerMailSync(provider: MailProviderSpec): ServerBackedTriggerResult =
        try {
            val response = apiProvider.get().syncMailSource(provider = provider.sourceType)
            if (!response.isSuccessful) {
                val message = "HTTP ${response.code()}"
                ServerBackedTriggerResult.Failure(
                    message = message,
                    retryable = response.code() == 429 || response.code() in 500..599,
                )
            } else {
                val body = response.body()
                    ?: return ServerBackedTriggerResult.Failure("Empty response", retryable = true)
                when (
                    val pollResult = SourceSyncJobPoller(
                        api = apiProvider.get(),
                        logger = logger,
                    ).awaitTerminal(provider.sourceType, body.toSourceSyncJobSnapshot())
                ) {
                    is SourceSyncJobPollResult.Completed -> {
                        logger.d(TAG, "backend mail sync success source=${provider.sourceType} synced=${pollResult.synced}")
                        ServerBackedTriggerResult.Success(syncedCount = pollResult.synced)
                    }
                    is SourceSyncJobPollResult.Pending -> ServerBackedTriggerResult.Pending(
                        message = pollResult.message,
                        retryAfterSeconds = pollResult.retryAfterSeconds,
                        reasonCode = pollResult.reasonCode,
                        syncedCount = pollResult.synced,
                    )
                    is SourceSyncJobPollResult.Failed -> ServerBackedTriggerResult.Failure(
                        message = pollResult.message,
                        retryable = pollResult.retryable,
                    )
                }
            }
        } catch (error: Exception) {
            when (error) {
                is HttpException -> ServerBackedTriggerResult.Failure(
                    message = "HTTP ${error.code()}",
                    retryable = error.code() == 429 || error.code() in 500..599,
                )
                else -> {
                    logger.w(TAG, "backend mail sync transient failure source=${provider.sourceType}", error)
                    ServerBackedTriggerResult.Failure(message = "Network error", retryable = true)
                }
            }
        }

    private sealed class MailProviderSpec(
        val sourceType: String,
    ) {
        data object Gmail : MailProviderSpec(SourceType.GMAIL)
        data object OutlookMail : MailProviderSpec(SourceType.OUTLOOK_MAIL)
    }

    public companion object {
        private const val TAG = "BackendMailSyncWorker"
        private const val MAX_RETRIES: Int = 5
    }
}
