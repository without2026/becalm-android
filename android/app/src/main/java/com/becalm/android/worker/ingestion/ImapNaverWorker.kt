package com.becalm.android.worker.ingestion

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.MetricsStore
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.local.secure.ImapCredentialStoreMigrator
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.imap.ImapClient
import com.becalm.android.data.remote.imap.ImapProviderDenylist
import com.becalm.android.data.repository.EmailBodyRepository
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceArtifactRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.WorkScheduler
import com.squareup.moshi.Moshi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider

@HiltWorker
public class ImapNaverWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
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
) : CoroutineWorker(appContext, workerParams) {

    public override suspend fun doWork(): Result =
        ImapProviderWorkerDelegate(
            profile = PROFILE,
            inputData = inputData,
            runAttemptCount = runAttemptCount,
            imapCredentialStore = imapCredentialStore,
            imapCredentialStoreMigrator = imapCredentialStoreMigrator,
            syncCursorStore = syncCursorStore,
            imapClient = imapClient,
            rawIngestionRepositoryProvider = rawIngestionRepositoryProvider,
            emailBodyRepositoryProvider = emailBodyRepositoryProvider,
            sourceArtifactRepositoryProvider = sourceArtifactRepositoryProvider,
            sourceStatusRepositoryProvider = sourceStatusRepositoryProvider,
            processingStatusRepository = processingStatusRepository,
            userPrefsStore = userPrefsStore,
            workScheduler = workScheduler,
            metricsStore = metricsStore,
            processingPauseGate = processingPauseGate,
            moshi = moshi,
            logger = logger,
        ).run()

    public companion object {
        public const val MAX_RETRIES: Int = 5
        public const val NAVER_IMAP_HOST: String = "imap.naver.com"
        public const val NAVER_IMAP_PORT: Int = 993
        public const val MAILBOX_NAVER_INBOX: String = "naver_inbox"
        public const val MAILBOX_NAVER_SENT: String = "naver_sent"
        internal const val FALLBACK_INBOX_NAME: String = "INBOX"
        internal const val FALLBACK_SENT_NAME: String = "보낸메일함"
        internal const val SINCE_DAYS: Int = 30

        private const val TAG = "ImapNaverWorker"
        private const val PROVIDER_NAVER = "naver"
        private val PROFILE = ImapProviderWorkerProfile(
            tag = TAG,
            sourceType = SourceType.NAVER_IMAP,
            pipaProvider = EmailPipaProvider.NAVER_IMAP,
            config = ImapProviderConfig(
                sourceType = SourceType.NAVER_IMAP,
                provider = PROVIDER_NAVER,
                host = NAVER_IMAP_HOST,
                port = NAVER_IMAP_PORT,
                inboxMailboxKey = MAILBOX_NAVER_INBOX,
                sentMailboxKey = MAILBOX_NAVER_SENT,
                fallbackInboxName = FALLBACK_INBOX_NAME,
                fallbackSentName = FALLBACK_SENT_NAME,
                denylist = ImapProviderDenylist.NAVER,
            ),
            maxRetries = MAX_RETRIES,
            defaultLookbackDays = SINCE_DAYS,
        )
    }
}
