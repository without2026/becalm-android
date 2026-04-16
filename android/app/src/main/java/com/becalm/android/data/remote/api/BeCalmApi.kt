package com.becalm.android.data.remote.api

import com.becalm.android.data.remote.dto.BatchUploadRequest
import com.becalm.android.data.remote.dto.BatchUploadResponse
import com.becalm.android.data.remote.dto.CommitmentDto
import com.becalm.android.data.remote.dto.CommitmentsResponse
import com.becalm.android.data.remote.dto.PatchActionStateRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

// spec: api-contract.yml — Railway API endpoints

interface BeCalmApi {

    // spec: ING-004 — batch upload raw ingestion events
    // spec: ING-014 — max 100 events per batch, max 1 MiB body
    // spec: ING-015 — server-side idempotency by (user_id, client_event_id)
    @POST("v1/raw_ingestion_events:batch")
    suspend fun batchUploadEvents(
        @Header("Authorization") bearerToken: String,
        @Body request: BatchUploadRequest
    ): Response<BatchUploadResponse>

    // spec: CMT-010, TDY-004 — list commitments with filters + cursor pagination
    @GET("v1/commitments")
    suspend fun getCommitments(
        @Header("Authorization") bearerToken: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 20,
        @Query("since") since: String? = null,
        @Query("person_ref") personRef: String? = null,
        @Query("direction") direction: String? = null,
        @Query("action_state") actionState: String? = null
    ): Response<CommitmentsResponse>

    // spec: CMT-003
    @GET("v1/commitments/{id}")
    suspend fun getCommitment(
        @Header("Authorization") bearerToken: String,
        @Path("id") id: String
    ): Response<SingleCommitmentResponse>

    // spec: CMT-005..CMT-007
    @PATCH("v1/commitments/{id}")
    suspend fun patchCommitmentActionState(
        @Header("Authorization") bearerToken: String,
        @Path("id") id: String,
        @Body request: PatchActionStateRequest
    ): Response<SingleCommitmentResponse>
}

data class SingleCommitmentResponse(val data: CommitmentDto)
