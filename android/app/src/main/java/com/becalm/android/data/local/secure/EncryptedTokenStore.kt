package com.becalm.android.data.local.secure

import android.content.Context
import android.content.SharedPreferences
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [SupabaseSessionStore] that persists OAuth tokens in
 * [EncryptedSharedPreferences] backed by the Android Keystore.
 *
 * ## Security model
 * - **Master key**: AES-256-GCM, stored in the Android Keystore system partition
 *   (`AndroidKeyStore` provider). On devices with a dedicated Secure Element (e.g. Samsung
 *   StrongBox, Google Titan M), the key material never leaves hardware. On devices without
 *   hardware security, it resides in the Trusted Execution Environment (TEE). The app process
 *   never holds raw key bytes.
 * - **Preference keys** are encrypted with AES-256-SIV (deterministic; required so the
 *   [SharedPreferences] lookup-by-key contract is preserved without leaking value correlations).
 * - **Preference values** are encrypted with AES-256-GCM (probabilistic; each write produces a
 *   fresh nonce, preventing value-comparison side-channel attacks).
 * - Tokens are stored as discrete key/value pairs (one key per field) rather than a serialized
 *   JSON blob. This prevents the shape of the data model from being inferred by observing the
 *   encrypted blob size.
 * - Token values are **never logged**. Only non-sensitive metadata (e.g. expiry epoch) is
 *   emitted at DEBUG level via Timber.
 *
 * ## Why EncryptedSharedPreferences over DataStore
 * Jetpack DataStore 1.x does not ship a stable encrypted counterpart (`EncryptedDataStore` is an
 * open feature request as of 2026-04). [EncryptedSharedPreferences] is the production-tested
 * standard recommended in the Android Security documentation and used by apps such as Google Pay.
 * DataStore is used for non-secret user preferences (SP-14 scope); this class is the exclusive
 * owner of credential storage.
 *
 * ## Keystore damage recovery
 * If the master key or the encrypted preference file becomes unreadable (e.g. after a factory
 * reset of the Keystore following device rooting, OS upgrade failure, or storage corruption),
 * construction of [EncryptedSharedPreferences] throws. This store detects that scenario, wipes
 * the preference file, and attempts one rebuild. If the second attempt also fails the exception
 * propagates to crash reporting — the device is genuinely broken and the user must re-authenticate
 * after reinstalling the app. See [buildStorePrefs] for the implementation.
 *
 * ## Thread safety
 * [EncryptedSharedPreferences] guarantees in-memory read visibility immediately after
 * `SharedPreferences.Editor.apply()` returns (per the [SharedPreferences] contract). Concurrent
 * reads therefore always observe the most recently committed write without additional locking in
 * this class. All disk I/O is dispatched on [Dispatchers.IO] to avoid blocking the main thread.
 *
 * AUTH-004 invariant: no plaintext token is ever written outside this class.
 */
@Singleton
public class EncryptedTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SupabaseSessionStore {

    private companion object {
        const val FILE_NAME = "becalm_secure_tokens"

        // Distinct from DeviceKeyStore's alias. Sharing MasterKey.DEFAULT_MASTER_KEY_ALIAS across
        // stores means a damage-recovery rebuild in one store invalidates the key the other store
        // is using, corrupting both files.
        const val MASTER_KEY_ALIAS = "becalm_token_store_master_key"

        // Preference keys — one per SupabaseSession field.
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_EMAIL = "email"
        const val KEY_EXPIRES_AT_EPOCH_MILLIS = "expires_at_epoch_millis"

        // Sentinel value stored for the email field when the original value was an empty string.
        // SupabaseSession.email is non-nullable (String); an empty string is a valid, expected
        // value (e.g. OAuth providers that do not return an email). No null sentinel is required.
    }

    // Lazily constructed so that the first disk access is always off the main thread.
    // Every public accessor wraps `prefs` access in withContext(Dispatchers.IO), so the lazy
    // initializer itself runs on IO on first access — the lazy block does not switch dispatchers.
    // Double-checked locking is handled by Kotlin's lazy(LazyThreadSafetyMode.SYNCHRONIZED).
    private val prefs: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        buildStorePrefs(context, FILE_NAME, MASTER_KEY_ALIAS, "EncryptedTokenStore")
    }

    // No replay: late subscribers do not retroactively receive prior sessions.
    // DROP_OLDEST: a writer never suspends on a full buffer; at most the most recent emission
    // is retained for a subscriber that has not yet resumed.
    private val sessionChanges = MutableSharedFlow<SupabaseSession?>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Loads the persisted [SupabaseSession], or returns `null` if no complete session exists.
     *
     * Returns `null` on first launch, after [clear], or if any mandatory field is absent.
     * Disk access is performed on [Dispatchers.IO].
     */
    override suspend fun load(): SupabaseSession? = withContext(ioDispatcher) {
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return@withContext null
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return@withContext null
        val userId = prefs.getString(KEY_USER_ID, null) ?: return@withContext null
        val email = prefs.getString(KEY_EMAIL, null) ?: return@withContext null
        if (!prefs.contains(KEY_EXPIRES_AT_EPOCH_MILLIS)) return@withContext null
        val expiresAtMillis = prefs.getLong(KEY_EXPIRES_AT_EPOCH_MILLIS, 0L)

        Timber.d("EncryptedTokenStore: session loaded, expires=%d", expiresAtMillis)

        SupabaseSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = userId,
            email = email,
            expiresAt = Instant.fromEpochMilliseconds(expiresAtMillis),
        )
    }

    /**
     * Persists [session] to encrypted storage, replacing any previously saved session.
     *
     * All fields are written in a single [SharedPreferences.Editor] transaction and committed
     * with `apply()` (async disk write; in-memory visibility is immediate). Disk access is
     * dispatched on [Dispatchers.IO].
     */
    override suspend fun save(session: SupabaseSession): Unit = withContext(ioDispatcher) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_EMAIL, session.email)
            .putLong(KEY_EXPIRES_AT_EPOCH_MILLIS, session.expiresAt.toEpochMilliseconds())
            .apply()
        Timber.d("EncryptedTokenStore: session saved, expires=%d", session.expiresAt.toEpochMilliseconds())
        // Emit AFTER apply() so subscribers never observe a session that isn't yet persisted.
        sessionChanges.tryEmit(session)
    }

    /**
     * Removes all persisted session data from encrypted storage.
     *
     * Called by `AuthRepository` (SP-16) as part of the sign-out wipe sequence, satisfying the
     * PIPA right-to-erasure obligation for credential data. Disk access is performed on
     * [Dispatchers.IO].
     */
    override suspend fun clear(): Unit = withContext(ioDispatcher) {
        // Editor.clear() drops every entry atomically, so a future SupabaseSession field that
        // forgets to mirror itself into an explicit .remove() cannot silently survive a PIPA
        // right-to-erasure wipe.
        prefs.edit().clear().apply()
        Timber.d("EncryptedTokenStore: session cleared")
        // Emit AFTER apply() so subscribers never observe a cleared state that isn't persisted.
        sessionChanges.tryEmit(null)
    }

    override fun observe(): SharedFlow<SupabaseSession?> = sessionChanges.asSharedFlow()
}
