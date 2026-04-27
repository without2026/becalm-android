package com.becalm.android.data.local.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import com.becalm.android.core.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google OAuth credential triple persisted in [OAuthCredentialStore].
 *
 * [refreshToken] is nullable because [com.google.android.gms.auth.api.identity.AuthorizationClient]
 * does not expose a long-lived refresh token to the app — silent re-authorization is delegated
 * to the Credential Manager layer on the device. The field is retained for future-compat with
 * providers that do emit a refresh token.
 *
 * [scope] is enforced to the single MVP scope ([OAuthCredentialStore.GMAIL_READONLY_SCOPE])
 * on write, and validated on read — a mismatched stored scope causes [OAuthCredentialStore.loadGoogle]
 * to return `null` and self-clear the keys.
 *
 * [accountEmail] pins the Google account on every subsequent
 * `AuthorizationRequest.Builder.setAccount(...)` so a multi-account device cannot
 * silently resolve `refreshSilently` against a different mailbox than the one the
 * user originally connected. `null` when the initial sign-in path did not capture
 * an email (legacy upgrade case); the provider falls through to Play Services'
 * default-account resolution in that case and the UI onboarding PR will backfill
 * this field once the Credential Manager account-picker flow lands.
 *
 * @param accessToken          Google OAuth2 bearer token for Gmail API.
 * @param refreshToken         Long-lived refresh token when the provider emits one; null otherwise.
 * @param expiresAtEpochMillis Epoch millisecond timestamp when [accessToken] ceases to be valid.
 * @param scope                Space-separated OAuth scope string granted by the authorization result.
 * @param accountEmail         Google account email the credential is pinned to, or `null`.
 */
public data class GoogleOAuthCredential(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMillis: Long,
    val scope: String,
    val accountEmail: String? = null,
)

/**
 * Encrypted store for the remaining device-side Google OAuth credential cache.
 *
 * ## Security model
 * Uses a dedicated [androidx.security.crypto.EncryptedSharedPreferences] file
 * (`becalm_oauth_credentials`) backed by the Android Keystore. The master key alias
 * (`becalm_oauth_credential_store_master_key`) is **distinct** from those used by
 * [EncryptedTokenStore] (Supabase session) and [ImapCredentialStore] (Naver/Daum IMAP)
 * to limit blast radius from individual file corruption events.
 *
 * Key encryption: AES-256-SIV (deterministic).
 * Value encryption: AES-256-GCM (probabilistic, fresh nonce per write).
 *
 * ## Namespace isolation
 * Preference keys are provider-namespaced with a `google_*` prefix so provider-specific
 * sign-outs do not clobber unrelated secure state.
 *
 * [clearGoogle] removes only the `google_*` keys. A global clear (e.g. account deletion)
 * lives outside this class today — future PR can add `clearAll()` if needed.
 *
 * ## PIPA invariant (spec 153)
 * `.spec/data-ingestion.spec.yml:153` requires that any locally persisted third-party OAuth
 * tokens live in the Android Keystore only and are never transmitted to Railway.
 * [EncryptedTokenStore] is restricted to Supabase session fields; this store owns the
 * remaining device-side Google credential cache kept for logout hygiene and legacy upgrades.
 *
 * ## Scope lock (ING-006 MVP)
 * Gmail scope is hard-coded to [GMAIL_READONLY_SCOPE]. On read, if the persisted
 * `google_scope` differs (e.g. a future build wrote a broader scope and then got rolled
 * back), [loadGoogle] returns `null` and self-clears the `google_*` keys so the caller
 * treats the session as unauthenticated and the UI re-prompts for a fresh
 * single-scope consent.
 *
 * ## Keystore damage recovery
 * On Keystore or file corruption the preference file is wiped and rebuilt once via
 * [buildStorePrefs]. A second consecutive failure re-throws — the device Keystore is
 * broken beyond recovery.
 *
 * ## Thread safety
 * All disk I/O is dispatched on [kotlinx.coroutines.Dispatchers.IO]. Construction is
 * lazy and synchronized.
 */
@Singleton
public class OAuthCredentialStore {

    private val ioDispatcher: CoroutineDispatcher
    private val prefsProvider: () -> SharedPreferences

