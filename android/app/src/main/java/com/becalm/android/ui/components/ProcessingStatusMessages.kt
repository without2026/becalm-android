package com.becalm.android.ui.components

import com.becalm.android.R
import com.becalm.android.data.repository.ProcessingStatusMessages

internal fun localizedProcessingStatusMessage(message: String?): UiMessage? = when (message) {
    ProcessingStatusMessages.SOURCE_SYNC_BACKPRESSURE_DELAYED ->
        UiMessage.resource(R.string.processing_status_source_sync_delayed)
    ProcessingStatusMessages.SOURCE_SYNC_IMPORTING_MORE_PAGES ->
        UiMessage.resource(R.string.processing_status_source_sync_importing_more_pages)
    ProcessingStatusMessages.LLM_DAILY_BUDGET_EXCEEDED ->
        UiMessage.resource(R.string.processing_status_llm_daily_budget_exceeded)
    ProcessingStatusMessages.LLM_RATE_LIMITED_RETRYING ->
        UiMessage.resource(R.string.processing_status_llm_rate_limited_retrying)
    ProcessingStatusMessages.AUDIO_CONFIRMATION_REQUIRED ->
        UiMessage.resource(R.string.processing_status_audio_confirmation_required)
    else -> null
}
