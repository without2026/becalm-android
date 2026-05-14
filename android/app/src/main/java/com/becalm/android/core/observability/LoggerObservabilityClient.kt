package com.becalm.android.core.observability

import com.becalm.android.core.util.Logger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Observability"

/**
 * Production [ObservabilityClient] that fans structured events out to the project's
 * existing [Logger] abstraction.
 *
 * Why Logger and not a vendor SDK directly? The Wave-6 alpha milestone requires the
 * ONB-007 `onboarding_step_failed` signal to exist **somewhere** so that operators
 * can correlate onboarding churn even before a crash-reporting SDK is wired.
 * Funnelling through [Logger] means:
 *  - debug builds surface the signal in logcat (Timber tree planted in
 *    [com.becalm.android.BecalmApplication.onCreate]),
 *  - release builds that wire a remote logger pick it up automatically,
 *  - switching to Firebase Crashlytics later is a swap of the Hilt binding in
 *    [com.becalm.android.core.di.ObservabilityModule] — no call-site churn.
 *
 * ## PII scrubbing
 * Tag values that match [PII_PATTERNS] (bearer tokens, JWTs, email addresses) are
 * replaced with `[redacted]` before being written to the log. Concrete Crashlytics
 * bindings MUST preserve this contract via an equivalent filtering
 * hook; the abstraction itself is not sufficient because vendor SDKs may otherwise
 * auto-enrich events with the raw string.
 *
 * ## Thread-safety
 * [Logger] implementations are responsible for their own synchronisation; this class
 * holds no mutable state other than the optional user scope, guarded by `@Volatile`
 * so that the most recent [setUserScope] value wins on concurrent reads.
 */
@Singleton
public class LoggerObservabilityClient @Inject constructor(
    private val logger: Logger,
) : ObservabilityClient {

    @Volatile
    private var currentUserScope: String? = null

    override fun captureMessage(message: String, tags: ObservabilityTags) {
        logger.i(TAG, format("event", message, tags))
    }

    override fun captureException(throwable: Throwable, tags: ObservabilityTags) {
        logger.e(TAG, format("exception", throwable.javaClass.simpleName, tags), throwable)
    }

    override fun addBreadcrumb(category: String, message: String, data: ObservabilityTags) {
        logger.d(TAG, format("breadcrumb/$category", message, data))
    }

    override fun setUserScope(userId: String?) {
        currentUserScope = userId
    }

    /**
     * Produces a deterministic single-line representation of the event for the logger.
     *
     * Format: `kind=<kind> msg=<label> user=<pseudoId> k1=v1 k2=v2 …`. The `user=`
     * token is present only when [setUserScope] has been called with a non-null value.
     */
    private fun format(kind: String, label: String, tags: ObservabilityTags): String = buildString {
        append("kind="); append(kind)
        append(" msg="); append(label)
        currentUserScope?.let { append(" user="); append(it) }
        for ((k, v) in tags) {
            append(' '); append(k); append('='); append(scrub(v))
        }
    }

    private fun scrub(value: String): String =
        if (PII_PATTERNS.any { it.containsMatchIn(value) }) "[redacted]" else value

    private companion object {
        /**
         * Values matching any of these patterns are rewritten to `[redacted]` before
         * leaving the process. Patterns err on the side of over-scrubbing because a
         * PIPA regression is far more expensive than a debug-log false positive.
         */
        private val PII_PATTERNS: List<Regex> = listOf(
            // Email — RFC-5322 superset.
            Regex("""\b[\w.+-]+@[\w-]+\.[\w.-]+\b"""),
            // JWT (three dot-separated base64 segments, at least 8 chars each).
            Regex("""\bey[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"""),
            // Bearer / OAuth access-token keywords followed by a long opaque string.
            Regex("""(?i)\b(Bearer|access_token|refresh_token)[=:\s]+[A-Za-z0-9._+/=-]{16,}"""),
        )
    }
}
