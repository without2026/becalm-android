package com.becalm.android.data.local.secure

import android.content.Context
import android.content.SharedPreferences
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.data.remote.dto.SourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMAP credentials for a single provider slot (Naver or Daum).
 *
 * @param username     IMAP login address (full email address).
 * @param appPassword  Provider-specific app-password (Naver / Daum both require an app
 *   password distinct from the account password for IMAP access).
 * @param host         IMAPS server hostname. Required — caller selects the value from the
 *   provider's `sourceType` (no default).
 * @param port         IMAPS port. Required — caller supplies (typically 993 for implicit TLS).
 *
 * ## Removed defaults
 * Prior revisions defaulted `host` / `port` to Naver's values via
 * `ImapCredentialStore.DEFAULT_HOST` / `DEFAULT_PORT`. That coupled the data class to
 * one provider and silently produced Naver-shaped credentials when a caller forgot to
 * pass a host — exactly the architectural shape the per-provider refactor eliminates.
 * Both defaults are now gone so every construction site must decide host / port
 * explicitly from its `sourceType`.
 */
public data class ImapCredentials(
    val username: String,
    val appPassword: String,
    val host: String,
    val port: Int,
)

/**
 * Encrypted, per-provider store for IMAP credentials (username + app-password).
 *
 * ## Per-provider namespacing (ING-011 parallel-execution invariant)
 * Every public API now requires a `sourceType: String` matching one of
 * [SourceType.NAVER_IMAP] / [SourceType.DAUM_IMAP]. Internal keys are prefixed with that
 * source-type string (`naver_imap_host`, `daum_imap_host`, `daum_imap_password`, …) so that the Naver and
 * Daum workers can read / write their own namespace without poisoning each other's slot
 * — the root cause of the previous single-tuple design that `.spec/data-ingestion.spec.yml:155`
 * ("6개 소스 어댑터는 병렬 실행") could not tolerate.
 *
 * Any other `sourceType` value fails the `require` guard at the top of every public
 * method (fail-loudly per `CLAUDE.md` — silent fallback to Naver would mask wiring bugs).
 *
 * ## Security model
 * Uses a dedicated [EncryptedSharedPreferences] file (`becalm_imap_credentials`) backed by
 * Android Keystore. The master key alias (`becalm_imap_credential_store_master_key`) is
 * distinct from those used by [EncryptedTokenStore] and [DeviceKeyStore] to limit blast
 * radius from individual file corruption events.
 *
 * Key encryption: AES-256-SIV (deterministic — enables namespace prefix scanning).
 * Value encryption: AES-256-GCM (probabilistic, fresh nonce per write).
 *
 * ## Keystore damage recovery
 * On Keystore or file corruption the preference file is wiped and rebuilt once.
 * A second consecutive failure re-throws — the device Keystore is broken beyond recovery.
 *
 * ## Thread safety
 * All disk I/O is dispatched on [Dispatchers.IO]. [EncryptedSharedPreferences] itself is
 * thread-safe; two different namespaces can be read or written concurrently without a
 * higher-level lock.
 *
 * CRIT-01: replaces plaintext DataStore storage of IMAP app-passwords.
 */
@Singleton
public class ImapCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    public companion object {
        internal const val FILE_NAME: String = "becalm_imap_credentials"

        // Distinct from EncryptedTokenStore alias ("becalm_token_store_master_key")
        // and DeviceKeyStore alias ("becalm_device_key_store_master_key").
        internal const val MASTER_KEY_ALIAS: String = "becalm_imap_credential_store_master_key"

        // ── Key suffixes (combined with the source_type namespace prefix by [key]) ──

        internal const val SUFFIX_HOST: String = "host"
        internal const val SUFFIX_PORT: String = "port"
        internal const val SUFFIX_USERNAME: String = "username"
        internal const val SUFFIX_PASSWORD: String = "password"

