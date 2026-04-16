package com.becalm.android.data.local.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent store for device-bound key material, isolated from OAuth session tokens.
 *
 * ## Scope
 * This store currently manages a single field — [getOrCreateDeviceId] — which provides a stable
 * UUID v4 identifier for this app installation. The UUID is **not** cryptographic key material;
 * it is a 128-bit random tag used for server-side device enrollment and analytics correlation.
 * Client-side encryption of sensitive fields is out of scope for R2 and is not required by the
 * current data-model spec (see `.spec/data-model.yml` — no E2E-encrypted columns).
 *
 * Future additions (device-bound JWT signing keys, WebAuthn credential handles) will be added
 * here without touching [EncryptedTokenStore]; when that happens, the KDoc on the new accessor
 * must document the key scheme and lifecycle explicitly.
 *
 * ## Security model
 * Uses a dedicated [EncryptedSharedPreferences] file (`becalm_device_keys`) backed by the same
 * Android Keystore infrastructure as [EncryptedTokenStore]. Separating session tokens and device
 * key material into distinct files limits the blast radius of any single file corruption event:
 * a corrupted device-keys file does not affect cached OAuth tokens, and vice versa.
 *
 * Key encryption: AES-256-SIV (deterministic).
 * Value encryption: AES-256-GCM (probabilistic, fresh nonce per write).
 * Master key: AES-256-GCM, stored in the Android Keystore (`AndroidKeyStore` provider),
 * hardware-backed on devices with a Secure Element or TEE.
 *
 * ## Keystore damage recovery
 * Construction failures (Keystore reset, storage corruption) are handled identically to
 * [EncryptedTokenStore]: wipe the preference file on first failure, rebuild once, rethrow on
 * second failure. A regenerated device ID is acceptable because device-bound material is
 * re-enrolled server-side on next use; no user data is permanently lost.
 *
 * ## Thread safety
 * All disk I/O is dispatched on [Dispatchers.IO]. [EncryptedSharedPreferences] in-memory
 * visibility guarantees hold after `apply()` returns; no additional locking is required.
 */
@Singleton
public class DeviceKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private companion object {
        const val FILE_NAME = "becalm_device_keys"

        // Distinct from EncryptedTokenStore's alias. Sharing MasterKey.DEFAULT_MASTER_KEY_ALIAS
        // across stores means a damage-recovery rebuild in one store invalidates the key the
        // other store is using, corrupting both files.
        const val MASTER_KEY_ALIAS = "becalm_device_key_store_master_key"

        const val KEY_DEVICE_ID = "device_id"
    }

    private val prefs: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createPrefs()
    }

    /**
     * Returns the stable device identifier for this app installation, generating and persisting
     * a new UUID v4 value on first access.
     *
     * The ID is stable across app restarts and across [SupabaseSessionStore] clears — device
     * identity is independent of user session identity. The ID is erased only by [clear] or by
     * a Keystore damage recovery event (see class KDoc).
     *
     * Disk access is performed on [Dispatchers.IO].
     *
     * @return A UUID v4 string identifying this app installation.
     */
    public suspend fun getOrCreateDeviceId(): String = withContext(Dispatchers.IO) {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return@withContext existing

        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        Timber.d("DeviceKeyStore: device ID generated")
        newId
    }

    /**
     * Removes all device-bound key material from encrypted storage.
     *
     * Called during full-wipe scenarios (e.g. factory reset flow within the app). Subsequent
     * calls to [getOrCreateDeviceId] will generate a fresh device ID.
     *
     * Disk access is performed on [Dispatchers.IO].
     */
    public suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        // Editor.clear() drops every entry atomically. When future key material (signing keys,
        // WebAuthn handles) is added, there is no "remember to mirror into clear()" footgun.
        prefs.edit().clear().apply()
        Timber.d("DeviceKeyStore: device key material cleared")
    }

    /**
     * Constructs the [EncryptedSharedPreferences] instance.
     *
     * Wipes and rebuilds once on Keystore or file corruption. Rethrows on a second consecutive
     * failure — the device Keystore is broken beyond in-process recovery.
     */
    private fun createPrefs(): SharedPreferences = try {
        buildEncryptedPrefs()
    } catch (t: Throwable) {
        Timber.w(t, "DeviceKeyStore: master key or prefs unavailable; wiping and rebuilding")
        context.deleteSharedPreferences(FILE_NAME)
        buildEncryptedPrefs()
    }

    private fun buildEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
