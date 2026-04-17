package com.becalm.android.data.local.secure

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMAP credentials for Naver Mail (or any IMAPS-compatible provider).
 *
 * @param username  IMAP login address (full email address for Naver Mail).
 * @param appPassword  Naver app-password (not the account password — Naver requires a dedicated
 *   app password for IMAP access).
 * @param host  IMAPS server hostname. Defaults to "imap.naver.com".
 * @param port  IMAPS port. Defaults to 993 (implicit TLS).
 */
public data class ImapCredentials(
    val username: String,
    val appPassword: String,
    val host: String = "imap.naver.com",
    val port: Int = 993,
)

/**
 * Encrypted store for IMAP credentials (username + app-password).
 *
 * ## Security model
 * Uses a dedicated [EncryptedSharedPreferences] file (`becalm_imap_credentials`) backed by
 * Android Keystore. The master key alias (`becalm_imap_credential_store_master_key`) is
 * distinct from those used by [EncryptedTokenStore] and [DeviceKeyStore] to limit blast radius
 * from individual file corruption events.
 *
 * Key encryption: AES-256-SIV (deterministic).
 * Value encryption: AES-256-GCM (probabilistic, fresh nonce per write).
 *
 * ## Keystore damage recovery
 * On Keystore or file corruption the preference file is wiped and rebuilt once.
 * A second consecutive failure re-throws — the device Keystore is broken beyond recovery.
 *
 * ## Thread safety
 * All disk I/O is dispatched on [Dispatchers.IO].
 *
 * CRIT-01: replaces plaintext DataStore storage of IMAP app-passwords.
 */
@Singleton
public class ImapCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private companion object {
        const val FILE_NAME = "becalm_imap_credentials"

        // Distinct from EncryptedTokenStore alias ("becalm_token_store_master_key")
        // and DeviceKeyStore alias ("becalm_device_key_store_master_key").
        const val MASTER_KEY_ALIAS = "becalm_imap_credential_store_master_key"

        const val KEY_USERNAME = "imap_username"
        const val KEY_APP_PASSWORD = "imap_app_password"
        const val KEY_HOST = "imap_host"
        const val KEY_PORT = "imap_port"

        const val DEFAULT_HOST = "imap.naver.com"
        const val DEFAULT_PORT = 993
    }

    private val prefs: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        buildStorePrefs(context, FILE_NAME, MASTER_KEY_ALIAS, "ImapCredentialStore")
    }

    /**
     * Returns the stored [ImapCredentials], or `null` when no credentials have been saved.
     *
     * Disk access is performed on [Dispatchers.IO].
     */
    public suspend fun getCredentials(): ImapCredentials? = withContext(Dispatchers.IO) {
        val username = prefs.getString(KEY_USERNAME, null) ?: return@withContext null
        val appPassword = prefs.getString(KEY_APP_PASSWORD, null) ?: return@withContext null
        val host = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        val port = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        ImapCredentials(username = username, appPassword = appPassword, host = host, port = port)
    }

    /**
     * Persists [c], replacing any previously stored credentials.
     *
     * Disk access is performed on [Dispatchers.IO].
     */
    public suspend fun saveCredentials(c: ImapCredentials): Unit = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_USERNAME, c.username)
            .putString(KEY_APP_PASSWORD, c.appPassword)
            .putString(KEY_HOST, c.host)
            .putInt(KEY_PORT, c.port)
            .apply()
        Timber.d("ImapCredentialStore: credentials saved")
    }

    /**
     * Removes all stored IMAP credentials.
     *
     * Called during user sign-out as part of the PIPA right-to-erasure wipe (AUTH-005).
     * Disk access is performed on [Dispatchers.IO].
     */
    public suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
        Timber.d("ImapCredentialStore: credentials cleared")
    }
}
