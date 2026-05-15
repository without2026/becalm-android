package com.becalm.android.core.observability

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
public class CompositeObservabilityClient @Inject constructor(
    private val loggerClient: LoggerObservabilityClient,
    private val crashlyticsClient: CrashlyticsObservabilityClient,
) : ObservabilityClient {

    override fun captureMessage(message: String, tags: ObservabilityTags) {
        fanOut { it.captureMessage(message, tags) }
    }

    override fun captureException(throwable: Throwable, tags: ObservabilityTags) {
        fanOut { it.captureException(throwable, tags) }
    }

    override fun addBreadcrumb(category: String, message: String, data: ObservabilityTags) {
        fanOut { it.addBreadcrumb(category, message, data) }
    }

    override fun setUserScope(userId: String?) {
        fanOut { it.setUserScope(userId) }
    }

    private fun fanOut(block: (ObservabilityClient) -> Unit) {
        for (client in listOf(loggerClient, crashlyticsClient)) {
            runCatching { block(client) }
        }
    }
}
