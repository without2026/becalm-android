package com.becalm.android.data.auth

import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AuthFailureInvalidator"

/**
 * Collapses the local auth state after a permanent Supabase session failure.
 *
 * This is intentionally smaller than user-driven sign-out:
 * - no server-side revoke call,
 * - no Room wipe,
 * - no provider OAuth cleanup.
 *
 * The only goal is to make the app converge on a consistent signed-out state when
 * the backend has definitively rejected the session and further retries are pointless.
 */
public interface AuthFailureSessionInvalidator {
    public suspend fun invalidate()
}

@Singleton
public class AuthFailureSessionInvalidatorImpl @Inject constructor(
    private val sessionStore: SupabaseSessionStore,
    private val userPrefsStore: UserPrefsStore,
    private val logger: Logger,
) : AuthFailureSessionInvalidator {

    override suspend fun invalidate() {
        sessionStore.clear()
        userPrefsStore.setCurrentUserId(null)
        logger.w(TAG, "cleared local session after permanent auth failure")
    }
}
