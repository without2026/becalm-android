package com.becalm.android.auth

import android.content.SharedPreferences
import com.becalm.android.di.AuthPrefs
import javax.inject.Inject
import javax.inject.Singleton

// spec: AUTH-001 — access_token + refresh_token stored in EncryptedSharedPreferences
// spec: AUTH-004 — update both tokens on refresh
// spec: AUTH-005 — delete tokens on logout
// Invariant: tokens never stored in plaintext

@Singleton
class TokenStore @Inject constructor(
    @AuthPrefs private val prefs: SharedPreferences
) {
    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID = "user_id"
    }

    // spec: AUTH-001 — store tokens after successful login
    fun saveTokens(accessToken: String, refreshToken: String, userId: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_USER_ID, userId)
            .apply()
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)

    fun getBearerHeader(): String? = getAccessToken()?.let { "Bearer $it" }

    fun isLoggedIn(): Boolean = getAccessToken() != null

    // spec: AUTH-004 — update both tokens on successful refresh
    fun updateTokens(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()
    }

    // spec: AUTH-005 — delete tokens on logout; Room DB is NOT deleted
    fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_ID)
            .apply()
    }
}
