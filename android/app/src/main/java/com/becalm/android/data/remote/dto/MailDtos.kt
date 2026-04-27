package com.becalm.android.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Response body for `POST /v1/mail_sources:sync`.
 */
@JsonClass(generateAdapter = true)
public data class MailSyncResponse(
    @field:Json(name = "synced") val synced: Int,
)

/**
 * Response body for `GET /v1/oauth/mail/{provider}:start`.
 */
@JsonClass(generateAdapter = true)
public data class MailOAuthStartResponse(
    @field:Json(name = "provider") val provider: String,
    @field:Json(name = "authorization_url") val authorizationUrl: String,
    @field:Json(name = "redirect_uri") val redirectUri: String,
    @field:Json(name = "state") val state: String,
)

/**
 * Response body for `GET /v1/oauth/mail/{provider}:status`.
 */
@JsonClass(generateAdapter = true)
public data class MailOAuthStatusResponse(
    @field:Json(name = "provider") val provider: String,
    @field:Json(name = "connected") val connected: Boolean,
    @field:Json(name = "account_email") val accountEmail: String? = null,
    @field:Json(name = "display_name") val displayName: String? = null,
)
