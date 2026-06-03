package com.becalm.android.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.di.UserPrefs
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.dto.SourceType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

public enum class ProcessingPhase {
    IDLE,
    SCANNING,
    NEW_ITEMS,
    AWAITING_CONFIRMATION,
    GEMINI,
    UPLOADING,
    NO_NEW_ITEMS,
    SYNCED,
    BLOCKED,
    ERROR,
}

public val ProcessingPhase.isActive: Boolean
    get() = when (this) {
        ProcessingPhase.SCANNING,
        ProcessingPhase.GEMINI,
        ProcessingPhase.UPLOADING
        -> true
        ProcessingPhase.NEW_ITEMS,
        ProcessingPhase.AWAITING_CONFIRMATION,
        ProcessingPhase.IDLE,
        ProcessingPhase.NO_NEW_ITEMS,
        ProcessingPhase.SYNCED,
        ProcessingPhase.BLOCKED,
        ProcessingPhase.ERROR
        -> false
    }

public data class ProcessingSourceState(
    val sourceType: String,
    val phase: ProcessingPhase = ProcessingPhase.IDLE,
    val itemCount: Int = 0,
    val message: String? = null,
    val updatedAt: Instant? = null,
)

public object ProcessingStatusMessages {
    public const val SOURCE_SYNC_BACKPRESSURE_DELAYED: String = "source_sync_backpressure_delayed"
    public const val SOURCE_SYNC_IMPORTING_MORE_PAGES: String = "source_sync_importing_more_pages"
    public const val LLM_DAILY_BUDGET_EXCEEDED: String = "llm_daily_budget_exceeded"
    public const val LLM_RATE_LIMITED_RETRYING: String = "llm_rate_limited_retrying"
    public const val AUDIO_CONFIRMATION_REQUIRED: String = "audio_confirmation_required"
}

