package com.becalm.android.data.remote.api

/**
 * Immutable configuration for OkHttp connection, read, and write timeouts.
 *
 * Two standard configurations are provided as constants:
 * - [Default] for normal API calls.
 * - [Upload] for batch upload calls whose payloads can reach 1 MiB on slow connections.
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
         * Extended timeouts for POST /v1/raw_ingestion_events:batch.
         *
         * Upload batches use a longer read/write window for 1 MiB payloads on slow connections.
         * connect stays at 15 s; read/write extend to 60 s.
         */
        public val Upload: HttpTimeouts = HttpTimeouts(
            connectSeconds = 15L,
            readSeconds = 60L,
            writeSeconds = 60L,
        )
    }
}
