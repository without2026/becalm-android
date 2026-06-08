package com.becalm.android.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.datetime.Instant

/**
 * Response body for GET /v1/processing_status.
 *
 * This aggregate is an operational/recovery signal. It must not be used as a
 * canonical source for next-action business ranking.
 */
@JsonClass(generateAdapter = true)
public data class ProcessingStatusResponseDto(
    @field:Json(name = "status") val status: String,
    @field:Json(name = "latest_status") val latestStatus: String,
    @field:Json(name = "processing_count") val processingCount: Int,
    @field:Json(name = "review_required_count") val reviewRequiredCount: Int,
    @field:Json(name = "failed_count") val failedCount: Int,
    @field:Json(name = "oldest_started_at") val oldestStartedAt: Instant? = null,
    @field:Json(name = "retryable") val retryable: Boolean,
    @field:Json(name = "next_retry_at") val nextRetryAt: Instant? = null,
    @field:Json(name = "retry_after_seconds") val retryAfterSeconds: Long? = null,
)
