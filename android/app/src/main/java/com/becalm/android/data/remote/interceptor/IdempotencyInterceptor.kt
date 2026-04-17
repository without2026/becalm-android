package com.becalm.android.data.remote.interceptor

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp [Interceptor] that attaches a server-side deduplication key to opt-in requests.
 *
 * ## Opt-in pattern
 *
 * Repository callsites signal idempotency intent by adding the marker header
 * `X-BeCalm-Idempotent: 1` to their Retrofit request (via `@Header` parameter or
 * `Request.Builder.header`). This interceptor:
 *
 * 1. Checks whether the outgoing request carries `X-BeCalm-Idempotent: 1`.
 * 2. If the header is **absent** — forwards the request unchanged.
 * 3. If the header is **present**:
 *    a. Generates a fresh UUID via [IdempotencyKeyProvider.generate].
 *    b. Attaches `Idempotency-Key: <uuid>` to the wire request.
 *    c. Strips `X-BeCalm-Idempotent` so the sentinel header never reaches the server.
 *    d. Proceeds with the modified request.
 *
 * This keeps idempotency key generation out of the DTO/repository layer.
 * The repository needs only declare "this call is idempotent" with a single header;
 * this interceptor handles key generation and wire protocol.
 *
 * Endpoints that carry idempotency keys per api-contract.yml:
 * - `POST /v1/raw_ingestion_events:batch` (SYNC-001, SYNC-002)
 * - `PATCH /v1/commitments/{id}` (CMT-005..007)
 *
 * @param idempotencyProvider Strategy for generating unique idempotency key strings.
 *   In production: [DefaultIdempotencyKeyProvider] (UUID v4). In tests: deterministic stub.
 */
public class IdempotencyInterceptor(
    private val idempotencyProvider: IdempotencyKeyProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Step 1: check opt-in marker
        if (request.header(HEADER_OPT_IN) == null) {
            return chain.proceed(request)
        }

        // Steps 2–4: generate key, attach Idempotency-Key, strip sentinel, proceed
        val key = idempotencyProvider.generate()
        val modifiedRequest = request.newBuilder()
            .removeHeader(HEADER_OPT_IN)
            .header(HEADER_IDEMPOTENCY_KEY, key)
            .build()

        return chain.proceed(modifiedRequest)
    }

    private companion object {
        /** Sentinel header set by repository callers to opt in to idempotency key injection. */
        private const val HEADER_OPT_IN = "X-BeCalm-Idempotent"

        /** Wire header consumed by the Railway backend for server-side deduplication. */
        private const val HEADER_IDEMPOTENCY_KEY = "Idempotency-Key"
    }
}
