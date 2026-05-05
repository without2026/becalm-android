package com.becalm.android.data.remote.api

/**
 * Immutable configuration for OkHttp connection, read, and write timeouts.
 *
 * Two standard configurations are provided as constants:
 * - [Default] for normal API calls.
 * - [Voice] for audio upload calls with extended read/write windows.
 *
 * @property connectSeconds Timeout in seconds for establishing a TCP connection.
 * @property readSeconds Timeout in seconds for reading the response from the server.
 * @property writeSeconds Timeout in seconds for writing the request body to the server.
 */
public data class HttpTimeouts(
    val connectSeconds: Long = 15L,
    val readSeconds: Long = 30L,
    val writeSeconds: Long = 30L,
) {
    public companion object {
        /** Standard timeouts applied to all non-upload Railway API calls. */
        public val Default: HttpTimeouts = HttpTimeouts()

        /**
         * Extended timeouts for POST /v1/extractions/commitments.
         *
         * Audio uploads can reach 60 MiB (120-minute AAC); Vertex AI processing adds latency.
         * connect=30s, read=180s, write=180s to accommodate slow upload links and long model
         * inference on the server side (api-contract.yml; VOI-006).
         */
        public val Voice: HttpTimeouts = HttpTimeouts(
            connectSeconds = 30L,
            readSeconds = 180L,
            writeSeconds = 180L,
        )
    }
}
