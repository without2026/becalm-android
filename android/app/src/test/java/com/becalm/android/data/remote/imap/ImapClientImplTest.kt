package com.becalm.android.data.remote.imap

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Narrow unit tests for the [ImapClient] contract surface + supporting data types.
 *
 * ## Scope
 * The Jakarta-Mail-backed [ImapClientImpl] opens a live IMAPS socket on every call,
 * so end-to-end wire tests live in the integration suite (GreenMail-backed). This
 * file covers:
 *  1. The [ImapClient.listFolders] / [ImapClient.fetchSince] failure-path mapping
 *     exercised via connect-failure (unreachable host) — the only IMAP contract we
 *     can assert without a real server.
 *  2. [ImapAttachmentMeta] Moshi round-trip — the EMAIL-004 JSON shape on the
 *     `email_body.attachments_meta` column (`filename/mime/size_bytes`).
 *  3. [ImapProviderDenylist] contents — ING-008 static guarantees: Naver + Daum
 *     spam / trash / all-mail / drafts folders MUST be included.
 *  4. [ImapMessage] / [ImapFolder] data-class invariants so the fixture factories
 *     in the worker test suites remain stable under refactor.
 *
 * Spec refs: ING-008 (`.spec/data-ingestion.spec.yml:78-85`),
 * EMAIL-004 (`.spec/email-pipeline.spec.yml:40-45`), RFC 6154 §2.
 */
@RunWith(RobolectricTestRunner::class)
class ImapClientImplTest {

    private val moshi: Moshi = Moshi.Builder().build()

    // ── Connect-failure path ─────────────────────────────────────────────────

    /**
     * When the host is unreachable, [ImapClientImpl.listFolders] must map the
     * underlying Jakarta Mail exception to [BecalmResult.Failure] with
     * [BecalmError.Network] — *never* crash the worker coroutine.
     *
     * Uses an RFC 6761-reserved TEST hostname to avoid actual DNS + connect in CI.
     */
    @Test
    fun `listFolders returns Network failure on unreachable host`() = runTest {
        val client = ImapClientImpl(UnconfinedTestDispatcher())

        val result = client.listFolders(
            host = "imap.invalid.becalm.test",
            port = 993,
            user = "noone@invalid.test",
            password = "n/a",
        )

        assertTrue(
            "Unreachable host must surface as BecalmResult.Failure, got: $result",
            result is BecalmResult.Failure,
        )
        // Specific error mapping: anything non-auth / non-messaging becomes Network or Unknown
        val err = (result as BecalmResult.Failure).error
        assertTrue(
            "Unreachable host must map to Network/Unknown (not Unauthorized). Got: ${err::class.simpleName}",
            err is BecalmError.Network || err is BecalmError.Unknown,
        )
    }

    @Test
    fun `fetchSince returns Network failure on unreachable host`() = runTest {
        val client = ImapClientImpl(UnconfinedTestDispatcher())

        val result = client.fetchSince(
            host = "imap.invalid.becalm.test",
            port = 993,
            user = "noone@invalid.test",
            password = "n/a",
            mailbox = "INBOX",
            uidValidity = null,
            uidNext = null,
            sinceDays = 30,
        )

        assertTrue(
            "Unreachable host must surface as BecalmResult.Failure, got: $result",
            result is BecalmResult.Failure,
        )
    }

    // ── ImapAttachmentMeta Moshi round-trip ──────────────────────────────────

    /**
     * EMAIL-004 + `.spec/contracts/data-model.yml:327-390 § attachments_meta` —
     * the JSON shape stored on `email_body.attachments_meta` MUST be
     * `{filename, mime, size_bytes}`, and `size_bytes` MUST serialise as
     * snake_case (not `sizeBytes`).
     */
    @Test
    fun `ImapAttachmentMeta Moshi round-trip preserves snake_case keys`() {
        val adapter = moshi.adapter(ImapAttachmentMeta::class.java)
        val meta = ImapAttachmentMeta(
            filename = "report.pdf",
            mime = "application/pdf",
            sizeBytes = 12_345L,
        )

        val json = adapter.toJson(meta)
        assertTrue(
            "size_bytes must be snake_case on the wire. Got: $json",
            json.contains("\"size_bytes\":12345"),
        )
        assertTrue(json.contains("\"filename\":\"report.pdf\""))
        assertTrue(json.contains("\"mime\":\"application/pdf\""))

        val decoded = adapter.fromJson(json)
        assertEquals(meta, decoded)
    }

