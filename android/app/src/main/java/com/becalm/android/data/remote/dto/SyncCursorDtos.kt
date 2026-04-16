package com.becalm.android.data.remote.dto

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Json

/**
 * Pagination cursor carried in paginated list responses.
 *
 * The Railway API uses opaque cursor strings (not structured objects) in all paginated
 * responses ([PaginatedCommitmentsResponse], [CalendarEventListResponse], [PersonListResponse], etc.).
 * Clients treat these as opaque — store and pass back verbatim.
 *
 * This DTO exists for documentation purposes. Actual cursor values are typed as
 * plain [String] on each response class.
 *
 * CONTRACT NOTE: There is no server-side cursor echo endpoint in api-contract.yml v1.
 * Cursors are managed client-side: persisted to DataStore by each sync worker and
 * passed as query parameters on subsequent requests. No [AckCursorRequest] is needed.
 */
@Suppress("unused")
public object SyncCursorContract {
    /**
     * Cursor query parameter name used across all paginated Railway endpoints.
     * Pass as: `GET /v1/commitments?cursor=<value>`
     */
    public const val QUERY_PARAM: String = "cursor"

    /**
     * Cursor is considered exhausted when the response [PaginatedCommitmentsResponse.hasMore]
     * (or equivalent `has_more` field) is `false`. The cursor value in that response
     * should still be persisted for future delta-sync via the `since` parameter.
     */
    public const val EXHAUSTED_SIGNAL_FIELD: String = "has_more"
}

/**
 * Client-side cursor acknowledgment model.
 *
 * CONTRACT GAP: api-contract.yml v1 does not declare a server-side cursor
 * acknowledgment endpoint (no POST /v1/cursors/ack or equivalent). Cursor state
 * is therefore managed entirely client-side in DataStore.
 *
 * If a server-side cursor echo endpoint is added in a future contract version,
 * this DTO should be updated to match the declared wire format.
 */
@JsonClass(generateAdapter = true)
public data class AckCursorRequest(
    /**
     * The source type whose cursor is being acknowledged.
     * Valid values: see [SourceType] constants.
     */
    @field:Json(name = "source_type") val sourceType: String,

    /**
     * The opaque cursor string last received from the server for this source.
     */
    @field:Json(name = "cursor") val cursor: String,
)
