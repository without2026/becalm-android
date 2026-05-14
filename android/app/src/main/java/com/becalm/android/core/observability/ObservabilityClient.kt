package com.becalm.android.core.observability

/**
 * Tags attached to an observability event.
 *
 * `Map<String, String>` would be the natural choice but it reifies mutation semantics
 * that most call sites do not need. The type alias keeps the call-site syntax light
 * while allowing a richer type (e.g. `@Serializable` key-enum) in a follow-up refactor
 * without churn at every emission point.
 */
public typealias ObservabilityTags = Map<String, String>

/**
 * Vendor-neutral façade for structured events, breadcrumbs, and exception reports.
 *
 * ## Why not call Firebase directly?
 * ONB-007 and the broader operational playbook require a single abstraction so that
 * (1) debug builds with no configured DSN stay crash-free via a no-op impl,
 * (2) tests can assert emitted events without reaching into a third-party SDK, and
 * (3) the production backend can be swapped (logger → Firebase Crashlytics → OTEL)
 * without touching every call site. PIPA also requires filtering PII out of payloads
 * in one place — concrete implementations scrub tokens / emails before forwarding.
 *
 * ## Event vocabulary
 * `message` strings are stable operational labels (e.g. `onboarding_step_failed`)
 * and MUST NOT contain per-user data — tags carry the structured context. Concrete
 * implementations are expected to reject or scrub tag values that look like bearer
 * tokens or email addresses before shipping them off-device.
 *
 * Implementations live in this package:
 *  - [LoggerObservabilityClient] — always-on Timber/Logger fan-out used by every build.
 *  - [NoopObservabilityClient] — canary fallback for edge cases where even the logger
 *    isn't trusted (disabled-by-flag, tests that do not care about events).
 */
public interface ObservabilityClient {

    /**
     * Emits a named event with structured [tags]. Intended for operational signals
     * that an operator would want to alert on (onboarding failures, auth errors).
     *
     * @param message Stable label — a constant string, never interpolated with PII.
     * @param tags    Structured context; implementations MAY drop entries whose values
     *   look like secrets (JWT, OAuth tokens) or email addresses.
     */
    public fun captureMessage(message: String, tags: ObservabilityTags = emptyMap())

    /**
     * Reports an unexpected [throwable] together with structured [tags].
     *
     * Implementations MUST NOT include the throwable's message or stack frames in
     * contexts that traverse the network unless the project has audited the exception
     * classes for PII leakage. The logger-backed impl logs locally, which is always safe.
     */
    public fun captureException(throwable: Throwable, tags: ObservabilityTags = emptyMap())

    /**
     * Records a lower-priority breadcrumb — context that helps reconstruct the trail
     * leading up to a later [captureMessage] / [captureException] call.
     */
    public fun addBreadcrumb(category: String, message: String, data: ObservabilityTags = emptyMap())

    /**
     * Scopes subsequent events to the given pseudonymous [userId].
     *
     * [userId] MUST be the Supabase UUID, NOT the account email. Pass `null` on
     * sign-out so the next session starts from a clean scope.
     */
    public fun setUserScope(userId: String?)
}
