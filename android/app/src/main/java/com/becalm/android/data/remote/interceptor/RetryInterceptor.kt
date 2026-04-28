package com.becalm.android.data.remote.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp [Interceptor] that retries transient failures with exponential backoff.
 *
 * ## Retry policy (SYNC-003, SYNC-005)
 *
 * **Triggering conditions** — a retry is attempted on:
 * - [IOException] from the network layer (connection reset, timeout, etc.).
 * - HTTP 5xx responses **except** 501 Not Implemented (not a transient failure).
 * - HTTP 408 Request Timeout.
 * - HTTP 429 Too Many Requests.
 *
 * **Non-retryable conditions** — the response is returned immediately on:
 * - Any 4xx status other than 408 and 429.
 * - 501 Not Implemented.
 * - One-shot request bodies (`request.body?.isOneShot() == true`): the body stream
 *   cannot be replayed, so retry is impossible.
 *
 * **Attempt budget** — maximum 3 attempts (1 initial + 2 retries).
 *
 * **Backoff schedule** (attempt index is 0-based):
 * - Attempt 0 (initial): 0 ms delay.
 * - Attempt 1 (1st retry): 500 ms delay.
 * - Attempt 2 (2nd retry): 2 000 ms delay.
 * Delays are capped at 2 000 ms unless a `Retry-After` header overrides this.
 *
 * **429 `Retry-After` header** — when a 429 response includes `Retry-After: N` (seconds),
 * the actual sleep duration is `max(backoffMs, N * 1_000)` capped at 10 000 ms.
 * This ensures compliance with the server's rate-limit window (SYNC-005) while
 * preventing runaway waits.
 *
 * **Response lifecycle** — retryable HTTP responses are closed before the next
 * `chain.proceed()` call. Only the final attempt's response is returned open to the caller.
 *
 * **IOException propagation** — after exhausting the attempt budget an [IOException]
 * is re-thrown unchanged; it is never swallowed.
 */
public class RetryInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // One-shot bodies cannot be replayed — bail immediately without retry
        if (request.body?.isOneShot() == true) {
            return chain.proceed(request)
        }

        var attempt = 0
        var lastException: IOException? = null
        // Override written by 429 Retry-After handling; consumed by the next iteration's sleep.
        var overrideNextDelayMs: Long? = null

        while (attempt < MAX_ATTEMPTS) {
            val delayMs = overrideNextDelayMs ?: backoffDelayMs(attempt)
            overrideNextDelayMs = null
            if (delayMs > 0L) {
                sleepBeforeRetry(delayMs)
            }

            try {
                val response = chain.proceed(request)

                if (!isRetryableStatus(response.code)) {
                    return response
                }

                // Budget exhausted: return the final retryable response open for the caller.
                if (attempt == MAX_ATTEMPTS - 1) {
                    return response
                }

                // Retryable status — read retry metadata, then close before the next proceed().
                overrideNextDelayMs = retryAfterOverrideMs(response)
                response.close()
                attempt++
            } catch (e: IOException) {
                lastException = e
                attempt++
            }
        }

        throw lastException ?: IOException("RetryInterceptor: attempt budget ($MAX_ATTEMPTS) exhausted")
    }

    /**
     * Returns the standard exponential backoff delay for the given 0-based [attempt] index.
     *
     * Schedule: 0 ms → 500 ms → 2 000 ms (capped at [MAX_BACKOFF_MS]).
     * This delay is applied *before* the attempt to keep the loop logic simple; attempt 0
     * always gets 0 ms.
     */
    private fun backoffDelayMs(attempt: Int): Long = when (attempt) {
        0 -> 0L
        1 -> FIRST_RETRY_DELAY_MS
        else -> MAX_BACKOFF_MS
    }

    /**
     * Returns an override delay to apply before the next retry, or `null` to fall back to
     * the standard exponential backoff.
     *
     * Only 429 responses with a parseable `Retry-After` (seconds) produce an override. The
     * override is capped at [MAX_RETRY_AFTER_MS] so a misbehaving server cannot stall the
     * client for minutes. For all other retryable statuses the standard backoff wins.
     */
    private fun retryAfterOverrideMs(response: Response): Long? {
        if (response.code != HTTP_TOO_MANY_REQUESTS) return null
        val retryAfterSeconds = response.header(HEADER_RETRY_AFTER)?.toLongOrNull() ?: return null
        return minOf(retryAfterSeconds * MILLIS_PER_SECOND, MAX_RETRY_AFTER_MS)
    }

    /**
     * Returns true when [statusCode] warrants a retry attempt.
     *
     * Retryable: 5xx (except 501), 408, 429.
     * Non-retryable: all other 4xx, 501, 2xx/3xx.
     */
    private fun isRetryableStatus(statusCode: Int): Boolean = when (statusCode) {
        HTTP_NOT_IMPLEMENTED -> false
        HTTP_REQUEST_TIMEOUT, HTTP_TOO_MANY_REQUESTS -> true
        in 500..599 -> true
        else -> false
    }

    private fun sleepBeforeRetry(delayMs: Long) {
        try {
            Thread.sleep(delayMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("RetryInterceptor interrupted before retry", e)
        }
    }

    private companion object {
        private const val MAX_ATTEMPTS = 3
        private const val FIRST_RETRY_DELAY_MS = 500L
        private const val MAX_BACKOFF_MS = 2_000L
        private const val MAX_RETRY_AFTER_MS = 10_000L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val HEADER_RETRY_AFTER = "Retry-After"
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_NOT_IMPLEMENTED = 501
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
