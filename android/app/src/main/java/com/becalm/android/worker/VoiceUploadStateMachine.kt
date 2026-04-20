package com.becalm.android.worker

import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.dto.VoiceErrorEnvelope
import kotlinx.coroutines.CancellationException

/**
 * Pure decision helpers for [VoiceUploadWorker].
 *
 * Each function is a deterministic mapping from inputs (state + environment) to a sealed
 * action type. No Android `Context`, no DAO, no DataStore — by design these are unit-testable
 * in isolation. The worker performs the resulting I/O.
 *
 * Logic is byte-equivalent with the original inlined branches in
 * [VoiceUploadWorker.handleFailure] and [VoiceUploadWorker.handleVoice502]; only the
 * retry-vs-quarantine *decision* moves here, not the side effects.
 */
internal object VoiceUploadStateMachine {

    /**
     * Decision for a transient failure path (network IOException, HTTP 429/500/503,
     * empty 200 body, or 502 vertex_upstream_error / unparseable envelope).
     *
     * WorkManager's `runAttemptCount` is zero-based: the current execution is the Nth
     * attempt where N = runAttemptCount + 1. Spec VOI-006 caps total attempts at
     * [maxAttempts], so we quarantine when the current execution is already the
     * [maxAttempts]th — i.e. `runAttemptCount >= maxAttempts - 1` — otherwise one extra
     * Vertex upload would occur on bad recordings.
     */
    fun decideRetryAction(runAttemptCount: Int, maxAttempts: Int): RetryAction {
        return if (runAttemptCount >= maxAttempts - 1) {
            RetryAction.Quarantine
        } else {
            RetryAction.Retry
        }
    }

    /**
     * Decision for an HTTP 502 response based on the parsed [VoiceErrorEnvelope] error code.
     *
     * Per api-contract.yml, three error codes are possible in the 502 envelope:
     * - [VoiceErrorEnvelope.OUTPUT_TRUNCATED] — non-retryable; quarantine immediately.
     * - [VoiceErrorEnvelope.SCHEMA_VIOLATION] — non-retryable; quarantine immediately.
     * - [VoiceErrorEnvelope.VERTEX_UPSTREAM_ERROR] — transient; defer to [decideRetryAction].
     *
     * If the body cannot be parsed (malformed JSON, null body, or unknown error code), the
     * failure is treated as transient to avoid silently quarantining an event that might
     * succeed on retry. Finding #4 fix.
     */
    fun decide502Action(errorCode: String?): Voice502Action {
        return when (errorCode) {
            VoiceErrorEnvelope.OUTPUT_TRUNCATED, VoiceErrorEnvelope.SCHEMA_VIOLATION ->
                Voice502Action.Quarantine
            else ->
                Voice502Action.HandleAsTransient
        }
    }
}

/** Result of [VoiceUploadStateMachine.decideRetryAction]. */
internal sealed interface RetryAction {
    /** Cap reached — caller must quarantine the entity (mark "failed"). */
    data object Quarantine : RetryAction

    /** Within the cap — caller must return `Result.retry()` and leave the row pending. */
    data object Retry : RetryAction
}

/** Result of [VoiceUploadStateMachine.decide502Action]. */
internal sealed interface Voice502Action {
    /** Server has deterministically rejected — caller must quarantine. */
    data object Quarantine : Voice502Action

    /** Treat as transient — caller defers to [VoiceUploadStateMachine.decideRetryAction]. */
    data object HandleAsTransient : Voice502Action
}

/**
 * 반복되는 try/catch 블록을 통합한다.
 * CancellationException 은 반드시 rethrow 하여 WorkManager 의 cancel 신호를 보존한다.
 * 실패 시 `"$op: ${e.message}"` 형태로 warn 로그를 남기고 [onFailure] 결과를 반환한다.
 *
 * Lifted from `VoiceUploadWorker.runCatchingNonCancel` (file-private inline) to keep the
 * worker under the 400-LOC K1 limit; behaviour is byte-identical with the original
 * including the TAG used for the warn log.
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
