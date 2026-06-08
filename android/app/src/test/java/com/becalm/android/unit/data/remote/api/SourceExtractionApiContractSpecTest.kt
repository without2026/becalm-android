package com.becalm.android.unit.data.remote.api

import com.becalm.android.core.util.addBecalmAdapters
import com.becalm.android.data.auth.AuthFailureSessionInvalidator
import com.becalm.android.data.remote.api.ApiFactory
import com.becalm.android.data.remote.api.SourceExtractionApi
import com.becalm.android.data.remote.dto.CommitmentExtractionJobCreateRequest
import com.becalm.android.data.remote.dto.ExtractionUploadPrepareRequest
import com.becalm.android.data.remote.interceptor.AuthTokenProvider
import com.becalm.android.data.remote.interceptor.IdempotencyKeyProvider
import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceExtractionApiContractSpecTest {

    @Test
    fun `direct upload prepare and job create use backend JSON contract`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(PREPARE_RESPONSE_JSON),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody(JOB_ACCEPTED_RESPONSE_JSON),
        )
        server.start()
        try {
            val api = sourceExtractionApi(server)

            val prepare = api.prepareCommitmentExtractionUpload(
                ExtractionUploadPrepareRequest(
                    inputModality = "image",
                    sourceType = "message_screenshot",
                    rawEventId = "raw-1",
                    contentType = "image/png",
                    contentLength = 68,
                ),
            )
            val job = api.createCommitmentExtractionJob(
                CommitmentExtractionJobCreateRequest(
                    inputModality = "image",
                    sourceType = "message_screenshot",
                    clientEventId = "client-1",
                    rawEventId = "raw-1",
                    timestamp = "2026-06-05T00:00:00Z",
                    storageRef = requireNotNull(prepare.body()).storageRef,
                    eventTitle = "thread.png",
                ),
            )

            assertTrue(prepare.isSuccessful)
            assertEquals(202, job.code())

            val prepareRequest = server.takeRequest()
            assertEquals("POST", prepareRequest.method)
            assertEquals("/v1/extractions/commitments/uploads:prepare", prepareRequest.path)
            assertTrue(prepareRequest.body.readUtf8().contains("\"content_length\":68"))

            val jobRequest = server.takeRequest()
            assertEquals("POST", jobRequest.method)
            assertEquals("/v1/extractions/commitments/jobs", jobRequest.path)
            val jobBody = jobRequest.body.readUtf8()
            assertTrue(jobBody.contains("\"storage_ref\""))
            assertTrue(jobBody.contains("\"raw_event_id\":\"raw-1\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `signed upload uses absolute storage URL without railway bearer token`() = runTest {
        val railway = MockWebServer()
        val storage = MockWebServer()
        storage.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        railway.start()
        storage.start()
        try {
            val api = sourceExtractionApi(railway)
            val media = MultipartBody.Part.createFormData(
                "file",
                "smoke.png",
                "png".toRequestBody("application/octet-stream".toMediaType()),
            )

            val response = api.uploadExtractionMediaToSignedUrl(
                signedUploadUrl = storage.url("/upload/sign/extraction-jobs/path?token=signed-token")
                    .newBuilder()
                    .host("127.0.0.1")
                    .build()
                    .toString(),
                file = media,
            )

            assertTrue(response.isSuccessful)
            val request = storage.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/upload/sign/extraction-jobs/path?token=signed-token", request.path)
            assertNull(request.getHeader("Authorization"))
            assertTrue(requireNotNull(request.getHeader("Content-Type")).startsWith("multipart/form-data"))
        } finally {
            railway.shutdown()
            storage.shutdown()
        }
    }

    private fun sourceExtractionApi(server: MockWebServer): SourceExtractionApi {
        val client = ApiFactory.createOkHttpClient(
            authProvider = StaticAuthTokenProvider,
            authFailureSessionInvalidator = NoOpAuthFailureSessionInvalidator,
            idempotencyProvider = StaticIdempotencyKeyProvider,
            railwayHost = server.url("/").host,
            isDebug = false,
        )
        val moshi = Moshi.Builder()
            .addBecalmAdapters()
            .build()
        return ApiFactory.createSourceExtractionApi(
            ApiFactory.createRetrofit(
                baseUrl = server.url("/").toString(),
                okHttp = client,
                moshi = moshi,
            ),
        )
    }

    private object StaticAuthTokenProvider : AuthTokenProvider {
        override fun currentAccessToken(): String = "railway-token"

        override suspend fun refresh(previousAccessToken: String): AuthTokenProvider.RefreshResult =
            AuthTokenProvider.RefreshResult.Failed
    }

    private object NoOpAuthFailureSessionInvalidator : AuthFailureSessionInvalidator {
        override suspend fun invalidate() = Unit
    }

    private object StaticIdempotencyKeyProvider : IdempotencyKeyProvider {
        override fun generate(): String = "idem-1"
    }

    private companion object {
        private const val PREPARE_RESPONSE_JSON = """
            {
              "raw_event_id": "raw-1",
              "job_id": "job-1",
              "bucket": "extraction-jobs",
              "path": "user-1/job-1/image.png",
              "content_type": "image/png",
              "media_kind": "image",
              "signed_upload_url": "https://storage.example/upload/sign/path?token=signed-token",
              "upload_token": "signed-token",
              "upload_content_type": "application/octet-stream",
              "storage_ref": {
                "bucket": "extraction-jobs",
                "path": "user-1/job-1/image.png",
                "content_type": "image/png",
                "media_kind": "image",
                "raw_event_id": "raw-1"
              }
            }
        """
        private const val JOB_ACCEPTED_RESPONSE_JSON = """
            {
              "raw_event_id": "raw-1",
              "items": [],
              "completion_signals": [],
              "source_event_participants": [],
              "model": "pending",
              "region": "pending",
              "raw_model_text": null,
              "job_id": "job-1",
              "status": "pending",
              "retry_after_seconds": 10
            }
        """
    }
}
