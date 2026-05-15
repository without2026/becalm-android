package com.becalm.android.data.remote.api

import com.becalm.android.data.remote.dto.BatchUploadRequest
import com.becalm.android.data.remote.dto.BatchUploadResponse
import com.becalm.android.data.remote.dto.CalendarEventListResponse
import com.becalm.android.data.remote.dto.CalendarOAuthStartResponse
import com.becalm.android.data.remote.dto.CalendarOAuthStatusResponse
import com.becalm.android.data.remote.dto.CalendarSyncResponse
import com.becalm.android.data.remote.dto.CommitmentBatchRequestDto
import com.becalm.android.data.remote.dto.CommitmentBatchResponseDto
import com.becalm.android.data.remote.dto.CommitmentParticipantsResponse
import com.becalm.android.data.remote.dto.CommitmentPatchDto
import com.becalm.android.data.remote.dto.PaginatedCommitmentsResponse
import com.becalm.android.data.remote.dto.PatchCommitmentRequest
import com.becalm.android.data.remote.dto.MailOAuthStartResponse
import com.becalm.android.data.remote.dto.MailOAuthStatusResponse
import com.becalm.android.data.remote.dto.MailSyncResponse
import com.becalm.android.data.remote.dto.PersonCommitmentsResponse
import com.becalm.android.data.remote.dto.PersonEventsResponse
import com.becalm.android.data.remote.dto.PersonListResponse
import com.becalm.android.data.remote.dto.PersonMemoryDownloadResponseDto
import com.becalm.android.data.remote.dto.PersonMemoryUploadRequestDto
import com.becalm.android.data.remote.dto.PersonMemoryUploadResponseDto
import com.becalm.android.data.remote.dto.ProductEventsBatchRequest
import com.becalm.android.data.remote.dto.ProductEventsBatchResponse
import com.becalm.android.data.remote.dto.RawIngestionEventsResponse
import com.becalm.android.data.remote.dto.ScheduleEventLinkStatusPatchDto
import com.becalm.android.data.remote.dto.ScheduleEventLinksResponse
import com.becalm.android.data.remote.dto.SingleCommitmentResponse
import com.becalm.android.data.remote.dto.SingleScheduleEventLinkResponse
import com.becalm.android.data.remote.dto.SourceStatusResponseDto
import com.becalm.android.data.remote.dto.SourceEventParticipantPatchRequestDto
import com.becalm.android.data.remote.dto.SourceEventParticipantResponse
import com.becalm.android.data.remote.dto.SourceEventParticipantsResponse
import com.becalm.android.data.remote.dto.SelfIdentityAnchorCreateRequestDto
import com.becalm.android.data.remote.dto.SelfIdentityAnchorPatchRequestDto
import com.becalm.android.data.remote.dto.SelfIdentityAnchorResponseDto
import com.becalm.android.data.remote.dto.SelfIdentityAnchorsResponseDto
import com.becalm.android.data.remote.dto.SourceConnectionPatchRequestDto
import com.becalm.android.data.remote.dto.SourceConnectionResponseDto
import com.becalm.android.data.remote.dto.SourceConnectionsResponseDto
import com.becalm.android.data.remote.dto.UserProfilePatchRequestDto
import com.becalm.android.data.remote.dto.UserProfileResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.PUT
import retrofit2.http.Query