@Singleton
public class ProcessingStatusRepository @Inject constructor(
    @UserPrefs private val userPrefs: DataStore<Preferences>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val logger: Logger,
) {
    public fun observeAll(): Flow<List<ProcessingSourceState>> =
        userPrefs.data
            .map { prefs -> DISPLAY_SOURCES.map { prefs.toProcessingState(it) } }
            .distinctUntilChanged()

    public suspend fun clearAll() = withContext(ioDispatcher) {
        runCatching {
            userPrefs.edit { prefs ->
                DISPLAY_SOURCES.forEach { sourceType ->
                    prefs.remove(phaseKey(sourceType))
                    prefs.remove(countKey(sourceType))
                    prefs.remove(messageKey(sourceType))
                    prefs.remove(updatedAtKey(sourceType))
                }
            }
        }.onFailure { error ->
            logger.w(TAG, "clear processing status failed", error)
        }
    }

    public suspend fun recordScanning(sourceType: String, message: String? = null) {
        record(sourceType, ProcessingPhase.SCANNING, message = message)
    }

    public suspend fun recordNewItems(sourceType: String, itemCount: Int, message: String? = null) {
        record(sourceType, ProcessingPhase.NEW_ITEMS, itemCount = itemCount, message = message)
    }

    public suspend fun recordAwaitingConfirmation(sourceType: String, itemCount: Int, message: String? = null) {
        record(sourceType, ProcessingPhase.AWAITING_CONFIRMATION, itemCount = itemCount, message = message)
    }

    public suspend fun recordGemini(sourceType: String, message: String? = null) {
        record(sourceType, ProcessingPhase.GEMINI, message = message)
    }

    public suspend fun recordUploading(sourceType: String, message: String? = null) {
        record(sourceType, ProcessingPhase.UPLOADING, message = message)
    }

    public suspend fun recordNoNewItems(sourceType: String, message: String? = null) {
        record(sourceType, ProcessingPhase.NO_NEW_ITEMS, message = message)
    }

    public suspend fun recordScanResult(
        sourceType: String,
        itemCount: Int,
        newItemsMessage: String? = null,
    ) {
        if (itemCount > 0) {
            if (sourceType in AUDIO_CONFIRMATION_SOURCES) {
                recordAwaitingConfirmation(
                    sourceType = sourceType,
                    itemCount = itemCount,
                    message = newItemsMessage ?: ProcessingStatusMessages.AUDIO_CONFIRMATION_REQUIRED,
                )
            } else {
                recordNewItems(sourceType, itemCount, newItemsMessage)
            }
        } else {
            recordNoNewItems(sourceType)
        }
    }

    public suspend fun recordSynced(sourceType: String, itemCount: Int = 0, message: String? = null) {
        record(sourceType, ProcessingPhase.SYNCED, itemCount = itemCount, message = message)
    }

    public suspend fun recordBlocked(sourceType: String, message: String? = null) {
        record(sourceType, ProcessingPhase.BLOCKED, message = message)
    }

    public suspend fun recordError(sourceType: String, message: String? = null, itemCount: Int = 0) {
        record(sourceType, ProcessingPhase.ERROR, itemCount = itemCount, message = message)
    }

    private suspend fun record(
        sourceType: String,
        phase: ProcessingPhase,
        itemCount: Int = 0,
        message: String? = null,
        at: Instant = Clock.System.now(),
    ) = withContext(ioDispatcher) {
        runCatching {
            userPrefs.edit { prefs ->
                prefs[phaseKey(sourceType)] = phase.name
                prefs[countKey(sourceType)] = itemCount.coerceAtLeast(0)
                prefs[updatedAtKey(sourceType)] = at.toEpochMilliseconds()
                if (message.isNullOrBlank()) {
                    prefs.remove(messageKey(sourceType))
                } else {
                    prefs[messageKey(sourceType)] = message.take(MAX_MESSAGE_CHARS)
                }
            }
        }.onFailure { error ->
            logger.w(TAG, "record processing status failed source=$sourceType phase=$phase", error)
        }
    }

    private fun Preferences.toProcessingState(sourceType: String): ProcessingSourceState {
        val phase = this[phaseKey(sourceType)]
            ?.let { runCatching { ProcessingPhase.valueOf(it) }.getOrNull() }
            ?: ProcessingPhase.IDLE
        return ProcessingSourceState(
            sourceType = sourceType,
            phase = phase,
            itemCount = this[countKey(sourceType)] ?: 0,
            message = this[messageKey(sourceType)],
            updatedAt = this[updatedAtKey(sourceType)]?.let(Instant::fromEpochMilliseconds),
        )
    }

    private companion object {
        private const val TAG = "ProcessingStatus"
        private const val MAX_MESSAGE_CHARS = 96
        private val AUDIO_CONFIRMATION_SOURCES: Set<String> = setOf(
            SourceType.VOICE,
            SourceType.CALL_RECORDING,
            SourceType.MEETING,
        )
        private val DISPLAY_SOURCES: List<String> = listOf(
            SourceType.VOICE,
            SourceType.CALL_RECORDING,
            SourceType.MEETING,
            SourceType.MESSAGE_SCREENSHOT,
            SourceType.NAVER_IMAP,
            SourceType.DAUM_IMAP,
            SourceType.GMAIL,
            SourceType.OUTLOOK_MAIL,
            SourceType.GOOGLE_CALENDAR,
            SourceType.OUTLOOK_CALENDAR,
        )

        private fun phaseKey(sourceType: String) =
            stringPreferencesKey("processing_status.$sourceType.phase")

        private fun countKey(sourceType: String) =
            intPreferencesKey("processing_status.$sourceType.count")

        private fun messageKey(sourceType: String) =
            stringPreferencesKey("processing_status.$sourceType.message")

        private fun updatedAtKey(sourceType: String) =
            longPreferencesKey("processing_status.$sourceType.updated_at")
    }
}
