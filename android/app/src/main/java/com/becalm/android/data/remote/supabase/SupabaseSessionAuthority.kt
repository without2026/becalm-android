package com.becalm.android.data.remote.supabase

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.util.Base64

/**
 * Local cache hygiene for persisted Supabase sessions.
 *
 * This does not validate JWT signatures. The backend remains the authority for auth. The client
 * only decodes the non-secret payload to ensure an encrypted cached session belongs to the
 * Supabase project compiled into the current APK before attaching it to Railway requests.
 */
internal object SupabaseSessionAuthority {
    private val payloadType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val payloadAdapter: JsonAdapter<Map<String, Any?>> = Moshi.Builder().build().adapter(payloadType)

    fun expectedIssuerFor(supabaseUrl: String): String? {
        val normalized = normalizeUrl(supabaseUrl) ?: return null
        return "$normalized/auth/v1"
    }

    fun issuerForAccessToken(accessToken: String): String? {
        val payloadSegment = accessToken.split('.', limit = 3).getOrNull(1) ?: return null
        val payloadJson = decodeBase64Url(payloadSegment) ?: return null
        val payload = runCatching { payloadAdapter.fromJson(payloadJson) }.getOrNull() ?: return null
        return (payload["iss"] as? String)?.trim()?.takeIf { it.isNotBlank() }
    }

    fun hasExpectedIssuer(accessToken: String, expectedIssuer: String): Boolean =
        issuerForAccessToken(accessToken) == expectedIssuer.trim().trimEnd('/')

    private fun decodeBase64Url(segment: String): String? =
        runCatching {
            val padding = (4 - segment.length % 4) % 4
            val padded = segment + "=".repeat(padding)
            String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull()

    private fun normalizeUrl(url: String): String? =
        url.trim().trimEnd('/').takeIf { it.isNotBlank() }
}