/**
 * Retrofit interface for all Railway API endpoints consumed by the BeCalm Android client.
 *
 * Base URL: `${BECALM_API_BASE_URL}` (BuildConfig; injected via [ApiFactory]).
 * Authentication: every call carries `Authorization: Bearer <supabase_jwt>` injected by
 * [com.becalm.android.data.remote.interceptor.AuthInterceptor].
 *
 * **All endpoints return `Response<T>`** — the repository layer inspects HTTP status codes
 * for retry, auth (401 re-check after interceptor), and idempotency control paths.
 * Bare-`T` return types are intentionally avoided so that non-2xx bodies can be
 * parsed via `response.errorBody()` into [com.becalm.android.data.remote.dto.ErrorEnvelopeDto].
 *
 * ## Idempotency opt-in
 * Endpoints whose contract marks idempotency carry an `@Header("X-BeCalm-Idempotent")`
 * parameter defaulting to `"1"`. The caller passes the default value to signal intent;
 * [com.becalm.android.data.remote.interceptor.IdempotencyInterceptor] strips this sentinel
 * and attaches a real `Idempotency-Key: <uuid>` on the wire.
 *
 * ## Pagination
 * All list endpoints use opaque cursor-based pagination. Pass [cursor] from the previous
 * response as the next request's cursor; stop when `has_more == false`.
 *
 * Spec refs: api-contract.yml, SYNC-001..006, AUTH-007, CMT-003..007, TDY-004..005,
 * SRC-001..003, SRC-006, ING-003..005, ING-011..013.
 */
public interface RailwayApi {

    @POST("v1/analytics/events:batch")
    public suspend fun batchProductEvents(
        @Body request: ProductEventsBatchRequest,
    ): Response<ProductEventsBatchResponse>

    // =========================================================================
    // RAW INGESTION EVENTS
    // =========================================================================

    /**
     * Uploads a batch of raw ingestion events to Railway for LLM extraction.
     *
     * Idempotent per (user_id, client_event_id) — duplicate submissions return HTTP 200
     * without creating a new row. Use when retrying failed uploads.
     *
     * Constraints: max 100 events per batch; max 1 MiB body (HTTP 413 if exceeded).
     *
     * The `X-BeCalm-Idempotent: 1` header opts this request into idempotency key injection
     * by [com.becalm.android.data.remote.interceptor.IdempotencyInterceptor].
     *
     * Possible responses: 200 (partial or full success), 400, 401, 413, 422, 429, 500, 503.
     *
     * Spec refs: ING-003, ING-004, ING-005, ING-011..013, SYNC-001..003, SYNC-005..006.
     *
     * @param idem Opt-in sentinel; keep default `"1"` to enable idempotency key injection.
     * @param request Batch body containing up to 100 [com.becalm.android.data.remote.dto.RawIngestionEventDto].
     */
    @POST("v1/raw_ingestion_events:batch")
    public suspend fun batchUploadRawEvents(
        @Header("X-BeCalm-Idempotent") idem: String = "1",
        @Body request: BatchUploadRequest,
    ): Response<BatchUploadResponse>

    /**
     * Lists backend-persisted raw ingestion events for the current user.
     *
     * Android uses this after backend-managed mail sync so Gmail / Outlook Mail rows written
     * server-side are mirrored into Room and can feed the local person index.
     */
    @GET("v1/raw_ingestion_events")
    public suspend fun getRawIngestionEvents(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("since") since: String? = null,
        @Query("source_type") sourceType: String? = null,
    ): Response<RawIngestionEventsResponse>

    /**
     * Lists backend-persisted source participants for current user.
     *
     * Android mirrors these rows into Room so backend-managed mail/calendar extraction
     * feeds the same person-index worker contract as local source adapters.
     */
    @GET("v1/source_event_participants")
    public suspend fun getSourceEventParticipants(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("since") since: String? = null,
        @Query("source_type") sourceType: String? = null,
        @Query("resolution_status") resolutionStatus: String? = null,
    ): Response<SourceEventParticipantsResponse>

    @PATCH("v1/source_event_participants/{participant_id}")
    public suspend fun patchSourceEventParticipant(
        @Path("participant_id") participantId: String,
        @Body request: SourceEventParticipantPatchRequestDto,
    ): Response<SourceEventParticipantResponse>

    /**
     * Lists backend-persisted commitment/person edges for current user.
     *
     * Android mirrors these rows into Room so commitments extracted on Railway can be
     * indexed into the same person timelines as locally extracted voice commitments.
     */
    @GET("v1/commitment_participants")
    public suspend fun getCommitmentParticipants(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("since") since: String? = null,
        @Query("person_id") personId: String? = null,
        @Query("commitment_id") commitmentId: String? = null,
    ): Response<CommitmentParticipantsResponse>

