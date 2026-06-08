package com.becalm.android.unit.worker

import com.becalm.android.data.repository.SOURCE_CONNECTION_STATUS_NEEDS_REAUTH
import com.becalm.android.worker.ingestion.ServerBackedSyncErrorMapping
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import retrofit2.Response

class ServerBackedSyncErrorMappingSpecTest {

    @Test
    fun `connection scoped sync maps reconnect source envelope to non retryable reauth`() {
        val result = ServerBackedSyncErrorMapping.triggerFailureFor(
            Response.error<Unit>(
                409,
                """
                {
                  "error": "source_connection_needs_reauth",
                  "message": "Reconnect this source before syncing.",
                  "retryable": false,
                  "client_action": "reconnect_source"
                }
                """.trimIndent().toResponseBody("application/json".toMediaType()),
            ),
        )

        assertEquals(SOURCE_CONNECTION_STATUS_NEEDS_REAUTH, result.message)
        assertFalse(result.retryable)
    }

    @Test
    fun `connection scoped sync maps provider reauth error code to non retryable reauth`() {
        val result = ServerBackedSyncErrorMapping.triggerFailureFor(
            Response.error<Unit>(
                409,
                """
                {
                  "error": "provider_needs_reauth",
                  "message": "Provider token expired.",
                  "retryable": false
                }
                """.trimIndent().toResponseBody("application/json".toMediaType()),
            ),
        )

        assertEquals(SOURCE_CONNECTION_STATUS_NEEDS_REAUTH, result.message)
        assertFalse(result.retryable)
    }

    @Test
    fun `connection scoped sync keeps retryable server errors retryable with safe error token`() {
        val result = ServerBackedSyncErrorMapping.triggerFailureFor(
            Response.error<Unit>(
                503,
                """
                {
                  "error": "source_sync_worker_unavailable",
                  "message": "Worker unavailable.",
                  "retryable": true,
                  "client_action": "retry_later"
                }
                """.trimIndent().toResponseBody("application/json".toMediaType()),
            ),
        )

        assertEquals("retry_later", result.message)
        assertEquals(true, result.retryable)
    }
}
