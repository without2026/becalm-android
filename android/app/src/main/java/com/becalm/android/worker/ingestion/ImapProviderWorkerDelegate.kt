package com.becalm.android.worker.ingestion

import androidx.work.Data
import androidx.work.ListenableWorker
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.MetricsStore
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.local.secure.ImapCredentialStoreMigrator
import com.becalm.android.data.remote.email.SourceRefEnvelope
import com.becalm.android.data.remote.imap.ImapAttachmentMeta
import com.becalm.android.data.remote.imap.ImapClient
import com.becalm.android.data.repository.EmailBodyRepository
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceArtifactRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.ColdSyncWorkInputs
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.WorkScheduler
import com.becalm.android.worker.WorkerRunGuard
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock

internal data class ImapProviderWorkerProfile(
    val tag: String,
    val sourceType: String,
    val pipaProvider: EmailPipaProvider,
    val config: ImapProviderConfig,
    val maxRetries: Int = 5,
    val defaultLookbackDays: Int = 30,
)

internal class ImapProviderWorkerDelegate(
    private val profile: ImapProviderWorkerProfile,
    private val inputData: Data,
    private val runAttemptCount: Int,
    private val imapCredentialStore: ImapCredentialStore,
    private val imapCredentialStoreMigrator: ImapCredentialStoreMigrator,
    private val syncCursorStore: SyncCursorStore,
    private val imapClient: ImapClient,
    private val rawIngestionRepositoryProvider: Provider<RawIngestionRepository>,
    private val emailBodyRepositoryProvider: Provider<EmailBodyRepository>,
    private val sourceArtifactRepositoryProvider: Provider<SourceArtifactRepository>,
    private val sourceStatusRepositoryProvider: Provider<SourceStatusRepository>,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val userPrefsStore: UserPrefsStore,
    private val workScheduler: WorkScheduler,
    private val metricsStore: MetricsStore,
    private val processingPauseGate: ProcessingPauseGate,
    private val moshi: Moshi,
    private val logger: Logger,
) {
    suspend fun run(): ListenableWorker.Result {
        WorkerRunGuard(
            tag = profile.tag,
            runAttemptCount = runAttemptCount,
            maxRetries = profile.maxRetries,
            processingPauseGate = processingPauseGate,
            logger = logger,
        ).terminalResultOrNull()?.let { return it }

        imapCredentialStoreMigrator.migrateIfNeeded()
        val credentials = imapCredentialStore.load(profile.sourceType) ?: return blockMissingCredentials()
        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId.isNullOrBlank()) {
            logger.w(profile.tag, "no active userId — draining stale work")
            return ListenableWorker.Result.success()
        }

        val lookbackDays = inputData.getInt(
            ColdSyncWorkInputs.KEY_LOOKBACK_DAYS,
            profile.defaultLookbackDays,
        )
        sourceStatusRepository.recordSyncStart(profile.sourceType)
        processingStatusRepository.recordScanning(profile.sourceType)

        val syncOutcome = syncRunner().run(
            imapEmail = credentials.username,
            imapPassword = credentials.appPassword,
            userId = userId,
            lookbackDays = lookbackDays,
        )
        val fetchedCount = when (syncOutcome) {
            is ImapProviderSyncOutcome.Success -> syncOutcome.fetchedCount
            is ImapProviderSyncOutcome.Terminal -> return syncOutcome.result
        }

        processingStatusRepository.recordScanResult(
            sourceType = profile.sourceType,
            itemCount = fetchedCount,
            newItemsMessage = "Queued backend Gemini extraction",
        )
        sourceStatusRepository.recordSyncSuccess(profile.sourceType, Clock.System.now())
        logger.d(profile.tag, "doWork complete")
        return ListenableWorker.Result.success()
    }

    private suspend fun blockMissingCredentials(): ListenableWorker.Result {
        logger.w(profile.tag, "IMAP credentials absent — provisioned by SP-53")
        processingStatusRepository.recordBlocked(profile.sourceType, "IMAP credentials missing")
        userPrefsStore.setEmailSourceConnected(profile.pipaProvider, false)
        userPrefsStore.setEmailSourceManagedByBackend(profile.pipaProvider, false)
        sourceStatusRepository.clear(profile.sourceType)
        return ListenableWorker.Result.success()
    }

    private val sourceStatusRepository: SourceStatusRepository
        get() = sourceStatusRepositoryProvider.get()

    private fun syncRunner(): ImapProviderSyncRunner =
        ImapProviderSyncRunner(
            config = profile.config,
            syncCursorStore = syncCursorStore,
            imapClient = imapClient,
            rawIngestionRepository = rawIngestionRepositoryProvider.get(),
            emailBodyRepository = emailBodyRepositoryProvider.get(),
            messagePersistence = messagePersistence(),
            rawEventMapper = ImapRawEventMapper(
                config = profile.config,
                sourceRefAdapter = moshi.adapter(SourceRefEnvelope::class.java),
            ),
            logger = logger,
            tag = profile.tag,
            onFetchFailure = ::handleFetchFailure,
        )

    private fun messagePersistence(): ImapMessagePersistence =
        ImapMessagePersistence(
            emailBodyRepository = emailBodyRepositoryProvider.get(),
            sourceArtifactRepository = sourceArtifactRepositoryProvider.get(),
            workScheduler = workScheduler,
            metricsStore = metricsStore,
            stringListAdapter = moshi.adapter(Types.newParameterizedType(List::class.java, String::class.java)),
            attachmentListAdapter = moshi.adapter(
                Types.newParameterizedType(List::class.java, ImapAttachmentMeta::class.java),
            ),
            logger = logger,
            tag = profile.tag,
        )

    private suspend fun handleFetchFailure(error: BecalmError): ListenableWorker.Result {
        val errorName = error::class.simpleName ?: "IMAP fetch failed"
        logger.e(profile.tag, "IMAP fetch failed error=$errorName")
        processingStatusRepository.recordError(profile.sourceType, errorName)
        sourceStatusRepository.recordSyncError(
            sourceType = profile.sourceType,
            error = error::class.simpleName ?: "unknown",
            at = Clock.System.now(),
        )
        return when (error) {
            is BecalmError.Unauthorized -> ListenableWorker.Result.failure()
            else -> ListenableWorker.Result.retry()
        }
    }
}