    /**
     * Production constructor — Hilt resolves [context] to the application context and
     * builds encrypted prefs lazily on first access.
     */
    @Inject
    public constructor(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) {
        this.ioDispatcher = ioDispatcher
        // Lazily construct the encrypted prefs so that construction failure (Keystore damage)
        // surfaces on first disk access, not on class construction. Mirrors the pattern used
        // in [ImapCredentialStore] / [EncryptedTokenStore].
        val lazyPrefs = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            buildStorePrefs(context, FILE_NAME, MASTER_KEY_ALIAS, "OAuthCredentialStore")
        }
        this.prefsProvider = { lazyPrefs.value }
    }

    /**
     * Test-only constructor — accepts a pre-built [SharedPreferences] so Robolectric
     * tests can exercise the store against an in-memory `getSharedPreferences(...)`
     * without touching the Keystore-backed [buildStorePrefs] path.
     */
    @VisibleForTesting
    internal constructor(
        prefs: SharedPreferences,
        ioDispatcher: CoroutineDispatcher,
    ) {
        this.ioDispatcher = ioDispatcher
        this.prefsProvider = { prefs }
    }

    private val prefs: SharedPreferences
        get() = prefsProvider()

    public companion object {
        internal const val FILE_NAME: String = "becalm_oauth_credentials"

        // Distinct from EncryptedTokenStore ("becalm_token_store_master_key"),
        // DeviceKeyStore ("becalm_device_key_store_master_key"),
        // and ImapCredentialStore ("becalm_imap_credential_store_master_key").
        internal const val MASTER_KEY_ALIAS: String = "becalm_oauth_credential_store_master_key"

        // google_* namespace.
        internal const val KEY_GOOGLE_ACCESS_TOKEN: String = "google_access_token"
        internal const val KEY_GOOGLE_REFRESH_TOKEN: String = "google_refresh_token"
        internal const val KEY_GOOGLE_TOKEN_EXPIRES_AT: String = "google_token_expires_at"
        internal const val KEY_GOOGLE_SCOPE: String = "google_scope"

        /**
         * Email of the Google account this credential belongs to.
         *
         * This remains for legacy compatibility with device-side Google OAuth caches.
         * Backend-managed Gmail no longer refreshes through a local provider class, but
         * sign-out cleanup and older upgraded installs may still carry this field.
         */
        internal const val KEY_GOOGLE_ACCOUNT_EMAIL: String = "google_account_email"

        /**
         * The one and only Gmail OAuth scope permitted by ING-006 MVP
         * (`.spec/data-ingestion.spec.yml:62`). Any authorization result granting a
         * different scope string is rejected by [loadGoogle] and self-cleared.
         */
        public const val GMAIL_READONLY_SCOPE: String =
            "https://www.googleapis.com/auth/gmail.readonly"

    }

    /**
     * Returns the persisted [GoogleOAuthCredential], or `null` when:
     * - no credentials have been saved yet,
     * - any of [KEY_GOOGLE_ACCESS_TOKEN] / [KEY_GOOGLE_TOKEN_EXPIRES_AT] / [KEY_GOOGLE_SCOPE]
     *   is absent, or
     * - the persisted scope differs from [GMAIL_READONLY_SCOPE] (in which case the
     *   stale `google_*` keys are cleared as a self-defense measure).
     *
     * Disk access is performed on [kotlinx.coroutines.Dispatchers.IO].
     */
    public suspend fun loadGoogle(): GoogleOAuthCredential? = withContext(ioDispatcher) {
        val accessToken = prefs.getString(KEY_GOOGLE_ACCESS_TOKEN, null) ?: return@withContext null
        val scope = prefs.getString(KEY_GOOGLE_SCOPE, null) ?: return@withContext null
        if (!prefs.contains(KEY_GOOGLE_TOKEN_EXPIRES_AT)) return@withContext null
        val expiresAt = prefs.getLong(KEY_GOOGLE_TOKEN_EXPIRES_AT, 0L)

        if (scope != GMAIL_READONLY_SCOPE) {
            Timber.w(
                "OAuthCredentialStore: stored google_scope=%s differs from expected " +
                    "gmail.readonly — clearing",
                scope,
            )
            clearGoogleKeysLocked()
            return@withContext null
        }

        val refreshToken = prefs.getString(KEY_GOOGLE_REFRESH_TOKEN, null)
        val accountEmail = prefs.getString(KEY_GOOGLE_ACCOUNT_EMAIL, null)
        GoogleOAuthCredential(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochMillis = expiresAt,
            scope = scope,
            accountEmail = accountEmail,
        )
    }

    /**
     * Persists [credential] atomically. All four `google_*` keys are written in a single
     * [SharedPreferences.Editor] transaction so a crash between `put` calls cannot leave
     * a half-written credential pair on disk.
     *
     * Disk access is performed on [kotlinx.coroutines.Dispatchers.IO].
     */
    public suspend fun saveGoogle(credential: GoogleOAuthCredential): Unit = withContext(ioDispatcher) {
        val editor = prefs.edit()
            .putString(KEY_GOOGLE_ACCESS_TOKEN, credential.accessToken)
            .putLong(KEY_GOOGLE_TOKEN_EXPIRES_AT, credential.expiresAtEpochMillis)
            .putString(KEY_GOOGLE_SCOPE, credential.scope)
        // Null refresh tokens are modelled by removing the key rather than writing
        // a literal "null" string that loadGoogle() would then re-read.
        if (credential.refreshToken != null) {
            editor.putString(KEY_GOOGLE_REFRESH_TOKEN, credential.refreshToken)
        } else {
            editor.remove(KEY_GOOGLE_REFRESH_TOKEN)
        }
        if (credential.accountEmail != null) {
            editor.putString(KEY_GOOGLE_ACCOUNT_EMAIL, credential.accountEmail)
        } else {
            editor.remove(KEY_GOOGLE_ACCOUNT_EMAIL)
        }
        editor.apply()
        Timber.d(
            "OAuthCredentialStore: google credential saved, expires=%d accountPinned=%b",
            credential.expiresAtEpochMillis,
            credential.accountEmail != null,
        )
    }

    /**
     * Removes only the `google_*` keys in this preference file.
     *
     * Disk access is performed on [kotlinx.coroutines.Dispatchers.IO].
     */
    public suspend fun clearGoogle(): Unit = withContext(ioDispatcher) {
        clearGoogleKeysLocked()
        Timber.d("OAuthCredentialStore: google credentials cleared")
    }

    /**
     * Removes only the bearer-token fields (`access_token`, `refresh_token`, `expires_at`,
     * `scope`) while intentionally preserving [KEY_GOOGLE_ACCOUNT_EMAIL]. Used by the
     * expiry-driven reauth path: when a cached credential ages into the safety window
     * we must invalidate the token so `currentToken()` returns null and downstream
     * code transitions to reauth, but we must NOT lose the mailbox pin — otherwise the
     * subsequent silent refresh would resolve account-agnostically and could drift onto
     * a different Google account on a multi-account device (Gmail mailbox-swap guard).
     *
     * Uses `commit()` for the same fail-loudly rationale as [clearGoogleKeysLocked].
     * Disk access is performed on [kotlinx.coroutines.Dispatchers.IO].
     */
    public suspend fun clearGoogleTokenPreservingPin(): Unit = withContext(ioDispatcher) {
        val committed = prefs.edit()
            .remove(KEY_GOOGLE_ACCESS_TOKEN)
            .remove(KEY_GOOGLE_REFRESH_TOKEN)
            .remove(KEY_GOOGLE_TOKEN_EXPIRES_AT)
            .remove(KEY_GOOGLE_SCOPE)
            .commit()
        if (!committed) {
            Timber.w(
                "OAuthCredentialStore: clearGoogleTokenPreservingPin commit returned false — " +
                    "propagating as IOException",
            )
            throw IOException(
                "OAuthCredentialStore.clearGoogleTokenPreservingPin commit failed — " +
                    "on-disk token may still be present",
            )
        }
        Timber.d("OAuthCredentialStore: google token cleared; account pin preserved")
    }

    /**
     * Unsynchronised helper that removes the four `google_*` keys. Called from both
     * [clearGoogle] and the self-defense branch of [loadGoogle]. The caller is
     * responsible for dispatcher discipline.
     *
     * **Uses `commit()` (synchronous) intentionally** — this is the cross-account leak
     * barrier called from `AuthRepository.signOut` / `invalidateSession` and from the
     * scope-mismatch self-defense branch. `apply()` returns before the write is
     * flushed, so a process death between sign-out return and disk write would leave
     * the previous user's Gmail bearer token on disk; the next
     * `BecalmApplication.warmUp()` would then republish that token for the next user's
     * session (PIPA cross-account invariant breach, spec 153).
     *
     * Logs when `commit()` returns false so operators / Sentry can correlate rare
     * Keystore damage cases; the sign-out flow continues regardless because there is
     * no meaningful recovery path beyond a fresh reinstall.
     */
    private fun clearGoogleKeysLocked() {
        val committed = prefs.edit()
            .remove(KEY_GOOGLE_ACCESS_TOKEN)
            .remove(KEY_GOOGLE_REFRESH_TOKEN)
            .remove(KEY_GOOGLE_TOKEN_EXPIRES_AT)
            .remove(KEY_GOOGLE_SCOPE)
            .remove(KEY_GOOGLE_ACCOUNT_EMAIL)
            .commit()
        if (!committed) {
            // Fail loudly — swallowing a commit failure here would let
            // [com.becalm.android.data.repository.AuthRepository.signOut] report a
            // successful wipe while the previous user's Gmail bearer token remained on
            // disk. The PIPA cross-account-leak invariant (spec 153) requires callers
            // to see the failure, refuse to complete sign-out, and surface an error to
            // the UI so the user can retry (or the device escalates to a reinstall).
            Timber.w(
                "OAuthCredentialStore: google credential clear commit returned false — " +
                    "Keystore or disk write failed; propagating as IOException",
            )
            throw IOException(
                "OAuthCredentialStore.clearGoogle commit failed — on-disk credential may still be present",
            )
        }
    }

}