        // ── Set of source types that map to an IMAP credential slot ───────────
        internal val ALLOWED_IMAP_SOURCES: Set<String> =
            setOf(SourceType.NAVER_IMAP, SourceType.DAUM_IMAP)
    }

    private val prefs: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        buildStorePrefs(context, FILE_NAME, MASTER_KEY_ALIAS, "ImapCredentialStore")
    }

    /**
     * Persists [creds] into the namespace identified by [sourceType].
     *
     * A concurrent call that targets a different `sourceType` is safe — the two writes
     * touch disjoint key sets.
     *
     * Disk access is performed on [Dispatchers.IO].
     *
     * @throws IllegalArgumentException when [sourceType] is not [SourceType.NAVER_IMAP]
     *   or [SourceType.DAUM_IMAP].
     */
    public suspend fun save(sourceType: String, creds: ImapCredentials): Unit =
        withContext(ioDispatcher) {
            require(sourceType in ALLOWED_IMAP_SOURCES) {
                "unknown IMAP sourceType: $sourceType"
            }
            prefs.edit()
                .putString(key(sourceType, SUFFIX_USERNAME), creds.username)
                .putString(key(sourceType, SUFFIX_PASSWORD), creds.appPassword)
                .putString(key(sourceType, SUFFIX_HOST), creds.host)
                .putInt(key(sourceType, SUFFIX_PORT), creds.port)
                .apply()
            Timber.d("ImapCredentialStore: credentials saved sourceType=%s", sourceType)
        }

    /**
     * Returns the [ImapCredentials] stored under [sourceType], or `null` when that
     * namespace has never been written (or was cleared).
     *
     * Disk access is performed on [Dispatchers.IO].
     *
     * @throws IllegalArgumentException when [sourceType] is not [SourceType.NAVER_IMAP]
     *   or [SourceType.DAUM_IMAP].
     */
    public suspend fun load(sourceType: String): ImapCredentials? =
        withContext(ioDispatcher) {
            require(sourceType in ALLOWED_IMAP_SOURCES) {
                "unknown IMAP sourceType: $sourceType"
            }
            val username = prefs.getString(key(sourceType, SUFFIX_USERNAME), null)
                ?: return@withContext null
            val appPassword = prefs.getString(key(sourceType, SUFFIX_PASSWORD), null)
                ?: return@withContext null
            val host = prefs.getString(key(sourceType, SUFFIX_HOST), null)
                ?: return@withContext null
            // getInt has no sentinel "absent"; a namespace with the 3 string keys present
            // always has the port key too (save() writes all 4 atomically). Use 0 as a
            // defensive fallback that the worker will treat as invalid.
            val port = prefs.getInt(key(sourceType, SUFFIX_PORT), 0)
            ImapCredentials(
                username = username,
                appPassword = appPassword,
                host = host,
                port = port,
            )
        }

    /**
     * Removes the 4 keys that make up the [sourceType] namespace, leaving every other
     * provider's slot untouched.
     *
     * Disk access is performed on [Dispatchers.IO].
     *
     * @throws IllegalArgumentException when [sourceType] is not [SourceType.NAVER_IMAP]
     *   or [SourceType.DAUM_IMAP].
     */
    public suspend fun clear(sourceType: String): Unit = withContext(ioDispatcher) {
        require(sourceType in ALLOWED_IMAP_SOURCES) {
            "unknown IMAP sourceType: $sourceType"
        }
        // Synchronous [commit] for the same rationale as [clearAll]: per-provider
        // clear is also a session-boundary barrier (account switch, IMAP disconnect UI),
        // so the write must be durable before the caller returns.
        val committed = prefs.edit()
            .remove(key(sourceType, SUFFIX_USERNAME))
            .remove(key(sourceType, SUFFIX_PASSWORD))
            .remove(key(sourceType, SUFFIX_HOST))
            .remove(key(sourceType, SUFFIX_PORT))
            .commit()
        if (!committed) {
            // Fail loudly so the AuthRepository signOut flow surfaces the wipe
            // failure as [BecalmResult.Failure] rather than reporting success while
            // the previous account's credential remains on disk (ING-011 / PIPA
            // cross-account leak barrier).
            Timber.w(
                "ImapCredentialStore: clear commit returned false sourceType=%s — propagating as IOException",
                sourceType,
            )
            throw IOException(
                "ImapCredentialStore.clear($sourceType) commit failed — " +
                    "on-disk credential may still be present",
            )
        }
        Timber.d("ImapCredentialStore: credentials cleared sourceType=%s", sourceType)
    }

    /**
     * Removes every IMAP credential on disk — called during PIPA sign-out wipe
     * (AUTH-005) and session invalidate.
     *
     * Wipes the backing [SharedPreferences] file wholesale via `clear()` rather than
     * enumerating individual keys. Rationale: this file is dedicated to IMAP
     * credentials (no other feature writes to `becalm_imap_credentials`), AND this
     * wave introduces both a pre-refactor legacy layout (`imap_username`,
     * `imap_app_password`, `imap_host`, `imap_port`) and the new per-provider
     * namespace (`naver_imap_*` / `daum_imap_*`). An enumerate-and-remove approach
     * that only knew about [ALLOWED_IMAP_SOURCES] would miss the legacy tuple on
     * upgraded devices whose migration has not yet finalised — the legacy keys would
     * then survive sign-out and the migrator on the next user's session could revive
     * them into a namespaced slot, producing a cross-account credential leak.
     * `clear()` is unconditional and future-proof against any additional keys that
     * might be added in later waves.
     *
     * **Uses `commit()` (synchronous) intentionally** — this is the cross-account
     * leak barrier for per-account IMAP credentials. `apply()` would return before
     * the encrypted-preferences write hits disk, so a process death between sign-out
     * return and the flush would leave the previous user's app-password on disk.
     *
     * Logs when `commit()` returns false so operators can correlate the rare
     * Keystore-damage / disk-failure path; the sign-out caller continues regardless
     * because there is no useful recovery beyond a fresh reinstall.
     *
     * Disk access is performed on [Dispatchers.IO].
     */
    public suspend fun clearAll(): Unit = withContext(ioDispatcher) {
        val committed = prefs.edit().clear().commit()
        if (!committed) {
            // Fail loudly — swallowing a commit failure here would let
            // [com.becalm.android.data.repository.AuthRepository.signOut] /
            // [AuthRepository.invalidateSession] report a clean logout while the
            // previous user's IMAP app-password remained on disk, breaking the
            // account-isolation barrier (ING-011 / PIPA invariant 153).
            Timber.w(
                "ImapCredentialStore: clearAll commit returned false — Keystore or disk " +
                    "write failed; propagating as IOException",
            )
            throw IOException(
                "ImapCredentialStore.clearAll commit failed — " +
                    "on-disk IMAP credentials may still be present",
            )
        }
        Timber.d("ImapCredentialStore: all credentials cleared (legacy + namespaced)")
    }

    /**
     * Builds the SharedPreferences key for a given [sourceType] and [suffix].
     *
     * Example: `key("naver_imap", "host")` → `"naver_imap_host"`.
     */
    private fun key(sourceType: String, suffix: String): String = "${sourceType}_$suffix"
}
