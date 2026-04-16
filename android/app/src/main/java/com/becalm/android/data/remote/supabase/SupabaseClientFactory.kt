package com.becalm.android.data.remote.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient

/**
 * Creates and configures a [SupabaseClient] for the given [config].
 *
 * This is a plain factory function, not a Hilt `@Provides` method — SP-06 owns the
 * Hilt wiring and calls this function from its `@Module`.
 *
 * Configuration decisions:
 * - `autoLoadFromStorage = false`: token persistence is owned entirely by [SupabaseSessionStore]
 *   (implemented by SP-15's `EncryptedTokenStore`). Letting the SDK manage its own storage
 *   would create a second, unencrypted copy of tokens.
 * - `alwaysAutoRefresh = false`: refresh is triggered by the `AuthInterceptor` (SP-05) on
 *   HTTP 401, giving us full control over retry logic and cancellation semantics.
 * - Postgrest is intentionally NOT installed. All data R/W goes through the Railway backend
 *   (see `contracts_profile: client_primary`). Direct Supabase REST writes are out of scope
 *   for BeCalm Android.
 *
 * @param config Project URL and anonymous key for the Supabase project.
 * @return A fully configured [SupabaseClient] ready for auth operations.
 */
public fun createSupabaseClient(config: SupabaseConfig): SupabaseClient =
    createSupabaseClient(config.url, config.anonKey) {
        install(Auth) {
            autoLoadFromStorage = false
            alwaysAutoRefresh = false
        }
    }
