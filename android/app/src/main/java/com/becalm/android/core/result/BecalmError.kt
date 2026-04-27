package com.becalm.android.core.result

/**
 * Sealed hierarchy of all domain errors that can surface through [BecalmResult.Failure].
 *
 * Subtypes:
 * - [Network] — HTTP transport error (non-2xx without a semantic mapping below).
 * - [Unauthorized] — 401; caller must re-authenticate.
 * - [RateLimited] — 429; include a Retry-After hint when the header is present.
 * - [ServerError] — 5xx; unrecoverable server-side fault.
 * - [Validation] — 422 / client-side field validation failure.
 * - [Io] — Local I/O error (file read/write, Room, etc.).
 * - [Permission] — Android runtime permission not granted.
 * - [NotFound] — 404 or missing local resource.
 * - [Cancelled] — coroutine or user-initiated cancellation; typically not shown to user.
 * - [ExtractorUnavailable] — on-device LLM (Gemini Nano / AICore) could not run
 *   the extraction request. [reason] is a short machine-readable tag — `AICORE_NOT_AVAILABLE`
 *   (device unsupported — caller should skip, no retry), `LLM_JSON_PARSE_FAILED`
 *   (model returned malformed output — quarantine), `AICORE_ERROR` (generic SDK error —
 *   transient retry). Spec refs: EMAIL-001 / EMAIL-008, KTR-GEMINI-NANO.
 * - [Unknown] — catch-all wrapping an unexpected [Throwable].
 */
public sealed class BecalmError {

    /** Non-2xx HTTP response that does not map to a more specific subtype. */
    public data class Network(val code: Int, val message: String) : BecalmError()

    /** Server returned 401; the session token is missing or expired. */
    public data object Unauthorized : BecalmError()

    /**
     * Server returned 429.
     *
     * @param retryAfterSeconds parsed from the `Retry-After` response header, or `null` when absent.
     */
    public data class RateLimited(val retryAfterSeconds: Long?) : BecalmError()

    /** Server returned 5xx; [body] contains the raw response body for debugging, when available. */
    public data class ServerError(val code: Int, val body: String?) : BecalmError()

    /**
     * Input validation failure.
     *
     * @param field the specific field that failed validation, or `null` for form-level errors.
     * @param message human-readable explanation of the constraint violation.
     */
    public data class Validation(val field: String?, val message: String) : BecalmError()

    /** Local I/O failure (Room, file system, etc.). */
    public data class Io(val message: String) : BecalmError()

    /** Android runtime permission [permission] was not granted by the user. */
    public data class Permission(val permission: String) : BecalmError()

    /** The requested [resource] (remote or local) does not exist. */
    public data class NotFound(val resource: String) : BecalmError()

    /** Operation was cancelled; typically swallowed rather than surfaced as UI error. */
    public data object Cancelled : BecalmError()

    /**
     * On-device LLM extraction could not complete.
     *
     * Thrown by [com.becalm.android.domain.extractor.GeminiNanoExtractor.extract] when the
     * AICore SDK path fails for any of three reasons tracked via [reason]:
     *
     * - `AICORE_NOT_AVAILABLE` — SDK reports the device does not support Gemini Nano. Caller
     *   (the [com.becalm.android.worker.extraction.CommitmentExtractionWorker]) should treat
     *   this as permanent and return [androidx.work.ListenableWorker.Result.success] without
     *   retry: no amount of WorkManager backoff will make an unsupported device work.
     * - `LLM_JSON_PARSE_FAILED` — model produced output that Moshi could not parse as the
     *   expected JSON array. Deterministic per prompt+model, so caller quarantines instead
     *   of retrying.
     * - `AICORE_ERROR` — any other SDK exception. Typically transient (service crashed,
     *   model still downloading, etc.) so caller may return
     *   [androidx.work.ListenableWorker.Result.retry], but must cap retries to avoid
     *   foreground/startup retry storms when the SDK remains unavailable.
     *
     * Spec refs: EMAIL-001, EMAIL-008, KTR-GEMINI-NANO.
     */
    public data class ExtractorUnavailable(
        val reason: String,
        val cause: Throwable? = null,
    ) : BecalmError()

    /**
     * Unexpected exception with no specific mapping.
     *
     * The raw [throwable] is retained for internal callers that need programmatic access
     * (e.g. workers inspecting specific exception types). For logging and user-facing
     * surfaces, prefer [safeMessage], which exposes only the exception class name and
     * avoids leaking PII or internal details carried in the exception message/stack.
     */
    public data class Unknown(val throwable: Throwable) : BecalmError() {
        /** Exception class name only; safe to log or display. */
        public val safeMessage: String
            get() = throwable::class.simpleName ?: "Unknown"
    }
}

/**
 * Returns a non-PII, display-safe short message for any [BecalmError] variant.
 *
 * Used by UI layers (e.g. [com.becalm.android.ui.auth.AuthViewModel]) to surface
 * an error to the user without leaking backend messages, stack traces, or credential
 * substrings. Each branch emits only the error *kind* plus opaque metadata (HTTP code,
 * field name, resource name) — never the raw server body or throwable message.
 *
 * The [BecalmError.Unknown.safeMessage] property is reused for the Unknown arm so
 * existing consumers see byte-identical strings.
 */
public val BecalmError.safeMessage: String
    get() = when (this) {
        is BecalmError.Network -> "Network error ($code)"
        is BecalmError.Unauthorized -> "Unauthorized"
        is BecalmError.RateLimited -> "Rate limited"
        is BecalmError.ServerError -> "Server error ($code)"
        is BecalmError.Validation -> field?.let { "Validation: $it" } ?: "Validation error"
        is BecalmError.Io -> "Local I/O error"
        is BecalmError.Permission -> "Permission not granted: $permission"
        is BecalmError.NotFound -> "Not found: $resource"
        is BecalmError.Cancelled -> "Cancelled"
        is BecalmError.ExtractorUnavailable -> "Extractor unavailable: $reason"
        is BecalmError.Unknown -> safeMessage
    }
