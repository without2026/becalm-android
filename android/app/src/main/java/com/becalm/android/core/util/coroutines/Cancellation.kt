package com.becalm.android.core.util.coroutines

import kotlinx.coroutines.CancellationException

/**
 * Re-throws this throwable if it is a [CancellationException], preserving structured-concurrency
 * cancellation propagation. Use inside catch blocks or `runCatching { ... }.fold` handlers that
 * map arbitrary [Throwable] to a domain error type; without this guard, a swallowed
 * [CancellationException] causes the enclosing coroutine to "ignore" its own cancellation.
 *
 * Example:
 * ```
 * runCatching { dao.upsert(x) }.fold(
 *     onSuccess = { ... },
 *     onFailure = { e ->
 *         e.rethrowIfCancellation()
 *         BecalmResult.Failure(BecalmError.Unknown(e))
 *     },
 * )
 * ```
 *
 * Spec ref: rubric-I (Errors) — no swallowed CancellationException.
 */
public fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