    @GET("v1/schedule_event_links")
    public suspend fun getScheduleEventLinks(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("since") since: String? = null,
        @Query("status") status: String? = null,
    ): Response<ScheduleEventLinksResponse>

    @PATCH("v1/schedule_event_links/{id}")
    public suspend fun patchScheduleEventLink(
        @Path("id") id: String,
        @Header("X-BeCalm-Idempotent") idem: String = "1",
        @Body request: ScheduleEventLinkStatusPatchDto,
    ): Response<SingleScheduleEventLinkResponse>

    // =========================================================================
    // COMMITMENTS
    // =========================================================================

    /**
     * Lists commitments for the authenticated user with optional filtering and pagination.
     *
     * @param cursor Opaque pagination cursor from the previous response; omit for first page.
     * @param limit Page size; server default is 20.
     * @param since ISO 8601 datetime — returns only commitments updated after this time.
     * @param personId Filter by canonical person id.
     * @param direction Filter by direction: `"give"` or `"take"`.
     * @param actionState Filter by state: `"pending"` | `"reminded"` | `"followed_up"` | `"completed"`.
     *
     * Spec refs: TDY-004, CMT-010.
     */
    @GET("v1/commitments")
    public suspend fun getCommitments(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 20,
        @Query("since") since: String? = null,
        @Query("person_id") personId: String? = null,
        @Query("direction") direction: String? = null,
        @Query("action_state") actionState: String? = null,
    ): Response<PaginatedCommitmentsResponse>

    /**
     * Updates the [actionState] of a single commitment.
     *
     * The `X-BeCalm-Idempotent: 1` header opts this request into idempotency key injection
     * so repeated PATCHes with the same state are deduplicated server-side.
     *
     * Valid [PatchCommitmentRequest.actionState] values: `"pending"` | `"reminded"` |
     * `"followed_up"` | `"completed"`. Returns 422 for unknown values.
     *
     * Spec refs: CMT-005, CMT-006, CMT-007.
     *
     * @param id Supabase-assigned UUID of the commitment.
     * @param idem Opt-in sentinel; keep default `"1"` to enable idempotency key injection.
     * @param request Body containing the new [PatchCommitmentRequest.actionState].
     */
    @PATCH("v1/commitments/{id}")
    public suspend fun patchCommitment(
        @Path("id") id: String,
        @Header("X-BeCalm-Idempotent") idem: String = "1",
        @Body request: PatchCommitmentRequest,
    ): Response<SingleCommitmentResponse>

    /**
     * Expanded-body partial update for `PATCH /v1/commitments/{id}` used by the
     * Stage-5 edit / dispute / soft-delete / supersede flows (EDIT-003..007).
     *
     * Distinct from [patchCommitment] because this body may carry any subset of
     * mutable columns (title, due_at, due_hint, person_ref, direction,
     * quote_disputed, deleted_at, last_edited_*) rather than the single-field
     * [PatchCommitmentRequest.actionState]. A legacy 422 response means the
     * server has not yet shipped the EDIT endpoint extension; the repository
     * treats that as "best-effort" and leaves `sync_status='pending'` so
     * UploadWorker retries.
     *
     * @param id Supabase-assigned UUID of the commitment.
     * @param idem Opt-in sentinel; keep default `"1"` to enable idempotency.
     * @param request Partial [CommitmentPatchDto] body.
     */
    @PATCH("v1/commitments/{id}")
    public suspend fun updateCommitment(
        @Path("id") id: String,
        @Header("X-BeCalm-Idempotent") idem: String = "1",
        @Body request: CommitmentPatchDto,
    ): Response<SingleCommitmentResponse>

