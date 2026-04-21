package com.becalm.android.data.remote.gmail

import com.becalm.android.core.result.BecalmResult
import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.Base64

/**
 * Unit tests for [GmailClientImpl] covering the EMAIL-001 / EMAIL-004 / EMAIL-005
 * wire-level contract:
 *
 *   - [GmailClient.listMessagesFullSyncForLabel] serialises the
 *     [GmailLabelScope.queryString] into a URL-encoded `q=` parameter.
 *   - [GmailClient.getMessage] requests `format=full` and walks the MIME tree
 *     into `bodyPlain` / `bodyHtml` / `attachmentsMeta`.
 *   - `Message-Id` / `In-Reply-To` / `References` headers (case-insensitive) are
 *     surfaced onto the [GmailMessage] DTO.
 *
 * Strategy: interceptor-based fake. A request-capturing OkHttp interceptor
 * records the URL the client tried to hit and returns a stubbed JSON body.
 * This avoids patching the hard-coded `GMAIL_BASE_URL` and keeps the test
 * process-local (no sockets, no threads).
 */
class GmailClientImplTest {

    private val moshi: Moshi = Moshi.Builder().build()

    /** Interceptor that records the last URL and returns a stub body per URL prefix. */
    private class StubInterceptor(
        private val responder: (Request) -> String,
    ) : Interceptor {
        val capturedUrls: MutableList<String> = mutableListOf()
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            capturedUrls += req.url.toString()
            val json = responder(req)
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private val authProvider: GoogleAuthTokenProvider = object : GoogleAuthTokenProvider {
        override fun currentToken(): String = "test-token"
    }

    private lateinit var interceptor: StubInterceptor
    private lateinit var client: GmailClientImpl

    private fun build(responder: (Request) -> String) {
        interceptor = StubInterceptor(responder)
        val http = OkHttpClient.Builder().addInterceptor(interceptor).build()
        client = GmailClientImpl(http, moshi, authProvider)
    }

    @Before
    fun init() {
        // Default responder returns an empty messages.list body so any unrelated
        // request in the setup path does not throw.
        build { """{"messages":[],"nextPageToken":null}""" }
    }

    // ── Full-sync query encoding ─────────────────────────────────────────────

    @Test
    fun `listMessagesFullSyncForLabel inbox excludes categories`() = runTest {
        build { """{"messages":[{"id":"x"}],"nextPageToken":null}""" }

        val result = client.listMessagesFullSyncForLabel(GmailLabelScope.INBOX, pageToken = null)

        assertTrue(result is BecalmResult.Success)
        assertEquals(listOf("x"), (result as BecalmResult.Success).value.messageIds)

        val url = interceptor.capturedUrls.single()
        assertTrue("url must contain q= parameter: $url", url.contains("?q="))
        // URL-decoded query must match GmailLabelScope.INBOX.queryString exactly.
        val encodedQuery = url.substringAfter("?q=").substringBefore('&')
        val decoded = URLDecoder.decode(encodedQuery, Charsets.UTF_8.name())
        assertEquals(GmailLabelScope.INBOX.queryString, decoded)
        // Sanity-check the negative filters the spec calls out by name.
        assertTrue(decoded.contains("-category:promotions"))
        assertTrue(decoded.contains("-category:social"))
        assertTrue(decoded.contains("-category:updates"))
        assertTrue(decoded.contains("-category:forums"))
        assertTrue(decoded.contains("-in:spam"))
        assertTrue(decoded.contains("-in:trash"))
        assertTrue(decoded.contains("-in:drafts"))
    }

    @Test
    fun `listMessagesFullSyncForLabel sent excludes drafts and trash`() = runTest {
        build { """{"messages":[],"nextPageToken":"next-page"}""" }

        client.listMessagesFullSyncForLabel(GmailLabelScope.SENT, pageToken = null)

        val url = interceptor.capturedUrls.single()
        val encodedQuery = url.substringAfter("?q=").substringBefore('&')
        val decoded = URLDecoder.decode(encodedQuery, Charsets.UTF_8.name())
        assertEquals(GmailLabelScope.SENT.queryString, decoded)
        assertTrue(decoded.contains("label:sent"))
        assertTrue(decoded.contains("-in:trash"))
        assertTrue(decoded.contains("-in:drafts"))
        assertFalse(decoded.contains("category"))
    }

    @Test
    fun `listMessagesFullSyncForLabel includes pageToken when provided`() = runTest {
        build { """{"messages":[],"nextPageToken":null}""" }

        client.listMessagesFullSyncForLabel(GmailLabelScope.INBOX, pageToken = "abc123")

        val url = interceptor.capturedUrls.single()
        assertTrue("url must contain pageToken: $url", url.contains("&pageToken=abc123"))
    }

    // ── getMessage format=full ───────────────────────────────────────────────

    @Test
    fun `getMessage uses format full`() = runTest {
        build { minimalMessageJson() }

        client.getMessage("msg1")

        val url = interceptor.capturedUrls.single()
        assertTrue("must hit messages/msg1 endpoint: $url", url.contains("/messages/msg1"))
        assertTrue("must request format=full: $url", url.contains("format=full"))
        assertFalse("must NOT request format=metadata: $url", url.contains("format=metadata"))
    }

    // ── Multipart parsing ────────────────────────────────────────────────────

    @Test
    fun `getMessage parsesMultipartBodyAndAttachments`() = runTest {
        build { multipartMessageJson() }

        val result = client.getMessage("mm1")

        assertTrue(result is BecalmResult.Success)
        val msg = (result as BecalmResult.Success).value
        assertEquals("Hello plain body.", msg.bodyPlain)
        assertEquals("<p>Hello HTML body.</p>", msg.bodyHtml)
        assertEquals(1, msg.attachmentsMeta.size)
        val att = msg.attachmentsMeta.single()
        assertEquals("report.pdf", att.filename)
        assertEquals("application/pdf", att.mime)
        assertEquals(12345L, att.sizeBytes)
    }

    // ── Headers parsing ──────────────────────────────────────────────────────

    @Test
    fun `getMessage readsReplyHeaders`() = runTest {
        build { multipartMessageJson() }

        val result = client.getMessage("mm1")

        val msg = (result as BecalmResult.Success).value
        assertEquals("Subject Line", msg.subject)
        assertEquals("Alice <alice@example.com>", msg.from)
        assertEquals("Bob <bob@example.com>, Carol <carol@example.com>", msg.to)
        assertEquals(listOf("Bob <bob@example.com>", "Carol <carol@example.com>"), msg.toAddresses)
        assertEquals("<mid-abc@example.com>", msg.messageIdHeader)
        assertEquals("<parent-abc@example.com>", msg.inReplyTo)
        assertEquals("<r1@x> <r2@x>", msg.references)
        // rawHeadersJson is a JSON array carrying every header verbatim.
        assertNotNull(msg.rawHeadersJson)
        assertTrue(msg.rawHeadersJson.startsWith("["))
    }

    @Test
    fun `getMessage message id header case insensitive`() = runTest {
        // Gmail sometimes returns "Message-ID" (uppercase D). Ensure case-insensitive match.
        build { messageWithHeaderName("Message-ID") }

        val result = client.getMessage("mm2")
        val msg = (result as BecalmResult.Success).value
        assertEquals("<case-check@example.com>", msg.messageIdHeader)
    }

    // ── Fixture builders ─────────────────────────────────────────────────────

    private fun minimalMessageJson(): String = """
        {
          "id": "msg1",
          "snippet": "snip",
          "internalDate": 1700000000000,
          "payload": { "mimeType": "text/plain", "headers": [], "body": { "size": 0 } },
          "labelIds": ["INBOX"]
        }
    """.trimIndent()

    private fun encodeBase64Url(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun multipartMessageJson(): String {
        val plain = encodeBase64Url("Hello plain body.")
        val html = encodeBase64Url("<p>Hello HTML body.</p>")
        return """
            {
              "id": "mm1",
              "snippet": "preview",
              "internalDate": 1700000100000,
              "labelIds": ["INBOX"],
              "payload": {
                "mimeType": "multipart/mixed",
                "headers": [
                  {"name": "Subject", "value": "Subject Line"},
                  {"name": "From", "value": "Alice <alice@example.com>"},
                  {"name": "To", "value": "Bob <bob@example.com>, Carol <carol@example.com>"},
                  {"name": "Message-Id", "value": "<mid-abc@example.com>"},
                  {"name": "In-Reply-To", "value": "<parent-abc@example.com>"},
                  {"name": "References", "value": "<r1@x> <r2@x>"}
                ],
                "parts": [
                  {
                    "mimeType": "multipart/alternative",
                    "parts": [
                      {
                        "mimeType": "text/plain",
                        "body": {"size": 17, "data": "$plain"}
                      },
                      {
                        "mimeType": "text/html",
                        "body": {"size": 23, "data": "$html"}
                      }
                    ]
                  },
                  {
                    "mimeType": "application/pdf",
                    "filename": "report.pdf",
                    "body": {"size": 12345, "attachmentId": "att-1"}
                  }
                ]
              }
            }
        """.trimIndent()
    }

    private fun messageWithHeaderName(headerName: String): String = """
        {
          "id": "mm2",
          "snippet": "x",
          "internalDate": 1700000200000,
          "labelIds": ["INBOX"],
          "payload": {
            "mimeType": "text/plain",
            "headers": [
              {"name": "$headerName", "value": "<case-check@example.com>"}
            ],
            "body": {"size": 0}
          }
        }
    """.trimIndent()
}