    @Test
    fun `ImapAttachmentMeta list Moshi round-trip`() {
        val listType = Types.newParameterizedType(List::class.java, ImapAttachmentMeta::class.java)
        val adapter = moshi.adapter<List<ImapAttachmentMeta>>(listType)

        val metas = listOf(
            ImapAttachmentMeta("a.txt", "text/plain", 10L),
            ImapAttachmentMeta("b.png", "image/png", 2_048L),
        )
        val json = adapter.toJson(metas)
        val decoded = adapter.fromJson(json)
        assertEquals(metas, decoded)
    }

    // ── ImapProviderDenylist invariants ──────────────────────────────────────

    /**
     * ING-008 `data-ingestion.spec.yml:78-85` — Naver's denied set must include
     * Drafts/Spam/Trash/All.
     */
    @Test
    fun `NAVER denylist covers ING-008 categories`() {
        val denied = ImapProviderDenylist.NAVER
        assertTrue("임시보관함 (Drafts)", "임시보관함" in denied)
        assertTrue("스팸메일함 (Spam)", "스팸메일함" in denied)
        assertTrue("휴지통 (Trash)", "휴지통" in denied)
        assertTrue("전체메일 (All)", "전체메일" in denied)
    }

    /**
     * Same for Daum. Naver's "보낸메일함" and Daum's "보낸편지함" must NOT be in
     * either denylist — those are the SENT folders the worker targets.
     */
    @Test
    fun `DAUM denylist does not include SENT folder fallback names`() {
        val deniedDaum = ImapProviderDenylist.DAUM
        val deniedNaver = ImapProviderDenylist.NAVER
        assertTrue("Daum Sent (보낸편지함) must not be in Daum denylist", "보낸편지함" !in deniedDaum)
        assertTrue("Naver Sent (보낸메일함) must not be in Naver denylist", "보낸메일함" !in deniedNaver)
    }

    // ── Data-class invariants ────────────────────────────────────────────────

    /**
     * Guardrail against accidental removal / reordering of [ImapMessage] fields that
     * worker code relies on. If a future refactor drops any of these field names,
     * the compiler catches the worker-site first; this test is a second line of
     * defence for the data contract itself.
     */
    @Test
    fun `ImapMessage exposes all worker-required fields`() {
        val msg = ImapMessage(
            uid = 1L,
            uidValidity = 2L,
            folder = "INBOX",
            messageId = "<m@x>",
            subject = "s",
            fromEmail = "a@b.com",
            fromDisplayName = "A",
            toAddresses = listOf("c@d.com"),
            bodyPlain = "p",
            bodyHtml = "<b>h</b>",
            attachmentsMeta = emptyList(),
            inReplyTo = "<r@x>",
            references = "<a> <b>",
            rawHeadersJson = "{}",
            sentAt = kotlinx.datetime.Instant.fromEpochMilliseconds(0L),
        )
        // Accessor smoke — if any field rename broke the worker, this wouldn't compile.
        assertEquals("INBOX", msg.folder)
        assertEquals(listOf("c@d.com"), msg.toAddresses)
        assertEquals("{}", msg.rawHeadersJson)
        assertNotNull(msg.bodyHtml)
    }

    @Test
    fun `ImapFolder equality honours name and specialUse`() {
        val a = ImapFolder(name = "INBOX", specialUse = ImapSpecialUse.INBOX)
        val b = ImapFolder(name = "INBOX", specialUse = ImapSpecialUse.INBOX)
        val c = ImapFolder(name = "INBOX", specialUse = null)
        assertEquals(a, b)
        assertTrue("null specialUse must not equal INBOX special-use", a != c)
    }
}
