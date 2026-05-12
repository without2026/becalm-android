package com.becalm.android.data.remote.api

import com.becalm.android.data.remote.dto.SourceExtractionResponse
import com.becalm.android.data.remote.dto.MeetingSpeakerPreviewResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/**
 * Retrofit interface for Railway's source-neutral commitment extraction endpoint.
 *
 * Base URL: `${BECALM_API_BASE_URL}` (BuildConfig; injected via the same [retrofit2.Retrofit]
 * instance as [RailwayApi] — see [com.becalm.android.core.di.NetworkModule]).
 *
 * Authentication: every call carries `Authorization: Bearer <supabase_jwt>` injected by
 * [com.becalm.android.data.remote.interceptor.AuthInterceptor].
 *
 * ## Timeouts
 * Audio uploads can reach 60 MiB (120-minute AAC, api-contract.yml max_body_bytes=62914560).
 * The Retrofit client used for this interface must be configured with [HttpTimeouts.SourceExtraction]
 * (connect=30s, read=180s, write=180s) to accommodate slow upload links without premature
 * read/write timeout on the server's streaming response.
 *
 * ## Idempotency
 * The `client_event_id` part carries the same UUID used in the corresponding
 * `/v1/raw_ingestion_events:batch` upload. Railway deduplicates on (user_id, client_event_id)
 * and returns the cached extraction result on duplicate submissions without re-running Gemini.
 *
 * Spec refs: VOI-001, VOI-002, VOI-003, VOI-006, VOI-007.
 */
public interface SourceExtractionApi {

    /**
     * Uploads normalized source content to Railway for server-side business-item extraction.
     * Audio sources pass [audio], image sources pass [image]. Transcript/manual text inputs are
     * intentionally unsupported by this public endpoint.
     *
     * The audio bytes are streamed from a [ContentResolver] input stream directly into this
     * multipart part — no temp-file copy on device (VOI-007).
     *
     * ## Response handling
     * - HTTP 200: `items` extracted; caller persists the current action subset into Room
     *   commitments and updates the raw event metadata.
     * - HTTP 401: [com.becalm.android.data.remote.interceptor.AuthInterceptor] attempts refresh;
     *   second 401 propagates to caller for permanent failure handling.
     * - HTTP 403: server-side PIPA consent audit failed; caller marks event `sync_status=failed`.
     * - HTTP 413: audio exceeds 60 MiB limit; retryable=false, caller quarantines.
     * - HTTP 422: audio exceeds 120 minutes or decoding error; retryable=false, quarantine.
     * - HTTP 429/500/502/503: transient; caller retries with exponential backoff (VOI-006).
     *
     * @param audio          Optional audio file as a multipart binary part (name="audio").
     *                       Content type should be "audio/m4a" or "audio/&#42;".
     * @param image          Optional image file as a multipart binary part (name="image").
     *                       Content type should be image/png, image/jpeg, or image/webp.
     * @param inputModality  One of audio or image.
     * @param sourceType     Source type of the raw event.
     * @param clientEventId  UUID idempotency key matching the raw_ingestion_event row.
     * @param rawEventId     Server-assigned UUID of the raw_ingestion_event to update.
     * @param durationSeconds Optional duration of the audio file in seconds.
     * @param timestamp      ISO-8601 timestamp of when the recording occurred.
     * @param counterpartyRef      Optional canonicalized counterparty identifier.
     * @param eventTitle     Optional MediaStore TITLE of the recording.
     * @param selfSpeakerId  Optional confirmed user speaker label for multi-party meeting
     *                       audio, e.g. "SPEAKER_01". Null until the meeting review UI
     *                       confirms speaker identity.
     * @param speakerMappings Optional JSON array of confirmed speaker/person mappings for
     *                        meeting audio review. Null until the review UI is wired.
     * @param speakerPreviewId Optional server-side transient preview token. When present for
     *                         meeting audio, Railway reuses the already billed CLOVA transcript.
     *
     * Spec refs: VOI-001, VOI-002, VOI-003, VOI-006, VOI-007.
     */
    @Multipart
    @POST("v1/extractions/commitments")
    public suspend fun commitmentExtract(
        @Part audio: MultipartBody.Part?,
        @Part image: MultipartBody.Part?,
        @Part("input_modality") inputModality: RequestBody,
        @Part("source_type") sourceType: RequestBody,
        @Part("client_event_id") clientEventId: RequestBody,
        @Part("raw_event_id") rawEventId: RequestBody,
        @Part("duration_seconds") durationSeconds: RequestBody?,
        @Part("timestamp") timestamp: RequestBody,
        @Part("counterparty_ref") counterpartyRef: RequestBody?,
        @Part("event_title") eventTitle: RequestBody?,
        @Part("folder") folder: RequestBody?,
        @Part("conversation_ref") conversationRef: RequestBody?,
        @Part("previous_thread_context") previousThreadContext: RequestBody?,
        @Part("self_speaker_id") selfSpeakerId: RequestBody?,
        @Part("speaker_mappings") speakerMappings: RequestBody?,
        @Part("speaker_preview_id") speakerPreviewId: RequestBody?,
    ): Response<SourceExtractionResponse>

    @Multipart
    @POST("v1/extractions/meeting_speaker_preview")
    public suspend fun meetingSpeakerPreview(
        @Part audio: MultipartBody.Part,
        @Part("raw_event_id") rawEventId: RequestBody,
        @Part("duration_seconds") durationSeconds: RequestBody,
    ): Response<MeetingSpeakerPreviewResponse>
}
