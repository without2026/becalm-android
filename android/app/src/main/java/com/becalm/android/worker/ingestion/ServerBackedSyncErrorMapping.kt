package com.becalm.android.worker.ingestion

import com.becalm.android.data.remote.dto.ErrorEnvelopeDto
import com.becalm.android.data.repository.SOURCE_CONNECTION_STATUS_NEEDS_REAUTH
import com.squareup.moshi.Moshi
import retrofit2.Response

internal object ServerBackedSyncErrorMapping {
    private val errorEnvelopeAdapter = Moshi.Builder()
        .build()
        .adapter(ErrorEnvelopeDto::class.java)

    fun <T> triggerFailureFor(response: Response<T>): ServerBackedTriggerResult.Failure {
        val rawBody = runCatching { response.errorBody()?.string().orEmpty() }.getOrDefault("")
        val envelope = rawBody.takeIf { it.isNotBlank() }?.let { body ->
            runCatching { errorEnvelopeAdapter.fromJson(body) }.getOrNull()
        }
        if (envelope.requiresReconnect()) {
            return ServerBackedTriggerResult.Failure(
                message = SOURCE_CONNECTION_STATUS_NEEDS_REAUTH,
                retryable = false,
            )
        }
        return ServerBackedTriggerResult.Failure(
            message = envelope.safeFailureMessage(rawBody, "HTTP ${response.code()}"),
            retryable = response.code() == 429 || response.code() in 500..599,
        )
    }

    private fun ErrorEnvelopeDto?.requiresReconnect(): Boolean =
        this?.clientAction == "reconnect_source" ||
            this?.error in SOURCE_RECONNECT_ERROR_CODES

    private fun ErrorEnvelopeDto?.safeFailureMessage(rawBody: String, fallback: String): String =
        when {
            !this?.clientAction.isNullOrBlank() -> this?.clientAction.orEmpty()
            !this?.error.isNullOrBlank() -> this?.error.orEmpty()
            rawBody.isNotBlank() -> rawBody
            else -> fallback
        }

    private val SOURCE_RECONNECT_ERROR_CODES = setOf(
        "source_connection_disconnected",
        "source_connection_needs_reauth",
        "provider_needs_reauth",
    )
}
