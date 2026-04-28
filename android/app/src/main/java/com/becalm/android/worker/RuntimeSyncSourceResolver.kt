package com.becalm.android.worker

import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.remote.dto.SourceType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Resolves which sources are actually runnable before WorkManager is touched.
 *
 * `UserPrefsStore.observeEnabledSources()` is intentionally UI-facing and can contain
 * stale connection flags until a worker self-heals them. Startup schedulers use this
 * resolver so credentialless or permission-blocked sources do not wake WorkManager only
 * to no-op immediately.
 */
public interface RuntimeSyncSourceResolver {
    public suspend fun foregroundSources(): Set<String>
    public suspend fun periodicSources(): Set<String>
    public suspend fun hasBackendMailSource(): Boolean
}

@Singleton
public class DefaultRuntimeSyncSourceResolver @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    private val imapCredentialStore: ImapCredentialStore,
    private val mediaAudioPermissionChecker: MediaAudioPermissionChecker,
) : RuntimeSyncSourceResolver {

    override suspend fun foregroundSources(): Set<String> =
        buildSet {
            if (canRunVoice()) add(SourceType.VOICE)
            addAll(periodicSources())
        }

    override suspend fun periodicSources(): Set<String> =
        buildSet {
            if (canRunLocalImap(SourceType.NAVER_IMAP, EmailPipaProvider.NAVER_IMAP)) {
                add(SourceType.NAVER_IMAP)
            }
            if (canRunLocalImap(SourceType.DAUM_IMAP, EmailPipaProvider.DAUM_IMAP)) {
                add(SourceType.DAUM_IMAP)
            }
            if (userPrefsStore.observeSourceEnabled(SourceType.GOOGLE_CALENDAR).first()) {
                add(SourceType.GOOGLE_CALENDAR)
            }
            if (userPrefsStore.observeSourceEnabled(SourceType.OUTLOOK_CALENDAR).first()) {
                add(SourceType.OUTLOOK_CALENDAR)
            }
        }

    override suspend fun hasBackendMailSource(): Boolean =
        canRunBackendMail(EmailPipaProvider.GMAIL) ||
            canRunBackendMail(EmailPipaProvider.OUTLOOK_MAIL)

    private suspend fun canRunVoice(): Boolean =
        userPrefsStore.observeSourceEnabled(SourceType.VOICE).first() &&
            mediaAudioPermissionChecker.isGranted() &&
            !userPrefsStore.observeRecordingFolderTreeUri().first().isNullOrBlank()

    private suspend fun canRunBackendMail(provider: EmailPipaProvider): Boolean =
        userPrefsStore.observeEmailSourceConnected(provider).first() &&
            userPrefsStore.observeEmailSourceManagedByBackend(provider).first()

    private suspend fun canRunLocalImap(sourceType: String, provider: EmailPipaProvider): Boolean {
        val connected = userPrefsStore.observeEmailSourceConnected(provider).first()
        val backendManaged = userPrefsStore.observeEmailSourceManagedByBackend(provider).first()
        if (!connected || backendManaged) return false
        return runCatching { imapCredentialStore.load(sourceType) != null }.getOrDefault(false)
    }
}
