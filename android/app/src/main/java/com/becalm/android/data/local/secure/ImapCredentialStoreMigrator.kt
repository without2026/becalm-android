package com.becalm.android.data.local.secure

import android.content.Context
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.dto.SourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot migrator that copies the legacy single-tuple IMAP credentials stored under
 * `imap_username` / `imap_app_password` / `imap_host` / `imap_port` into the new
 * per-provider namespace (`naver_imap_*` or `daum_imap_*`) on first app start after the
 * [ImapCredentialStore] refactor lands.
 *
 * ## Why a separate class
 * [ImapCredentialStore] is the single-responsibility owner of the new schema — it does
 * not know about the legacy keys. Putting the migration in a dedicated `@Singleton`
 * class keeps the store clean (CLAUDE.md "Surgical Changes") and lets tests drive the
 * migration in isolation from the production store's happy-path code paths.
 *
 * ## Invariant
 * Every public API of [ImapCredentialStore] must observe migrated data. Authenticated
 * runtime bootstrap invokes the migrator before source workers are scheduled, and IMAP
 * workers also invoke it at the top of their own work path for persisted-work recovery.
 *
 * ## Failure policy (fail loudly)
 * On any [Throwable] the flag is NOT set. The next app start retries the migration.
 * Exceptions are logged at ERROR with tag [TAG] so crash-reporting can correlate a
 * repeated failure to the migration step — not swallowed.
 */
