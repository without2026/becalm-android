package com.becalm.android.core.analytics

import com.becalm.android.BuildConfig
import com.becalm.android.core.di.ApplicationScope
import com.becalm.android.core.observability.ObservabilityClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
public class CompositeProductAnalyticsClient(
    private val amplitude: AmplitudeProductAnalyticsClient,
    private val backendMirror: BackendProductEventsMirrorClient,
    private val observability: ObservabilityClient,
    private val analyticsContext: ProductAnalyticsContext,
    applicationScope: CoroutineScope,
    private val telemetryEnabled: Boolean,
) : ProductAnalyticsClient {

    private val events = Channel<ProductAnalyticsEvent>(
        capacity = CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Inject
    public constructor(
        amplitude: AmplitudeProductAnalyticsClient,
        backendMirror: BackendProductEventsMirrorClient,
        observability: ObservabilityClient,
        analyticsContext: ProductAnalyticsContext,
        @ApplicationScope applicationScope: CoroutineScope,
    ) : this(
        amplitude = amplitude,
        backendMirror = backendMirror,
        observability = observability,
        analyticsContext = analyticsContext,
        applicationScope = applicationScope,
        telemetryEnabled = BuildConfig.TELEMETRY_ENABLED,
    )

    init {
        applicationScope.launch {
            drain()
        }
    }

    override fun track(event: ProductAnalyticsEvent) {
        if (!telemetryEnabled) return
        val enriched = analyticsContext.enrich(event)
        if (!ProductAnalyticsValidation.isValid(enriched)) {
            observability.addBreadcrumb("analytics", "product_event_dropped", mapOf("event_name" to enriched.eventName))
            return
        }
        events.trySend(enriched)
    }

    override fun setUserScope(userId: String?) {
        amplitude.setUserScope(userId)
        observability.setUserScope(userId)
    }

    override fun resetUserScope() {
        amplitude.resetUserScope()
        observability.setUserScope(null)
    }

    private suspend fun drain() {
        val pending = mutableListOf<ProductAnalyticsEvent>()
        while (currentCoroutineContext().isActive) {
            val first = withTimeoutOrNull(FLUSH_INTERVAL_MILLIS) { events.receive() }
            if (first == null) {
                flushPending(pending)
                continue
            }
            sendToAmplitude(first)
            pending += first
            while (pending.size < BACKEND_BATCH_SIZE) {
                val next = events.tryReceive().getOrNull() ?: break
                sendToAmplitude(next)
                pending += next
            }
            if (pending.size >= BACKEND_BATCH_SIZE) {
                flushPending(pending)
            }
        }
        flushPending(pending)
    }

    private fun sendToAmplitude(event: ProductAnalyticsEvent) {
        runCatching { amplitude.track(event) }
            .onFailure {
                observability.addBreadcrumb("analytics", "amplitude_track_failed", mapOf("event_name" to event.eventName))
            }
    }

    private suspend fun flushPending(pending: MutableList<ProductAnalyticsEvent>) {
        if (pending.isEmpty()) return
        val batch = pending.toList()
        pending.clear()
        runCatching { backendMirror.flush(batch) }
            .onFailure {
                observability.addBreadcrumb("analytics", "backend_mirror_failed", mapOf("batch_size" to batch.size.toString()))
            }
            .onSuccess { success ->
                if (!success) {
                    observability.addBreadcrumb("analytics", "backend_mirror_rejected", mapOf("batch_size" to batch.size.toString()))
                }
            }
    }

    private companion object {
        private const val CHANNEL_CAPACITY = 200
        private const val BACKEND_BATCH_SIZE = 20
        private const val FLUSH_INTERVAL_MILLIS = 30_000L
    }
}
