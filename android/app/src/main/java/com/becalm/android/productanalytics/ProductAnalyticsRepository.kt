package com.becalm.android.productanalytics

import com.becalm.android.core.di.ApplicationScope
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.ProductAnalyticsDao
import com.becalm.android.data.local.db.entity.QueuedProductEventEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.ProductAnalyticsBatchRequestDto
import com.becalm.android.data.remote.dto.ProductAnalyticsEventDto
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
public class ProductAnalyticsRepository @Inject constructor(
    private val dao: ProductAnalyticsDao,
    private val api: RailwayApi,
    private val userPrefsStore: UserPrefsStore,
    private val clock: Clock,
    private val logger: Logger,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val flushScheduler: ProductAnalyticsFlushScheduler,
) : ProductAnalyticsClient {

    override fun track(
        eventName: String,
        properties: Map<String, Any?>,
        sessionId: String?,
    ) {
        applicationScope.launch(ioDispatcher) {
            runCatching {
                enqueue(eventName = eventName, properties = properties, sessionId = sessionId)
            }.onFailure { error ->
                logger.w(TAG, "analytics enqueue failed: ${error::class.java.simpleName}")
            }
        }
    }

    override fun flush() {
        runCatching { flushScheduler.enqueueFlush() }
    }

    public suspend fun enqueue(
        eventName: String,
        properties: Map<String, Any?> = emptyMap(),
        sessionId: String? = null,
    ) {
        if (!userPrefsStore.observeProductAnalyticsEnabled().first()) return
        if (userPrefsStore.observeCurrentUserId().first().isNullOrBlank()) return
        if (!ProductAnalyticsPrivacy.isSafeEventName(eventName)) return
        val sanitized = ProductAnalyticsPrivacy.sanitizeProperties(properties) ?: return
        val now = clock.nowInstant()
        val event = QueuedProductEventEntity(
            id = UUID.randomUUID().toString(),
            eventId = UUID.randomUUID().toString(),
            eventName = eventName,
            occurredAt = now,
            sessionId = sessionId,
            source = ProductAnalyticsEvent.SOURCE_ANDROID,
            propertiesJson = ProductAnalyticsJson.encode(sanitized),
            createdAt = now,
        )
        dao.insert(event)
        dao.trimToMax(MAX_QUEUED_EVENTS)
        flushScheduler.enqueueFlush()
    }

    public suspend fun flushBatch(limit: Int = BATCH_SIZE): Boolean =
        withContext(ioDispatcher) {
            if (!userPrefsStore.observeProductAnalyticsEnabled().first()) return@withContext true
            if (userPrefsStore.observeCurrentUserId().first().isNullOrBlank()) return@withContext true
            val rows = dao.oldest(limit)
            if (rows.isEmpty()) return@withContext true
            val request = ProductAnalyticsBatchRequestDto(
                events = rows.map { row ->
                    ProductAnalyticsEventDto(
                        eventId = row.eventId,
                        eventName = row.eventName,
                        occurredAt = row.occurredAt,
                        sessionId = row.sessionId,
                        source = row.source,
                        properties = ProductAnalyticsJson.decode(row.propertiesJson),
                    )
                },
            )
            val response = api.uploadProductAnalyticsEvents(request = request)
            if (response.isSuccessful) {
                dao.deleteByIds(rows.map { it.id })
                true
            } else {
                false
            }
        }

    public companion object {
        public const val MAX_QUEUED_EVENTS: Int = 1_000
        public const val BATCH_SIZE: Int = 100
        private const val TAG = "ProductAnalytics"
    }
}
