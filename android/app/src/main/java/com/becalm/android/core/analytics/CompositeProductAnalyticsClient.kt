package com.becalm.android.core.analytics

import com.becalm.android.BuildConfig
import com.becalm.android.core.di.ApplicationScope
import com.becalm.android.core.observability.ObservabilityClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
public class CompositeProductAnalyticsClient(
    private val amplitude: AmplitudeProductAnalyticsClient,
    private val backendMirror: BackendProductEventsMirrorClient,
    private val eventQueue: ProductAnalyticsEventQueue,
    private val observability: ObservabilityClient,
    private val analyticsContext: ProductAnalyticsContext,
    applicationScope: CoroutineScope,
    private val telemetryEnabled: Boolean,
) : ProductAnalyticsClient {

    private val events = Channel<ProductAnalyticsEvent>(capacity = Channel.UNLIMITED)

    @Inject
    public constructor(
        amplitude: AmplitudeProductAnalyticsClient,
        backendMirror: BackendProductEventsMirrorClient,
        eventQueue: ProductAnalyticsEventQueue,
        observability: ObservabilityClient,
        analyticsContext: ProductAnalyticsContext,
        @ApplicationScope applicationScope: CoroutineScope,
    ) : this(
        amplitude = amplitude,
        backendMirror = backendMirror,
        eventQueue = eventQueue,
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
        if (events.trySend(enriched).isFailure) {
            observability.addBreadcrumb("analytics", "product_event_enqueue_failed", mapOf("event_name" to enriched.eventName))
        }
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
        flushQueued()
        while (currentCoroutineContext().isActive) {
            val first = withTimeoutOrNull(FLUSH_INTERVAL_MILLIS) { events.receive() }
            if (first == null) {
                flushQueued()
                continue
            }
            val pending = mutableListOf(first)
            while (pending.size < BACKEND_BATCH_SIZE) {
                val next = events.tryReceive().getOrNull() ?: break
                pending += next
            }
            pending.forEach(::sendToAmplitude)
            enqueuePending(pending)
            flushQueued()
        }
        flushQueued()
    }

    private suspend fun enqueuePending(pending: List<ProductAnalyticsEvent>) {
        if (pending.isEmpty()) return
        runCatching { eventQueue.enqueue(pending) }
            .onFailure {
                observability.addBreadcrumb("analytics", "product_event_queue_persist_failed", mapOf("batch_size" to pending.size.toString()))
            }
    }

    private suspend fun flushQueued() {
        while (currentCoroutineContext().isActive) {
            val batch = runCatching { eventQueue.peek(BACKEND_BATCH_SIZE) }
                .onFailure {
                    observability.addBreadcrumb("analytics", "product_event_queue_read_failed", emptyMap())
                }
                .getOrDefault(emptyList())
            if (batch.isEmpty()) return
            val success = flushBatch(batch)
            if (!success) return
            runCatching { eventQueue.remove(batch.map { it.eventId }.toSet()) }
                .onFailure {
                    observability.addBreadcrumb("analytics", "product_event_queue_remove_failed", mapOf("batch_size" to batch.size.toString()))
                    return
                }
            if (batch.size < BACKEND_BATCH_SIZE) return
        }
    }

    private suspend fun flushBatch(batch: List<ProductAnalyticsEvent>): Boolean {
        if (batch.isEmpty()) return true
        return runCatching { backendMirror.flush(batch) }
            .onFailure {
                observability.addBreadcrumb("analytics", "backend_mirror_failed", mapOf("batch_size" to batch.size.toString()))
            }
            .onSuccess { success ->
                if (!success) {
                    observability.addBreadcrumb("analytics", "backend_mirror_rejected", mapOf("batch_size" to batch.size.toString()))
                }
            }
            .getOrDefault(false)
    }

    private fun sendToAmplitude(event: ProductAnalyticsEvent) {
        runCatching { amplitude.track(event) }
            .onFailure {
                observability.addBreadcrumb("analytics", "amplitude_track_failed", mapOf("event_name" to event.eventName))
            }
    }

    private companion object {
        private const val BACKEND_BATCH_SIZE = 20
        private const val FLUSH_INTERVAL_MILLIS = 30_000L
    }
}
