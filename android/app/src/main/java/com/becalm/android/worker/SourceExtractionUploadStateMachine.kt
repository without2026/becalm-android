package com.becalm.android.worker

import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.dto.SourceExtractionErrorEnvelope
import kotlinx.coroutines.CancellationException

/**
 * Pure decision helpers for source extraction upload workers.
 *
 * Each function is a deterministic mapping from inputs (state + environment) to a sealed
 * action type. No Android `Context`, no DAO, no DataStore — by design these are unit-testable
 * in isolation. The worker performs the resulting I/O.
 *
 * The worker performs the side effects; this object only decides retry versus quarantine.
 */
internal object SourceExtractionUploadStateMachine {

    /**
     * Decision for a transient failure path (network IOException, HTTP 429/500/503,
     * empty 200 body, or 502 vertex_upstream_error / unparseable envelope).
     *
     * WorkManager's `runAttemptCount` is zero-based: the current execution is the Nth
     * attempt where N = runAttemptCount + 1. Spec VOI-006 caps total attempts at
     * [maxAttempts], so we quarantine when the current execution is already the
     * [maxAttempts]th — i.e. `runAttemptCount >= maxAttempts - 1` — otherwise one extra
     * extraction attempt would occur on deterministic failures.
     */
    fun decideRetryAction(runAttemptCount: Int, maxAttempts: Int): RetryAction {
        return if (runAttemptCount >= maxAttempts - 1) {
            RetryAction.Quarantine
        } else {
            RetryAction.Retry
        }
    }

    /**
     * Decision for an HTTP 502 response based on the parsed [SourceExtractionErrorEnvelope] error code.
     *
     * Per api-contract.yml, three error codes are possible in the 502 envelope:
     * - [SourceExtractionErrorEnvelope.OUTPUT_TRUNCATED] — non-retryable; quarantine immediately.
     * - [SourceExtractionErrorEnvelope.SCHEMA_VIOLATION] — non-retryable; quarantine immediately.
     * - [SourceExtractionErrorEnvelope.VERTEX_UPSTREAM_ERROR] — transient; defer to [decideRetryAction].
     *
     * If the body cannot be parsed (malformed JSON, null body, or unknown error code), the
     * failure is treated as transient to avoid silently quarantining an event that might
     * succeed on retry. Finding #4 fix.
     */
    fun decide502Action(errorCode: String?): SourceExtraction502Action {
        return when (errorCode) {
            SourceExtractionErrorEnvelope.OUTPUT_TRUNCATED, SourceExtractionErrorEnvelope.SCHEMA_VIOLATION ->
                SourceExtraction502Action.Quarantine
            else ->
                SourceExtraction502Action.HandleAsTransient
        }
    }
}

/** Result of [SourceExtractionUploadStateMachine.decideRetryAction]. */
internal sealed interface RetryAction {
    /** Cap reached — caller must quarantine the entity (mark "failed"). */
    data object Quarantine : RetryAction

    /** Within the cap — caller must return `Result.retry()` and leave the row pending. */
    data object Retry : RetryAction
}

/** Result of [SourceExtractionUploadStateMachine.decide502Action]. */
internal sealed interface SourceExtraction502Action {
    /** Server has deterministically rejected — caller must quarantine. */
    data object Quarantine : SourceExtraction502Action

    /** Treat as transient — caller defers to [SourceExtractionUploadStateMachine.decideRetryAction]. */
    data object HandleAsTransient : SourceExtraction502Action
}

/**
 * 반복되는 try/catch 블록을 통합한다.
 * CancellationException 은 반드시 rethrow 하여 WorkManager 의 cancel 신호를 보존한다.
 * 실패 시 `"$op: ${e.message}"` 형태로 warn 로그를 남기고 [onFailure] 결과를 반환한다.
 *
 * Shared by source extraction workers so cancellation and logging behavior stays consistent.
 */
internal inline fun <T> runCatchingNonCancel(
    logger: Logger,
    tag: String,
    op: String,
    block: () -> T,
    onFailure: (Exception) -> T,
): T {
    return try {
        block()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        logger.w(tag, "$op: ${e.message}")
        onFailure(e)
    }
}
