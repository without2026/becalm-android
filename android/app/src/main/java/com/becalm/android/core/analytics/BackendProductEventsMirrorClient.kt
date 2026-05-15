package com.becalm.android.core.analytics

import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.ProductEventDto
import com.becalm.android.data.remote.dto.ProductEventsBatchRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
public class BackendProductEventsMirrorClient @Inject constructor(
    private val railwayApi: RailwayApi,
) {
    public suspend fun flush(events: List<ProductAnalyticsEvent>): Boolean {
        if (events.isEmpty()) return true
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
}
