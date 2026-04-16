package com.becalm.android.data.remote.supabase

/**
 * Immutable configuration for the Supabase project connection.
 *
 * Both values are injected from [BuildConfig] by the Hilt module in SP-06 so that
 * secrets never appear as string literals in production source.
 *
 * @param url The Supabase project URL, e.g. `"https://xyz.supabase.co"`.
 * @param anonKey The public anonymous API key for the Supabase project.
 */
public data class SupabaseConfig(
    val url: String,
    val anonKey: String,
)
