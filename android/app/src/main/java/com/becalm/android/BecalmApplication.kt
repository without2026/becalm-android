package com.becalm.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.secure.ImapCredentialStoreMigrator
import com.becalm.android.receiver.ReminderBroadcastReceiver
import com.becalm.android.worker.AuthenticatedRuntimeBootstrap
import com.becalm.android.worker.VoiceFailureNotifier
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
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

    @Inject
    public lateinit var authenticatedRuntimeBootstrapProvider: Provider<AuthenticatedRuntimeBootstrap>

    /**
     * One-shot migrator that promotes the pre-wave-2 single-tuple IMAP credential
     * layout into the new `{naver_imap,daum_imap}_*` namespaced schema. Invoked once
     * per app launch on [applicationScope]; the migrator itself is idempotent (driven
     * by a `UserPrefsStore` flag), so repeat invocations are safe no-ops.
     */
    @Inject
    public lateinit var imapCredentialStoreMigratorProvider: Provider<ImapCredentialStoreMigrator>

    /**
     * [SyncCursorStore] — hosts the one-shot Outlook mail cursor v2 migration that
     * promotes the pre-Wave-3 single-cursor key ("outlook_mail_delta") into the
     * folder-scoped INBOX key ("outlook_mail_inbox_delta"). Idempotent: the store
     * writes a `outlook_mail_migration_v2_done` flag so subsequent cold starts skip
     * the read/write after a single pass.
     */
    @Inject
    public lateinit var syncCursorStoreProvider: Provider<SyncCursorStore>

    /**
     * Unstructured scope for startup fire-and-forget work that must outlive individual
     * component lifecycles but fail independently ([SupervisorJob]). Work submitted
     * here MUST be strictly idempotent — the scope is never explicitly cancelled and
     * receives no structured error propagation back to the caller.
     *
     * Dispatched on [Dispatchers.IO] because startup migrations and authenticated runtime
     * bootstrap perform DataStore, Room, and WorkManager I/O. The heavier Hilt graph is
     * also resolved from this scope via [Provider.get], keeping Application.onCreate small
     * for WorkManager-launched processes.
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

        // CMT-008: register the commitment_due_soon high-importance notification
        // channel at process start. createNotificationChannel is idempotent on API 26+,
        // so repeat cold starts are safe no-ops. ReminderBroadcastReceiver posts to
        // this channel after re-querying Room for the commitment's live state.
        registerCommitmentDueSoonChannel()
        VoiceFailureNotifier.ensureChannel(this)

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
            runCatching { imapCredentialStoreMigratorProvider.get().migrateIfNeeded() }
                .onFailure { Timber.e(it, "BecalmApplication: IMAP migration launch failed") }
        }

        // Promote the Wave 1 single Outlook mail cursor ("outlook_mail_delta") into
        // the folder-scoped INBOX key ("outlook_mail_inbox_delta"). Gated by a
        // `outlook_mail_migration_v2_done` flag inside the store so repeat cold
        // starts bail out after the first DataStore read. runCatching mirrors the
        // other startup migrations — a rare DataStore corruption must not crash
        // the process before the UI is reachable; the next launch retries.
        applicationScope.launch {
            runCatching { syncCursorStoreProvider.get().runOutlookMailCursorMigrationV2() }
                .onFailure { Timber.e(it, "BecalmApplication: Outlook mail cursor v2 migration failed") }
        }

        // Promote the Wave 1 single-INBOX IMAP cursors ("naver" / "daum") into the
        // folder-scoped successors ("naver_inbox" / "daum_inbox"). Gated by an
        // `imap_cursor_migration_v2_done` flag inside the store so repeat cold
        // starts bail out after the first DataStore read. The "_sent" cursors stay
        // null so the first Wave 3 run performs a bounded 30-day cold sync for Sent.
        // runCatching mirrors the other startup migrations — a DataStore corruption
        // must not crash the process before the UI is reachable.
        applicationScope.launch {
            runCatching { syncCursorStoreProvider.get().runImapCursorMigrationV2() }
                .onFailure { Timber.e(it, "BecalmApplication: IMAP cursor v2 migration failed") }
        }

        // WorkManager can start the process directly for SystemJobService. Do not block
        // Application.onCreate on DataStore, Room, or WorkManager calls; JobService must
        // get back to onStartJob promptly even under cold-start I/O pressure (AUTH-009).
        applicationScope.launch {
            authenticatedRuntimeBootstrapProvider.get().startIfSignedIn()
        }
    }

    /**
     * Registers the `commitment_due_soon` notification channel (CMT-008) with
     * `IMPORTANCE_HIGH`. The channel description and name are localized via
     * string resources so the system notification-settings screen can display
     * them in the user's locale.
     *
     * Idempotent on API 26+ — repeat calls from subsequent cold starts are no-ops.
     */
    private fun registerCommitmentDueSoonChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(ReminderBroadcastReceiver.CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            ReminderBroadcastReceiver.CHANNEL_ID,
            getString(R.string.commitment_channel_due_soon_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.commitment_channel_due_soon_desc)
        }
        manager.createNotificationChannel(channel)
    }

}
