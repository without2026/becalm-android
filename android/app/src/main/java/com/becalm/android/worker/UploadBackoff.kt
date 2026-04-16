package com.becalm.android.worker

import kotlin.math.min
import kotlin.random.Random

/**
 * Pure, stateless helper for computing exponential-backoff-with-jitter delays for
 * [UploadWorker] retry cycles.
 *
 * ## Algorithm
 * If the server supplied a `Retry-After` header, that value is used verbatim (SYNC-005).
 * Otherwise the delay is:
 *
 * ```
 * base = 30s × 2^(attempt - 1)   // doubles every attempt, first retry = 30s
 * capped = min(base, 3600s)       // hard ceiling at 1 hour
 * jittered = capped × [0.8, 1.2) // ±20 % uniform jitter
 * ```
 *
 * The first attempt index passed to [nextDelaySeconds] should be 1 (the attempt that just
 * failed). Attempt 0 is reserved by WorkManager's own initial-run semantics and is never
 * passed here; if it is, it is treated the same as attempt 1 (base 30s).
 *
 * ## Thread safety
 * All functions are pure. [Random.Default] is thread-safe.
 */
public object UploadBackoff {

    /** Base delay in seconds for the first retry. */
    private const val BASE_DELAY_SEC: Long = 30L

    /** Maximum delay ceiling in seconds (1 hour). */
    private const val MAX_DELAY_SEC: Long = 3_600L

    /** Lower bound of the jitter factor (−20 %). */
    private const val JITTER_MIN: Double = 0.8

    /** Upper bound of the jitter factor (+20 %). */
    private const val JITTER_MAX: Double = 1.2

    /**
     * Maximum number of upload attempts before the worker reports permanent failure.
     *
     * WorkManager tracks this via `inputData.getInt("attempt", 0)`. When `attempt + 1`
     * would exceed [MAX_ATTEMPTS], the worker returns [androidx.work.ListenableWorker.Result.failure]
     * instead of [androidx.work.ListenableWorker.Result.retry].
     */
    public const val MAX_ATTEMPTS: Int = 6

    /**
     * Returns the number of seconds the caller should communicate to WorkManager's
     * `setInitialDelay` (or equivalent) before the next retry.
     *
     * @param attempt      1-based index of the attempt that just failed.
     *                     Values ≤ 0 are treated as 1.
     * @param retryAfterSec Value parsed from the `Retry-After` response header, or `null`
     *                      when the header was absent. When non-null this value wins and
     *                      the exponential formula is skipped.
     * @return Delay in whole seconds, always in the range [1, [MAX_DELAY_SEC]].
     */
    public fun nextDelaySeconds(attempt: Int, retryAfterSec: Long?): Long {
        if (retryAfterSec != null) {
            // Server-supplied hint always wins (SYNC-005).
            return retryAfterSec.coerceAtLeast(1L)
        }

        val effectiveAttempt = attempt.coerceAtLeast(1)
        // 2^(attempt-1) — use a safe shift capped to avoid Long overflow for large attempts.
        val shift = (effectiveAttempt - 1).coerceAtMost(30)
        val base = BASE_DELAY_SEC * (1L shl shift)
        val capped = min(base, MAX_DELAY_SEC)

        val jitter = Random.Default.nextDouble(JITTER_MIN, JITTER_MAX)
        return (capped * jitter).toLong().coerceIn(1L, MAX_DELAY_SEC)
    }
}
