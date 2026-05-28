package com.becalm.android.data.remote.supabase

import kotlinx.datetime.Instant

/**
 * Domain representation of an authenticated Supabase session.
 *
 * This is the canonical in-process session object. It is persisted by [SupabaseSessionStore]
 * (AUTH-004 invariant: stored encrypted via Android Keystore — owned by SP-15's
 * `EncryptedTokenStore`). Raw tokens are never held in plaintext memory beyond
 * the lifetime of a single call stack.
 *
 * @param accessToken Short-lived JWT used in `Authorization: Bearer` headers for Railway API calls.
 * @param refreshToken Long-lived opaque token used to obtain a new [accessToken] on expiry.
 * @param userId Supabase UUID for the authenticated user (UUID v4 string).
 * @param email The email address associated with the authenticated account, blank for phone-only auth.
 * @param expiresAt The [Instant] at which [accessToken] expires; evaluated by the
 *   `AuthInterceptor` (SP-05) to decide when a proactive refresh is needed.
 * @param phone The verified phone number associated with the authenticated account, when present.
 * @param authProvider The provider that produced this session. Defaults to email for old stored sessions.
 * @param profileName Best-effort auth/provider profile name used to prefill first-run identity.
 */
public data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String,
    val expiresAt: Instant,
    val phone: String? = null,
    val authProvider: SupabaseAuthProvider = SupabaseAuthProvider.EMAIL,
    val profileName: String? = null,
)

public enum class SupabaseAuthProvider(public val wireValue: String) {
    EMAIL("email"),
    GOOGLE("google"),
    PHONE("phone"),
    ;

    public companion object {
        public fun fromWire(value: String?): SupabaseAuthProvider =
            entries.firstOrNull { it.wireValue == value } ?: EMAIL
    }
}
