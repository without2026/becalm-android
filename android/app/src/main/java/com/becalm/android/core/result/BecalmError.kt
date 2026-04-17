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