@Singleton
public class ImapCredentialStoreMigrator @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val userPrefsStore: UserPrefsStore,
    private val logger: Logger,
) {

    /**
     * Performs the one-shot migration if the DataStore flag indicates it has not yet run.
     *
     * Steps (each guarded by the flag):
     * 1. Read the migrated flag — if true, return.
     * 2. Read the 4 legacy keys. If any is absent, set the flag and return (idempotent).
     * 3. Classify the host: `imap.naver.com*` → Naver, `imap.daum.net*` → Daum, other →
     *    log warning and default to Naver (BeCalm only supports these two providers).
     * 4. Write the 4 namespaced keys AND remove the 4 legacy keys in one atomic
     *    [SharedPreferences.Editor] transaction so a crash mid-migration cannot leave
     *    both schemas visible.
     * 5. Set the migrated flag.
     *
     * Any [Throwable] is caught and logged; the flag is NOT set so the next app start
     * retries the migration. Non-zero retry cost is acceptable — this runs at most once
     * per install after the refactor lands.
     */
    public suspend fun migrateIfNeeded(): Unit = withContext(ioDispatcher) {
        try {
            val alreadyMigrated = userPrefsStore.observeImapMigrated().first()
            if (alreadyMigrated) {
                return@withContext
            }

            val prefs = buildStorePrefs(
                context,
                ImapCredentialStore.FILE_NAME,
                ImapCredentialStore.MASTER_KEY_ALIAS,
                "ImapCredentialStoreMigrator",
            )

            val username = prefs.getString(LEGACY_KEY_USERNAME, null)
            val password = prefs.getString(LEGACY_KEY_APP_PASSWORD, null)
            val host = prefs.getString(LEGACY_KEY_HOST, null)
            // Legacy schema used getInt(KEY_PORT, 993) at read-time. absent-key detection
            // therefore also requires checking `contains`.
            val port = if (prefs.contains(LEGACY_KEY_PORT)) prefs.getInt(LEGACY_KEY_PORT, 0) else null

            if (username.isNullOrBlank() || password.isNullOrBlank() || host.isNullOrBlank() || port == null) {
                // Nothing to migrate — set the flag so we skip this block on future starts.
                userPrefsStore.setImapMigrated(true)
                logger.d(TAG, "migrateIfNeeded: no legacy tuple present — marked migrated")
                return@withContext
            }

            // Classify the legacy host into the per-provider namespace. An unknown host
            // leaves both the legacy tuple AND the migrated flag untouched so the user
            // can manually re-enter credentials through the onboarding UI (or a future
            // recovery path) — silently coercing into `naver_imap_*` would render the
            // tuple unreachable (the Daum worker connects to a hard-coded Daum host; a
            // Naver-namespaced Daum credential set would hit AUTH failure every run).
            val targetSourceType = classifyHost(host) ?: run {
                logger.w(
                    TAG,
                    "imap_migration_unknown_host — leaving legacy tuple in place; " +
                        "user must re-enter credentials via onboarding",
                )
                return@withContext
            }

            // [SharedPreferences.Editor.commit] is intentional, NOT [apply]: apply() is
            // asynchronous, which opens a small window where the flag below can be
            // persisted while the namespaced credentials have not yet hit disk. A process
            // death in that window would leave the device with the flag=true and no
            // namespaced keys visible, permanently wedging IMAP access on the next boot
            // until the user manually re-enters credentials. Synchronous commit() closes
            // that window at the cost of one I/O hop per upgrade (runs once per install).
            val commitOk = prefs.edit()
                .putString(namespacedKey(targetSourceType, ImapCredentialStore.SUFFIX_USERNAME), username)
                .putString(namespacedKey(targetSourceType, ImapCredentialStore.SUFFIX_PASSWORD), password)
                .putString(namespacedKey(targetSourceType, ImapCredentialStore.SUFFIX_HOST), host)
                .putInt(namespacedKey(targetSourceType, ImapCredentialStore.SUFFIX_PORT), port)
                .remove(LEGACY_KEY_USERNAME)
                .remove(LEGACY_KEY_APP_PASSWORD)
                .remove(LEGACY_KEY_HOST)
                .remove(LEGACY_KEY_PORT)
                .commit()

            if (!commitOk) {
                // Fail loudly. Do NOT set the migrated flag — the next app start retries.
                logger.e(TAG, "migrateIfNeeded: SharedPreferences commit returned false — migration will retry on next launch")
                return@withContext
            }

            userPrefsStore.setImapMigrated(true)
            logger.d(TAG, "migrateIfNeeded: migrated legacy tuple to sourceType=$targetSourceType")
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            // Fail loudly — do NOT set the flag so the next app start retries.
            logger.e(TAG, "migrateIfNeeded: migration failed", t)
        }
    }

    /**
     * Classifies a legacy `host` value into the per-provider namespace it belongs to,
     * or `null` when the host is unknown.
     *
     * BeCalm only bundles Naver + Daum IMAP. Earlier drafts defaulted unknown hosts to
     * Naver, but that silently coerces Daum-ish (or typo'd) tuples into a namespace the
     * Daum worker cannot read — the worker connects to a hard-coded Daum host and would
     * hit AUTH failure on every run. Returning `null` leaves both the legacy tuple and
     * the migration flag untouched so the next app launch retries (if the user corrects
     * the host) or the user can re-enter credentials through onboarding.
     */
    private fun classifyHost(host: String): String? = when {
        host.startsWith(HOST_PREFIX_NAVER, ignoreCase = true) -> SourceType.NAVER_IMAP
        host.startsWith(HOST_PREFIX_DAUM, ignoreCase = true) -> SourceType.DAUM_IMAP
        else -> null
    }

    private fun namespacedKey(sourceType: String, suffix: String): String =
        "${sourceType}_$suffix"

    public companion object {
        internal const val TAG: String = "ImapCredentialStoreMigrator"

        // ── Legacy key names (pre-per-provider refactor) ─────────────────────
        // Kept as private-to-package constants so tests can refer to them by name
        // rather than duplicating the string literals.
        internal const val LEGACY_KEY_USERNAME: String = "imap_username"
        internal const val LEGACY_KEY_APP_PASSWORD: String = "imap_app_password"
        internal const val LEGACY_KEY_HOST: String = "imap_host"
        internal const val LEGACY_KEY_PORT: String = "imap_port"

        // ── Host prefixes used to classify a legacy tuple ────────────────────
        internal const val HOST_PREFIX_NAVER: String = "imap.naver.com"
        internal const val HOST_PREFIX_DAUM: String = "imap.daum.net"
    }
}
