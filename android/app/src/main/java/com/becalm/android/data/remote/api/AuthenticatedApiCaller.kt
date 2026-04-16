package com.becalm.android.data.remote.api

import com.becalm.android.auth.AuthResult
import com.becalm.android.auth.SupabaseAuthService
import com.becalm.android.auth.TokenStore
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

// spec: AUTH-007 — Railway 401 → refresh → 1 retry → LoginScreen if still 401

sealed class ApiCallResult<out T> {
    data class Success<T>(val data: T) : ApiCallResult<T>()
    data class HttpError(val code: Int, val message: String) : ApiCallResult<Nothing>()
    object Unauthorized : ApiCallResult<Nothing>()   // force logout
    object NetworkError : ApiCallResult<Nothing>()
}

@Singleton
class AuthenticatedApiCaller @Inject constructor(
    private val tokenStore: TokenStore,
    private val authService: SupabaseAuthService
) {
    // spec: AUTH-007 — wraps any Railway API call with 401 refresh+retry logic
    suspend fun <T> call(apiCall: suspend (bearerHeader: String) -> Response<T>): ApiCallResult<T> {
        val token = tokenStore.getBearerHeader()
            ?: return ApiCallResult.Unauthorized

        return try {
            val response = apiCall(token)
            when {
                response.isSuccessful -> ApiCallResult.Success(response.body()!!)
                response.code() == 401 -> {
                    // spec: AUTH-007 — refresh and retry once
                    val refreshToken = tokenStore.getRefreshToken()
                        ?: return ApiCallResult.Unauthorized
                    val refreshResult = authService.refreshToken(refreshToken)
                    if (refreshResult is AuthResult.Success) {
                        tokenStore.updateTokens(refreshResult.accessToken, refreshResult.refreshToken)
                        val retryResponse = apiCall("Bearer ${refreshResult.accessToken}")
                        if (retryResponse.isSuccessful) {
                            ApiCallResult.Success(retryResponse.body()!!)
                        } else {
                            // spec: AUTH-007 — retry also 401 → force logout
                            ApiCallResult.Unauthorized
                        }
                    } else {
                        ApiCallResult.Unauthorized
                    }
                }
                else -> ApiCallResult.HttpError(response.code(), response.message())
            }
        } catch (e: Exception) {
            ApiCallResult.NetworkError
        }
    }
}
