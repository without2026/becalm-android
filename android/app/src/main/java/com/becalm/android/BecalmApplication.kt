package com.becalm.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.becalm.android.data.local.secure.ImapCredentialStoreMigrator
import com.becalm.android.data.remote.gmail.GoogleAuthTokenProviderImpl
import com.becalm.android.worker.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import timber.log.Timber

/**
 * Application class for BeCalm Android.
 *
 * Implements [Configuration.Provider] so that WorkManager uses [HiltWorkerFactory],
 * which is required because multiple workers are annotated with [@HiltWorker][androidx.hilt.work.HiltWorker].
 * Default WorkManager auto-initialization is disabled in AndroidManifest.xml.
 *
 * Plants the Timber tree in debug builds only. In release builds nothing is planted so
 * [com.becalm.android.core.util.TimberLogger] calls become no-ops — PIPA Article 29
 * (no secondary exposure via logcat).
 */
@HiltAndroidApp
public class BecalmApplication : Application(), Configuration.Provider {

    @Inject
    public lateinit var workerFactory: HiltWorkerFactory

    /**
     * One-release upgrade compat shim for the #13 MediaStore unique-work rename.
     *
     * TODO(wave-N+2): Remove alongside [com.becalm.android.worker.UniqueWorkKeys.LEGACY_MEDIA_STORE_KEY]
     * and [WorkScheduler.cleanupLegacyWorkNames] once the pre-#13 install base has drained.
     */
    @Inject
    public lateinit var workScheduler: WorkScheduler

    /**
     * One-shot migrator that promotes the pre-wave-2 single-tuple IMAP credential
     * layout into the new `{naver_imap,daum_imap}_*` namespaced schema. Invoked once
     * per app launch on [applicationScope]; the migrator itself is idempotent (driven
     * by a `UserPrefsStore` flag), so repeat invocations are safe no-ops.
     */
    @Inject
    public lateinit var imapCredentialStoreMigrator: ImapCredentialStoreMigrator

    /**
     * Concrete [GoogleAuthTokenProviderImpl] — injected by concrete type rather than
     * through the [com.becalm.android.data.remote.gmail.GoogleAuthTokenProvider]
     * interface so that the startup [GoogleAuthTokenProviderImpl.warmUp] call is
     * reachable. The interface intentionally does not expose warm-up because it is an
     * implementation concern (loading the Keystore-backed credential into the hot-path
     * cache). Without this warm-up, the first Gmail request after a process restart
     * would see `currentToken() == null` and fail with `BecalmError.Unauthorized` even
     * though a valid token is persisted on-device (ING-006 cold-start regression).
     */
    @Inject
    public lateinit var googleAuthTokenProvider: GoogleAuthTokenProviderImpl

    /**
     * Unstructured scope for startup fire-and-forget work that must outlive individual
     * component lifecycles but fail independently ([SupervisorJob]). Work submitted
     * here MUST be strictly idempotent — the scope is never explicitly cancelled and
     * receives no structured error propagation back to the caller.
     *
     * Dispatched on [Dispatchers.IO] because the only current consumer
     * ([ImapCredentialStoreMigrator.migrateIfNeeded]) performs [SharedPreferences] +
     * DataStore disk I/O end-to-end. The migrator itself re-dispatches to
     * `@IoDispatcher`, so either `IO` or `Default` would work — `IO` is chosen to match
     * the plan verbatim and to keep the top-level launch context aligned with the
     * dominant workload.
     */
    private val applicationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // Fire-and-forget upgrade compat: cancel WorkManager unique-work under the pre-#13
        // `ingest.sms_call` name so devices upgrading from that build don't run duplicate
        // MediaStore scans alongside the new `ingest.media_store` key. Idempotent — cancelling
        // a non-existent unique-work name is a no-op on subsequent cold starts.
        workScheduler.cleanupLegacyWorkNames()

        // Promote the legacy single-tuple IMAP credential layout to the per-provider
        // namespaced schema (ING-011 parallel-execution invariant). Idempotent via
        // UserPrefsStore flag; further launches are cheap no-ops.
        //
        // Wrapped in runCatching so a rare Keystore / encrypted-prefs corruption path
        // does not take the process down before the UI is reachable — the next launch
        // retries the migration (flag is only set on success), and workers self-heal
        // by invoking [ImapCredentialStoreMigrator.migrateIfNeeded] at the top of their
        // own [ImapNaverWorker.doWork] / [ImapDaumWorker.doWork].
        applicationScope.launch {
            runCatching { imapCredentialStoreMigrator.migrateIfNeeded() }
                .onFailure { Timber.e(it, "BecalmApplication: IMAP migration launch failed") }
        }

        // Hydrate the Gmail OAuth credential cache from Keystore. `currentToken()` is
        // called synchronously on the OkHttp dispatcher and cannot reach disk itself,
        // so without this eager warm-up the first Gmail API request after every
        // process restart would see a cold cache and fail with
        // `BecalmError.Unauthorized` even when a valid token is persisted. Idempotent.
        //
        // Wrapped in runCatching for the same reason: [warmUp] can throw an
        // `IOException` when its internal self-defense path fails to commit the
        // pin-preserving clear for an expired credential on a damaged Keystore. Letting
        // that propagate to the root coroutine would crash the process before the UI
        // could drive recovery — the worker-level [GoogleAuthTokenProviderImpl.warmUp]
        // call inside [GmailWorker.doWork] retries on next sync, and
        // [GmailWorker] then falls through to [refreshSilently] which surfaces
        // reauth state to the UI.
        applicationScope.launch {
            runCatching { googleAuthTokenProvider.warmUp() }
                .onFailure { Timber.e(it, "BecalmApplication: Gmail OAuth warmUp launch failed") }
        }
    }
}
