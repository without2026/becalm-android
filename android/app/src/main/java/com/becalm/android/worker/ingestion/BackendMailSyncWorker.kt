package com.becalm.android.worker.ingestion

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.SourceConnectionEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceConnectionRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.data.repository.SOURCE_CONNECTION_STATUS_NEEDS_REAUTH
import com.becalm.android.data.repository.SourceSyncJobPollResult
import com.becalm.android.data.repository.SourceSyncJobPoller
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.repository.UserCorrectionRepository
import com.becalm.android.data.repository.isSyncableBackendSourceConnectionStatus
import com.becalm.android.data.repository.toSnapshot
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

/**
 * Periodically nudges backend-managed mail providers so new Gmail / Outlook Mail
 * messages are discovered without user interaction.
 *
 * Naver / Daum IMAP remain local workers. This worker only calls Railway's
 * `source_connections/{id}:sync` endpoint for OAuth providers whose local connection
 * state is marked backend-managed.
 */
@HiltWorker
public class BackendMailSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val authRepositoryProvider: Provider<AuthRepository>,
    private val apiProvider: Provider<RailwayApi>,
    private val commitmentRepositoryProvider: Provider<CommitmentRepository>,
    private val rawIngestionRepositoryProvider: Provider<RawIngestionRepository>,
    private val sourceConnectionRepositoryProvider: Provider<SourceConnectionRepository>,
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
            maxRetries = null,
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
                trigger = {
                    triggerConnectionScopedMailSync(
                        userId = userId,
                        sourceType = provider.sourceType,
                    )
                },
            ),
        )
    }

    private suspend fun triggerConnectionScopedMailSync(
        userId: String,
        sourceType: String,
    ): ServerBackedTriggerResult {
        val connections = when (val refresh = sourceConnectionRepositoryProvider.get().refresh(userId)) {
            is BecalmResult.Success -> {
                val mailConnections = refresh.value.mailConnectionsFor(sourceType)
                if (mailConnections.any { it.status == SOURCE_CONNECTION_STATUS_NEEDS_REAUTH }) {
                    return ServerBackedTriggerResult.Failure(SOURCE_CONNECTION_STATUS_NEEDS_REAUTH, retryable = false)
                }
                mailConnections.syncableMailConnections()
            }
            is BecalmResult.Failure -> {
                val message = refresh.error.toMailSyncMessage()
                logger.w(TAG, "source connection refresh failed before backend mail sync: $message")
                return ServerBackedTriggerResult.Failure(
                    message = message,
                    retryable = refresh.error is BecalmError.Network || refresh.error is BecalmError.ServerError,
                )
            }
        }
        if (connections.isEmpty()) {
            return ServerBackedTriggerResult.Failure("No source connection for $sourceType", retryable = false)
        }

        val jobs = mutableListOf<Pair<SourceConnectionEntity, com.becalm.android.data.remote.dto.SourceSyncJobResponse>>()
        for (connection in connections) {
            val response = apiProvider.get().syncSourceConnection(connection.id)
            if (!response.isSuccessful) {
                return ServerBackedSyncErrorMapping.triggerFailureFor(response)
            }
            val body = response.body()
                ?: return ServerBackedTriggerResult.Failure("Empty response", retryable = true)
            jobs += connection to body
        }

        var synced = 0
        for ((connection, body) in jobs) {
            when (
                val pollResult = SourceSyncJobPoller(
                    api = apiProvider.get(),
                    logger = logger,
                ).awaitTerminal(sourceType, body.toSnapshot())
            ) {
                is SourceSyncJobPollResult.Completed -> {
                    synced += pollResult.synced
                    logger.d(TAG, "backend mail connection sync success source=$sourceType connectionId=${connection.id} synced=${pollResult.synced}")
                }
                is SourceSyncJobPollResult.Pending -> return ServerBackedTriggerResult.Pending(
                    message = pollResult.message,
                    retryAfterSeconds = pollResult.retryAfterSeconds,
                    reasonCode = pollResult.reasonCode,
                    syncedCount = pollResult.synced,
                )
                is SourceSyncJobPollResult.Failed -> return ServerBackedTriggerResult.Failure(
                    message = pollResult.message,
                    retryable = pollResult.retryable,
                )
            }
        }
        return ServerBackedTriggerResult.Success(syncedCount = synced)
    }

    private sealed class MailProviderSpec(
        val sourceType: String,
    ) {
        data object Gmail : MailProviderSpec(SourceType.GMAIL)
        data object OutlookMail : MailProviderSpec(SourceType.OUTLOOK_MAIL)
    }

    public companion object {
        private const val TAG = "BackendMailSyncWorker"
    }
}

private fun List<SourceConnectionEntity>.mailConnectionsFor(sourceType: String): List<SourceConnectionEntity> =
    filter { connection -> connection.toMailSourceType() == sourceType }

private fun List<SourceConnectionEntity>.syncableMailConnections(): List<SourceConnectionEntity> =
    filter { connection -> isSyncableBackendSourceConnectionStatus(connection.status) }

private fun SourceConnectionEntity.toMailSourceType(): String? =
    when {
        provider == "google" && capability == "mail" -> SourceType.GMAIL
        provider == "outlook" && capability == "mail" -> SourceType.OUTLOOK_MAIL
        else -> null
    }

private fun BecalmError.toMailSyncMessage(): String = when (this) {
    is BecalmError.Network -> "Network error HTTP $code: $message"
    is BecalmError.Unauthorized -> "Unauthorized"
    is BecalmError.RateLimited -> "Rate limited (retryAfter=${retryAfterSeconds}s)"
    is BecalmError.ServerError -> "Server error HTTP $code"
    is BecalmError.Validation -> "Validation error field=$field: $message"
    is BecalmError.Io -> "IO error: $message"
    is BecalmError.Permission -> "Permission denied: $permission"
    is BecalmError.NotFound -> "Not found: $resource"
    is BecalmError.Cancelled -> "Cancelled"
    is BecalmError.ExtractorUnavailable -> "Extractor unavailable: reason=$reason"
    is BecalmError.Unknown -> "Unknown: ${throwable.message}"
}