    /**
     * Uploads a batch of commitments to Railway (partial-success semantics).
     *
     * Idempotent per (user_id, client_event_id) — duplicate submissions return HTTP 200
     * without creating a new row. Used by [com.becalm.android.worker.UploadWorker] to
     * drain rows whose `sync_status='pending'` after a failed PATCH (CMT-005..007 +
     * commitment-management.spec.yml invariant 3).
     *
     * Constraints: max 100 commitments per batch; max 1 MiB body (HTTP 413 if exceeded).
     *
     * The `X-BeCalm-Idempotent: 1` header opts this request into idempotency key injection
     * by [com.becalm.android.data.remote.interceptor.IdempotencyInterceptor].
     *
     * Possible responses: 200 (partial or full success), 401, 413, 422, 429, 500, 503.
     *
     * Spec refs: CMT-005, CMT-006, CMT-007, SYNC-001.
     *
     * @param idem Opt-in sentinel; keep default `"1"` to enable idempotency key injection.
     * @param request Batch body containing up to 100 [CommitmentBatchRequestDto.commitments].
     */
    @POST("v1/commitments:batch")
    public suspend fun uploadCommitmentsBatch(
        @Header("X-BeCalm-Idempotent") idem: String = "1",
        @Body request: CommitmentBatchRequestDto,
    ): Response<CommitmentBatchResponseDto>

    // =========================================================================
    // SOURCE STATUS
    // =========================================================================

    /**
     * Fetches per-source sync health from Railway and merges it with Android's local
     * source-status cache.
     *
     * The product-facing strip uses SourceType.PRODUCT_SOURCES:
     * voice, gmail, outlook_mail, naver_imap, daum_imap, google_calendar, outlook_calendar.
     * The server may return only the sources it can authoritatively observe; omitted sources
     * keep their current local-derived values (for example, voice runtime state is recorded
     * directly by Android workers).
     *
     * Possible responses: 200, 401 (token refresh), 500 (server error), 503 (upstream unavailable).
     * The client falls back to local [com.becalm.android.data.local.datastore.SyncCursorStore]
     * derivation on any non-2xx — see [com.becalm.android.data.repository.SourceStatusRepository].
     *
     * Spec refs: TDY-003, TDY-008, SMG-001, SMG-002.
     */
    @GET("v1/source_status")
    public suspend fun getSourceStatus(): Response<SourceStatusResponseDto>

    @GET("v1/user_profile")
    public suspend fun getUserProfile(): Response<UserProfileResponseDto>

    @PATCH("v1/user_profile")
    public suspend fun patchUserProfile(
        @Body request: UserProfilePatchRequestDto,
    ): Response<UserProfileResponseDto>

    @GET("v1/self_identity_anchors")
    public suspend fun getSelfIdentityAnchors(): Response<SelfIdentityAnchorsResponseDto>

    @POST("v1/self_identity_anchors")
    public suspend fun createSelfIdentityAnchor(
        @Body request: SelfIdentityAnchorCreateRequestDto,
    ): Response<SelfIdentityAnchorResponseDto>

    @PATCH("v1/self_identity_anchors/{id}")
    public suspend fun patchSelfIdentityAnchor(
        @Path("id") id: String,
        @Body request: SelfIdentityAnchorPatchRequestDto,
    ): Response<SelfIdentityAnchorResponseDto>

    @GET("v1/source_connections")
    public suspend fun getSourceConnections(): Response<SourceConnectionsResponseDto>

    @PATCH("v1/source_connections/{id}")
    public suspend fun patchSourceConnection(
        @Path("id") id: String,
        @Body request: SourceConnectionPatchRequestDto,
    ): Response<SourceConnectionResponseDto>

    // =========================================================================
    // CALENDAR EVENTS
    // =========================================================================

    /**
     * Lists calendar events for the authenticated user, optionally filtered by time.
     *
     * @param cursor Opaque pagination cursor from the previous response.
     * @param since ISO 8601 datetime — returns only events updated after this time.
     * @param limit Page size; server default applies when omitted.
     *
     * Spec refs: TDY-005.
     */
    @GET("v1/calendar_events")
    public suspend fun getCalendarEvents(
        @Query("cursor") cursor: String? = null,
        @Query("since") since: String? = null,
        @Query("limit") limit: Int? = null,
    ): Response<CalendarEventListResponse>

