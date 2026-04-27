package com.becalm.android.unit.data.remote.interceptor

import com.becalm.android.data.remote.interceptor.RetryInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class RetryInterceptorSpecTest {

    @Test
    fun `SYNC-003 closes retryable response before proceeding again on same call`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("transient"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(RetryInterceptor())
                .build()

            client.newCall(Request.Builder().url(server.url("/v1/raw_ingestion_events:batch")).build())
                .execute()
                .use { response ->
                    assertEquals(200, response.code)
                    assertEquals("ok", response.body!!.string())
                }

            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
