package com.becalm.android.core.analytics

import android.content.Context
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.data.remote.dto.ProductEventDto
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

public interface ProductAnalyticsEventQueue {
    public suspend fun enqueue(events: List<ProductAnalyticsEvent>)
    public suspend fun peek(limit: Int): List<ProductAnalyticsEvent>
    public suspend fun remove(eventIds: Set<String>)
}

@Singleton
public class FileProductAnalyticsEventQueue internal constructor(
    private val queueFile: File,
    private val moshi: Moshi,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val maxQueuedEvents: Int = MAX_QUEUED_EVENTS,
) : ProductAnalyticsEventQueue {

    @Inject
    public constructor(
        @ApplicationContext context: Context,
        moshi: Moshi,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        queueFile = File(context.filesDir, QUEUE_FILE_PATH),
        moshi = moshi,
        ioDispatcher = ioDispatcher,
    )

    private val lock = Mutex()
    private val adapter = moshi.adapter(ProductEventDto::class.java)

    override suspend fun enqueue(events: List<ProductAnalyticsEvent>) {
        if (events.isEmpty()) return
        val safeEvents = events.map { it.toDto() }
        withContext(ioDispatcher) {
            lock.withLock {
                val bounded = (readAll() + safeEvents).takeLast(maxQueuedEvents.coerceAtLeast(1))
                rewrite(bounded)
            }
        }
    }

    override suspend fun peek(limit: Int): List<ProductAnalyticsEvent> {
        if (limit <= 0) return emptyList()
        return withContext(ioDispatcher) {
            lock.withLock {
                readAll().take(limit).map { it.toEvent() }
            }
        }
    }

    override suspend fun remove(eventIds: Set<String>) {
        if (eventIds.isEmpty()) return
        withContext(ioDispatcher) {
            lock.withLock {
                val remaining = readAll().filterNot { it.eventId in eventIds }
                rewrite(remaining)
            }
        }
    }

    private fun readAll(): List<ProductEventDto> {
        if (!queueFile.exists()) return emptyList()
        return queueFile.readLines()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) null else runCatching { adapter.fromJson(trimmed) }.getOrNull()
            }
    }

    private fun rewrite(events: List<ProductEventDto>) {
        if (events.isEmpty()) {
            if (queueFile.exists()) queueFile.delete()
            return
        }
        queueFile.parentFile?.mkdirs()
        val tmp = File(queueFile.parentFile, "${queueFile.name}.tmp")
        tmp.writeText(events.joinToString(separator = "\n", postfix = "\n") { adapter.toJson(it) })
        tmp.renameTo(queueFile)
    }

    private fun ProductAnalyticsEvent.toDto(): ProductEventDto =
        ProductEventDto(
            eventId = eventId,
            eventName = eventName,
            occurredAt = occurredAt,
            sessionId = sessionId,
            source = "android",
            properties = ProductAnalyticsValidation.sanitizedProperties(properties),
        )

    private fun ProductEventDto.toEvent(): ProductAnalyticsEvent =
        ProductAnalyticsEvent(
            eventId = eventId,
            eventName = eventName,
            occurredAt = occurredAt,
            sessionId = sessionId,
            properties = properties,
        )

    private companion object {
        private const val QUEUE_FILE_PATH = "analytics/product-events.jsonl"
        private const val MAX_QUEUED_EVENTS = 1_000
    }
}
