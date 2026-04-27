package com.becalm.android.data.remote.gmail

import android.app.PendingIntent

/**
 * Provider-agnostic OAuth session lifecycle as seen by the UI layer.
 *
 * This enum and result model started in the old Gmail device-side flow, but they are kept
 * as small shared auth-state types for remaining local OAuth consumers.
 *
 * State machine:
 * - [Unauthenticated] — no credential has ever been saved, or [clearGoogle] was invoked.
 * - [Authenticated] — a valid access token is persisted and not yet expired.
 * - [ReauthRequired] — a previous [Authenticated] session has expired or been revoked;
 *   silent refresh failed and the user must start sign-in again.
 */
public enum class OAuthTokenState {
    Unauthenticated,
    Authenticated,
    ReauthRequired,
}

/**
 * Outcome of a foreground sign-in attempt for a local OAuth consumer.
 *
 * Returning a sealed result rather than throwing lets UI callers render error copy
 * without `try/catch` boilerplate.
 */
public sealed interface OAuthSignInResult {

    /** Authorization succeeded with the expected scope; a credential has been persisted. */
    public data object Success : OAuthSignInResult

    /**
     * First-time consent path: the provider returned without an access token but
     * with a [pendingIntent] that, when launched from the UI, presents the Google account
     * picker + scope grant sheet. Callers MUST invoke
     * [androidx.activity.result.ActivityResultLauncher.launch] (or
     * `Activity.startIntentSenderForResult`) with the pending intent and then call
     * the provider sign-in call again once the user completes the flow.
     *
     * Without this branch the provider has no way to drive first-time consent, and
     * [OAuthSignInResult.Failure] (with [FailureReason.USER_CANCELLED]) would be emitted
     * instead, silently breaking ING-006 for any user who has not previously granted
     * the Gmail read-only scope on this device.
     */
    public data class ResolutionRequired(val pendingIntent: PendingIntent) : OAuthSignInResult

    /**
     * Authorization did not complete. [reason] is mapped from the underlying Credential
     * Manager / AuthorizationClient failure; [throwable] is the original cause, surfaced
     * for Sentry reporting on the [FailureReason.UNKNOWN] branch.
     */
    public data class Failure(
        val reason: FailureReason,
        val throwable: Throwable? = null,
    ) : OAuthSignInResult
}

/**
 * Mapped reason for an [OAuthSignInResult.Failure].
 *
 * - [USER_CANCELLED]          — user dismissed the consent sheet.
 * - [PLAY_SERVICES_UNAVAILABLE] — Google Play Services missing / out-of-date on device.
 * - [NETWORK]                 — transient network error reaching Google's auth servers.
 * - [SCOPE_DENIED]            — authorization returned successfully but the granted scope
 *   does not include the requested Gmail read-only scope; the credential is rejected.
 * - [UNKNOWN]                 — any other exception; the original [Throwable] is attached
 *   to the [OAuthSignInResult.Failure] instance for crash reporting.
 */
public enum class FailureReason {
    USER_CANCELLED,
    PLAY_SERVICES_UNAVAILABLE,
    NETWORK,
    SCOPE_DENIED,
    UNKNOWN,
}
