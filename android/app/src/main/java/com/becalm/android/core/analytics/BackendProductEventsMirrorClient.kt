package com.becalm.android.core.analytics

import com.becalm.android.BuildConfig
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.ProductEventDto
import com.becalm.android.data.remote.dto.ProductEventsBatchRequest
import com.becalm.android.data.remote.interceptor.AuthInterceptor
import com.becalm.android.data.remote.interceptor.AuthTokenProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
public class BackendProductEventsMirrorClient @Inject constructor(
    private val railwayApi: RailwayApi,
    private val authTokenProvider: AuthTokenProvider,
) {
    public suspend fun flush(events: List<ProductAnalyticsEvent>): Boolean {
        if (events.isEmpty()) return true
        if (!hasBackendUsableAuthToken()) return true
        val request = ProductEventsBatchRequest(
            events = events.map { event ->
                ProductEventDto(
                    eventId = event.eventId,
                    eventName = event.eventName,
                    occurredAt = event.occurredAt,
                    sessionId = event.sessionId,
                    source = "android",
                    properties = ProductAnalyticsValidation.sanitizedProperties(event.properties),
                )
            },
        )
        val response = railwayApi.batchProductEvents(request)
        return response.isSuccessful
    }

    private fun hasBackendUsableAuthToken(): Boolean {
        val token = authTokenProvider.currentAccessToken().orEmpty()
        return token.isNotBlank() && !token.isDebugLocalOnlyToken()
    }

    private fun String.isDebugLocalOnlyToken(): Boolean =
        BuildConfig.DEBUG &&
            substringAfterLast('.', missingDelimiterValue = "") in DEBUG_LOCAL_ONLY_TOKEN_SIGNATURES

    private companion object {
        private val DEBUG_LOCAL_ONLY_TOKEN_SIGNATURES = setOf(
            AuthInterceptor.DEBUG_LOCAL_ONLY_TOKEN_SIGNATURE,
            "debug",
        )
    }
}
