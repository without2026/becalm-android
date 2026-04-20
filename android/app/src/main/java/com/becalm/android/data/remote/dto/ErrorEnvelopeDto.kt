package com.becalm.android.data.remote.dto

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Json

/**
 * Shared error envelope returned by all Railway 4xx/5xx responses.
 *
 * Wire format (api-contract.yml, top-level "Error envelope"):
 *   { error: string, message: string, request_id?: string }
 *
 * Note: the field name is "error" (not "code"). Every Railway endpoint that
 * returns 400/401/404/413/422/429/500/503 uses this shape.
 */
@JsonClass(generateAdapter = true)
public data class ErrorEnvelopeDto(
    /** Machine-readable error token, e.g. "unauthorized", "not_found", "schema_invalid". */
    @field:Json(name = "error") val error: String,

    /** Human-readable description of the error. */
    @field:Json(name = "message") val message: String,

    /**
     * Server-assigned request correlation ID. Present on most 5xx responses.
     * Use for support escalation.
     */
    @field:Json(name = "request_id") val requestId: String? = null,
)