    /**
     * Triggers a server-side Google Calendar sync for the authenticated user.
     *
     * Idempotent in practice — calling multiple times merges rather than duplicates.
     * Returns 429 when the server rate-limits sync requests.
     *
     * Spec refs: TDY-005.
     */
    @POST("v1/calendar_events:sync")
    public suspend fun syncCalendarEvents(): Response<CalendarSyncResponse>

    /**
     * Triggers a server-side Gmail / Outlook Mail sync for the authenticated user.
     *
     * @param provider Optional provider scope: `"gmail"` or `"outlook_mail"`.
     */
    @POST("v1/mail_sources:sync")
    public suspend fun syncMailSource(
        @Query("provider") provider: String? = null,
    ): Response<MailSyncResponse>

    /**
     * Starts the backend-managed calendar OAuth flow for [provider].
     *
     * Returns the provider authorization URL and the Railway callback URI that has been
     * signed with the current user identity.
     */
    @GET("v1/oauth/calendar/{provider}:start")
    public suspend fun startCalendarOAuth(
        @Path("provider") provider: String,
    ): Response<CalendarOAuthStartResponse>

    /**
     * Returns whether the current authenticated user has already completed calendar OAuth
     * for [provider] on the backend.
     */
    @GET("v1/oauth/calendar/{provider}:status")
    public suspend fun getCalendarOAuthStatus(
        @Path("provider") provider: String,
    ): Response<CalendarOAuthStatusResponse>

    /**
     * Starts the backend-managed Gmail / Outlook Mail OAuth flow for [provider].
     */
    @GET("v1/oauth/mail/{provider}:start")
    public suspend fun startMailOAuth(
        @Path("provider") provider: String,
    ): Response<MailOAuthStartResponse>

    /**
     * Returns whether the current authenticated user has completed mail OAuth for [provider].
     */
    @GET("v1/oauth/mail/{provider}:status")
    public suspend fun getMailOAuthStatus(
        @Path("provider") provider: String,
    ): Response<MailOAuthStatusResponse>

    // =========================================================================
    // PERSONS
    // =========================================================================

    /**
     * Lists first-class person records derived from backend relation intelligence.
     *
     * Backend aggregates `persons` with `person_interactions` and
     * `commitment_participants`.
     *
     * @param cursor Opaque pagination cursor from the previous response.
     * @param limit Page size; server default is 20.
     * @param q Substring search applied to display name, email, and phone.
     *
     * Spec refs: SRC-001, SRC-003, SRC-006.
     */
    @GET("v1/persons")
    public suspend fun getPersons(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("q") query: String? = null,
    ): Response<PersonListResponse>

    /**
     * Lists raw ingestion events associated with a specific person.
     *
     * Returns 404 when [personId] is not found or belongs to a different user.
     *
     * @param personId Canonical backend `persons.id` value.
     * @param cursor Opaque pagination cursor from the previous response.
     * @param limit Page size; server default applies when omitted.
     *
     * Spec refs: SRC-002.
     */
    @GET("v1/persons/{person_id}/events")
    public suspend fun getPersonEvents(
        @Path("person_id") personId: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): Response<PersonEventsResponse>

    /**
     * Lists commitments associated with a specific person.
     *
     * Returns 404 when [personId] is not found or belongs to a different user.
     *
     * @param personId Canonical backend `persons.id` value.
     * @param cursor Opaque pagination cursor from the previous response.
     * @param limit Page size; server default applies when omitted.
     *
     * Spec refs: SRC-002.
     */
    @GET("v1/persons/{person_id}/commitments")
    public suspend fun getPersonCommitments(
        @Path("person_id") personId: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): Response<PersonCommitmentsResponse>

    @PUT("v1/persons/{person_id}/memory")
    public suspend fun uploadPersonMemory(
        @Path("person_id") personId: String,
        @Body request: PersonMemoryUploadRequestDto,
    ): Response<PersonMemoryUploadResponseDto>

    @GET("v1/persons/{person_id}/memory")
    public suspend fun getPersonMemory(
        @Path("person_id") personId: String,
    ): Response<PersonMemoryDownloadResponseDto>
}
