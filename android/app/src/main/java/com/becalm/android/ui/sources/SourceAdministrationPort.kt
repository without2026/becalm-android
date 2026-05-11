package com.becalm.android.ui.sources

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.coroutines.rethrowIfCancellation
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.OUTLOOK_MAIL_INBOX_CURSOR_KEY
import com.becalm.android.data.local.datastore.OUTLOOK_MAIL_SENT_CURSOR_KEY
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.ingestion.ImapDaumWorker
import com.becalm.android.worker.ingestion.ImapNaverWorker
import com.becalm.android.worker.ingestion.MediaStoreWorker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observable disconnect-side-effect summary used by SourceDetail unit tests.
 *
 * The real implementation may compute these booleans from DataStore / Keystore / Room
 * collaborators; the UI layer only needs a single contract object it can surface or fake.
 */
public data class SourceDisconnectOutcome(
    val sourceType: String,
    val cursorCleared: Boolean,
    val credentialsDeleted: Boolean,
    val roomDataRetained: Boolean,
)

/**
 * Administration seam for per-source disconnect flows.
 *
 * Extracted so SourceDetailViewModel tests can fake the disconnect result contract
 * without reaching into DataStore / Keystore implementation details.
 */
public interface SourceAdministrationPort {
    public suspend fun disconnect(sourceType: String): BecalmResult<SourceDisconnectOutcome>
}

@Singleton
public class DefaultSourceAdministrationPort @Inject constructor(
    private val sourceStatusRepository: SourceStatusRepository,
    private val syncCursorStore: SyncCursorStore,
    private val userPrefsStore: UserPrefsStore,
    private val imapCredentialStore: ImapCredentialStore,
    private val logger: Logger,
) : SourceAdministrationPort {

    override suspend fun disconnect(sourceType: String): BecalmResult<SourceDisconnectOutcome> {
        return try {
            val cursorCleared = clearSourceCursor(sourceType)
            val credentialsDeleted = clearSourceCredentials(sourceType)
            clearSourceConnectionState(sourceType)
            when (val statusResult = sourceStatusRepository.clear(sourceType)) {
                is BecalmResult.Failure -> statusResult
                is BecalmResult.Success -> {
                    logger.i(TAG, "disconnect completed sourceType=$sourceType")
                    BecalmResult.Success(
                        SourceDisconnectOutcome(
                            sourceType = sourceType,
                            cursorCleared = cursorCleared,
                            credentialsDeleted = credentialsDeleted,
                            roomDataRetained = true,
                        ),
                    )
                }
            }
        } catch (e: IOException) {
            logger.e(TAG, "disconnect IO failure sourceType=$sourceType", e)
            BecalmResult.Failure(BecalmError.Io(e.message ?: "disconnect failed"))
        } catch (e: Throwable) {
            e.rethrowIfCancellation()
            logger.e(TAG, "disconnect unexpected failure sourceType=$sourceType", e)
            BecalmResult.Failure(BecalmError.Unknown(e))
        }
    }

    private suspend fun clearSourceCursor(sourceType: String): Boolean =
        when (sourceType) {
            SourceType.GMAIL -> {
                syncCursorStore.setGmailHistoryId(null)
                true
            }
            SourceType.OUTLOOK_MAIL -> {
                syncCursorStore.clearCursor(OUTLOOK_MAIL_INBOX_CURSOR_KEY)
                syncCursorStore.clearCursor(OUTLOOK_MAIL_SENT_CURSOR_KEY)
                true
            }
            SourceType.NAVER_IMAP -> {
                syncCursorStore.setImapState(ImapNaverWorker.MAILBOX_NAVER_INBOX, null)
                syncCursorStore.setImapState(ImapNaverWorker.MAILBOX_NAVER_SENT, null)
                true
            }
            SourceType.DAUM_IMAP -> {
                syncCursorStore.setImapState(ImapDaumWorker.MAILBOX_DAUM_INBOX, null)
                syncCursorStore.setImapState(ImapDaumWorker.MAILBOX_DAUM_SENT, null)
                true
            }
            SourceType.VOICE -> {
                syncCursorStore.setMediaStoreLastSeen(MediaStoreWorker.KIND_VOICE, null)
                true
            }
            SourceType.MEETING -> {
                syncCursorStore.setMediaStoreLastSeen(MediaStoreWorker.KIND_MEETING, null)
                true
            }
            else -> false
        }

    private suspend fun clearSourceCredentials(sourceType: String): Boolean =
        when (sourceType) {
            SourceType.NAVER_IMAP,
            SourceType.DAUM_IMAP,
            -> {
                imapCredentialStore.clear(sourceType)
                true
            }
            else -> false
        }

    private suspend fun clearSourceConnectionState(sourceType: String) {
        when (sourceType) {
            SourceType.VOICE,
            SourceType.GOOGLE_CALENDAR,
            SourceType.OUTLOOK_CALENDAR,
            -> userPrefsStore.setSourceEnabled(sourceType, false)

            SourceType.GMAIL -> userPrefsStore.setEmailSourceConnected(EmailPipaProvider.GMAIL, false)
            SourceType.OUTLOOK_MAIL -> userPrefsStore.setEmailSourceConnected(EmailPipaProvider.OUTLOOK_MAIL, false)
            SourceType.NAVER_IMAP -> userPrefsStore.setEmailSourceConnected(EmailPipaProvider.NAVER_IMAP, false)
            SourceType.DAUM_IMAP -> userPrefsStore.setEmailSourceConnected(EmailPipaProvider.DAUM_IMAP, false)
        }
        when (sourceType) {
            SourceType.GMAIL -> userPrefsStore.setEmailSourceManagedByBackend(EmailPipaProvider.GMAIL, false)
            SourceType.OUTLOOK_MAIL -> userPrefsStore.setEmailSourceManagedByBackend(EmailPipaProvider.OUTLOOK_MAIL, false)
            SourceType.NAVER_IMAP -> userPrefsStore.setEmailSourceManagedByBackend(EmailPipaProvider.NAVER_IMAP, false)
            SourceType.DAUM_IMAP -> userPrefsStore.setEmailSourceManagedByBackend(EmailPipaProvider.DAUM_IMAP, false)
            else -> Unit
        }
    }

    private companion object {
        private const val TAG = "SourceAdminPort"
    }
}

@Module
@InstallIn(SingletonComponent::class)
public abstract class SourceAdministrationModule {

    @Binds
    @Singleton
    public abstract fun bindSourceAdministrationPort(
        impl: DefaultSourceAdministrationPort,
    ): SourceAdministrationPort
}
