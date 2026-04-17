package com.becalm.android.data.local.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber

/**
 * Package-internal helper that constructs an [EncryptedSharedPreferences] instance.
 *
 * All three stores in this package ([EncryptedTokenStore], [DeviceKeyStore],
 * [ImapCredentialStore]) require the same AES-256-GCM master key + AES-256-SIV/GCM
 * preference scheme. This function centralises that boilerplate so each store only
 * declares its own [fileName] and [masterKeyAlias] constants.
 *
 * Wipe-and-rebuild-once recovery semantics are preserved here: a Keystore or file
 * corruption exception causes a single retry after deleting the preference file.
 * A second consecutive failure re-throws so crash reporting captures the event.
 *
 * @param context Application context.
 * @param fileName The [EncryptedSharedPreferences] file name for the calling store.
 * @param masterKeyAlias The Android Keystore alias for the calling store's master key.
 * @param logTag Timber tag prefix used in recovery log messages (e.g. "DeviceKeyStore").
 */
internal fun buildStorePrefs(
    context: Context,
    fileName: String,
    masterKeyAlias: String,
    logTag: String,
): SharedPreferences = try {
    buildEncryptedPrefs(context, fileName, masterKeyAlias)
} catch (t: Throwable) {
    Timber.w(t, "$logTag: master key or prefs unavailable; wiping and rebuilding")
    context.deleteSharedPreferences(fileName)
    // Second failure propagates — device Keystore is broken beyond recovery.
    buildEncryptedPrefs(context, fileName, masterKeyAlias)
}

private fun buildEncryptedPrefs(
    context: Context,
    fileName: String,
    masterKeyAlias: String,
): SharedPreferences {
    val masterKey = MasterKey.Builder(context, masterKeyAlias)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        fileName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}
