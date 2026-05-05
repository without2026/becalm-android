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
public class ImapDaumWorker @AssistedInject constructor(
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
        public const val DAUM_IMAP_HOST: String = "imap.daum.net"
        public const val DAUM_IMAP_PORT: Int = 993
        public const val MAILBOX_DAUM_INBOX: String = "daum_inbox"
        public const val MAILBOX_DAUM_SENT: String = "daum_sent"
        internal const val FALLBACK_INBOX_NAME: String = "INBOX"
        internal const val FALLBACK_SENT_NAME: String = "보낸편지함"
        internal const val SINCE_DAYS: Int = 30

        private const val TAG = "ImapDaumWorker"
        private const val PROVIDER_DAUM = "daum"
        private val PROFILE = ImapProviderWorkerProfile(
            tag = TAG,
            sourceType = SourceType.DAUM_IMAP,
            pipaProvider = EmailPipaProvider.DAUM_IMAP,
            config = ImapProviderConfig(
                sourceType = SourceType.DAUM_IMAP,
                provider = PROVIDER_DAUM,
                host = DAUM_IMAP_HOST,
                port = DAUM_IMAP_PORT,
                inboxMailboxKey = MAILBOX_DAUM_INBOX,
                sentMailboxKey = MAILBOX_DAUM_SENT,
                fallbackInboxName = FALLBACK_INBOX_NAME,
                fallbackSentName = FALLBACK_SENT_NAME,
                denylist = ImapProviderDenylist.DAUM,
            ),
            maxRetries = MAX_RETRIES,
            defaultLookbackDays = SINCE_DAYS,
        )
    }
}
