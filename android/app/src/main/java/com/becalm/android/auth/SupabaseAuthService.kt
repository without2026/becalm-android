package com.becalm.android.auth

import com.becalm.android.BuildConfig
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

// spec: AUTH-001 — email+password login
// spec: AUTH-002 — invalid password error handling
// spec: AUTH-003 — Google OAuth login
// spec: AUTH-004 — refresh token flow
// spec: AUTH-005 — logout
// spec: AUTH-007 — 401 Railway response triggers refresh + 1 retry

sealed class AuthResult {
    data class Success(val accessToken: String, val refreshToken: String, val userId: String) : AuthResult()
    data class Error(val code: Int, val message: String) : AuthResult()
    object NetworkError : AuthResult()
}

@JsonClass(generateAdapter = true)
data class SupabaseTokenResponse(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String,
    val user: SupabaseUser
)

@JsonClass(generateAdapter = true)
data class SupabaseUser(
    val id: String,
    val email: String
)

@JsonClass(generateAdapter = true)
data class SupabaseErrorResponse(
    val error: String?,
    @Json(name = "error_description") val errorDescription: String?,
    val message: String?
)

@Singleton
class SupabaseAuthService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) {
    private val baseUrl = BuildConfig.SUPABASE_URL
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY
    private val json = "application/json; charset=utf-8".toMediaType()

    // spec: AUTH-001 — email+password login
    suspend fun loginWithEmailPassword(email: String, password: String): AuthResult {
        val body = """{"email":"$email","password":"$password"}"""
        return callTokenEndpoint("password", body)
    }

    // spec: AUTH-003 — Google OAuth id_token exchange
    suspend fun loginWithGoogleIdToken(idToken: String): AuthResult {
        val body = """{"id_token":"$idToken","provider":"google"}"""
        return callTokenEndpoint("id_token", body)
    }

    // spec: AUTH-004 — refresh access token
    suspend fun refreshToken(refreshToken: String): AuthResult {
        val body = """{"refresh_token":"$refreshToken"}"""
        return callTokenEndpoint("refresh_token", body)
    }

    // spec: AUTH-005 — logout; returns true if Supabase 204, false if network error
    suspend fun logout(accessToken: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/auth/v1/logout")
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("apikey", anonKey)
                .post("".toRequestBody())
                .build()
            val response = okHttpClient.newCall(request).execute()
            response.code == 204
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun callTokenEndpoint(grantType: String, additionalBody: String): AuthResult {
        return try {
            val bodyJson = """{"grant_type":"$grantType",$additionalBody.trimStart('{').trimEnd('}')}"""
            // Build proper JSON by merging grant_type with the additional fields
            val mergedBody = buildJsonBody(grantType, additionalBody)
            val request = Request.Builder()
                .url("$baseUrl/auth/v1/token?grant_type=$grantType")
                .addHeader("apikey", anonKey)
                .addHeader("Content-Type", "application/json")
                .post(mergedBody.toRequestBody(json))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val bodyString = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val adapter = moshi.adapter(SupabaseTokenResponse::class.java)
                val parsed = adapter.fromJson(bodyString)
                if (parsed != null) {
                    AuthResult.Success(parsed.accessToken, parsed.refreshToken, parsed.user.id)
                } else {
                    AuthResult.Error(response.code, "Invalid response")
                }
            } else {
                // spec: AUTH-002 — 400 invalid_grant
                val errorAdapter = moshi.adapter(SupabaseErrorResponse::class.java)
                val error = runCatching { errorAdapter.fromJson(bodyString) }.getOrNull()
                AuthResult.Error(response.code, error?.errorDescription ?: error?.message ?: "Auth error")
            }
        } catch (e: Exception) {
            AuthResult.NetworkError
        }
    }

    private fun buildJsonBody(grantType: String, additionalBody: String): String {
        // additionalBody is already a JSON object body like {"email":"...","password":"..."}
        val inner = additionalBody.trim().trimStart('{').trimEnd('}')
        return """{"grant_type":"$grantType",$inner}"""
    }
}
