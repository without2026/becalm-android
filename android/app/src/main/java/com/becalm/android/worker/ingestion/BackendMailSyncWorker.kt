package com.becalm.android.worker.ingestion

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourcePersonCandidateRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.WorkScheduler
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
    private val sourcePersonCandidateRepositoryProvider: Provider<SourcePersonCandidateRepository>,
    private val userPrefsStore: UserPrefsStore,
    private val sourceStatusRepository: SourceStatusRepository,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val processingPauseGate: ProcessingPauseGate,
    private val workScheduler: WorkScheduler,
    private val clock: Clock,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, workerParams) {

    public override suspend fun doWork(): Result = withContext(ioDispatcher) {
        if (processingPauseGate.shouldSkip(TAG)) {
            return@withContext Result.success()
        }
        if (runAttemptCount >= MAX_RETRIES) {
            logger.e(TAG, "Exceeded $MAX_RETRIES attempts, failing permanently")
            return@withContext Result.failure()
        }
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
            if (result == ProviderSyncResult.RETRY) shouldRetry = true
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

    private suspend fun syncProvider(provider: MailProviderSpec, userId: String): ProviderSyncResult {
        sourceStatusRepository.recordSyncStart(provider.sourceType)
        processingStatusRepository.recordScanning(provider.sourceType, "Checking new mail")

        return try {
            processingStatusRepository.recordGemini(provider.sourceType, "Checking mail with Gemini")
            val response = apiProvider.get().syncMailSource(provider = provider.sourceType)
            if (!response.isSuccessful) {
                return response.code().toProviderFailure(provider)
            }

            val synced = response.body()?.synced ?: 0
            processingStatusRepository.recordSynced(
                sourceType = provider.sourceType,
                itemCount = synced,
                message = "Checked recent mail",
            )
            sourceStatusRepository.recordSyncSuccess(provider.sourceType, clock.nowInstant())
            logger.d(TAG, "backend mail sync success source=${provider.sourceType} synced=$synced")
            refreshRawEventsAndCommitments(provider, userId)
        } catch (error: Exception) {
            when (error) {
                is HttpException -> error.code().toProviderFailure(provider)
                else -> {
                    logger.w(TAG, "backend mail sync transient failure source=${provider.sourceType}", error)
                    sourceStatusRepository.recordSyncError(provider.sourceType, "Network error", clock.nowInstant())
                    processingStatusRepository.recordError(provider.sourceType, "Network error")
                    ProviderSyncResult.RETRY
                }
            }
        }
    }

    private suspend fun refreshRawEventsAndCommitments(provider: MailProviderSpec, userId: String): ProviderSyncResult {
        when (val result = rawIngestionRepositoryProvider.get().refreshSince(userId = userId, sourceType = provider.sourceType, since = null)) {
            is BecalmResult.Success -> logger.d(
                TAG,
                "raw event refresh after mail sync source=${provider.sourceType} " +
                    "fetched=${result.value.fetched} upserted=${result.value.upserted}",
            )
            is BecalmResult.Failure -> {
                sourceStatusRepository.recordSyncError(
                    provider.sourceType,
                    "Raw mail refresh failed",
                    clock.nowInstant(),
                )
                processingStatusRepository.recordError(provider.sourceType, "Raw mail refresh failed")
                return ProviderSyncResult.RETRY
            }
        }

        when (
            val result = sourcePersonCandidateRepositoryProvider.get()
                .refreshSince(userId = userId, sourceType = provider.sourceType, since = null)
        ) {
            is BecalmResult.Success -> logger.d(
                TAG,
                "person candidate refresh after mail sync source=${provider.sourceType} " +
                    "fetched=${result.value.fetched} upserted=${result.value.upserted}",
            )
            is BecalmResult.Failure -> {
                sourceStatusRepository.recordSyncError(
                    provider.sourceType,
                    "Person candidate refresh failed",
                    clock.nowInstant(),
                )
                processingStatusRepository.recordError(provider.sourceType, "Person candidate refresh failed")
                return ProviderSyncResult.RETRY
            }
        }

        return when (val result = commitmentRepositoryProvider.get().refreshSince(userId = userId, since = null)) {
            is BecalmResult.Success -> {
                logger.d(
                    TAG,
                    "commitment refresh after mail sync source=${provider.sourceType} " +
                        "fetched=${result.value.fetched} upserted=${result.value.upserted}",
                )
                workScheduler.enqueuePersonInteractionIndex()
                ProviderSyncResult.SUCCESS
            }
            is BecalmResult.Failure -> {
                sourceStatusRepository.recordSyncError(
                    provider.sourceType,
                    "Commitment refresh failed",
                    clock.nowInstant(),
                )
                processingStatusRepository.recordError(provider.sourceType, "Commitment refresh failed")
                logger.w(
                    TAG,
                    "commitment refresh after mail sync failed source=${provider.sourceType} " +
                        "error=${result.error::class.simpleName}",
                )
                ProviderSyncResult.RETRY
            }
        }
    }

    private suspend fun Int.toProviderFailure(provider: MailProviderSpec): ProviderSyncResult {
        val message = "HTTP $this"
        sourceStatusRepository.recordSyncError(provider.sourceType, message, clock.nowInstant())
        processingStatusRepository.recordError(provider.sourceType, message)
        logger.w(TAG, "backend mail sync failed source=${provider.sourceType} code=$this")
        return when (this) {
            429, in 500..599 -> ProviderSyncResult.RETRY
            else -> ProviderSyncResult.FAILURE
        }
    }

    private enum class ProviderSyncResult {
        SUCCESS,
        RETRY,
        FAILURE,
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
