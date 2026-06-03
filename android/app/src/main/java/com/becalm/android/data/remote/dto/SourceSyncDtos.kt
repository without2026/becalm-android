package com.becalm.android.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
public data class SourceSyncJobResponse(
    @field:Json(name = "job_id") val jobId: String,
    @field:Json(name = "status") val status: String,
    @field:Json(name = "accepted") val accepted: Boolean,
    @field:Json(name = "retry_after_seconds") val retryAfterSeconds: Long? = null,
    @field:Json(name = "synced") val synced: Int = 0,
    @field:Json(name = "provider") val provider: String? = null,
    @field:Json(name = "capability") val capability: String? = null,
    @field:Json(name = "source_connection_id") val sourceConnectionId: String? = null,
    @field:Json(name = "attempts") val attempts: Int = 0,
    @field:Json(name = "error_code") val errorCode: String? = null,
    @field:Json(name = "error_message") val errorMessage: String? = null,
    @field:Json(name = "stage") val stage: String? = null,
    @field:Json(name = "progress") val progress: Double? = null,
    @field:Json(name = "message") val message: String? = null,
    @field:Json(name = "sync_mode") val syncMode: String? = null,
    @field:Json(name = "has_more_pages") val hasMorePages: Boolean = false,
    @field:Json(name = "backfill_complete") val backfillComplete: Boolean? = null,
)
