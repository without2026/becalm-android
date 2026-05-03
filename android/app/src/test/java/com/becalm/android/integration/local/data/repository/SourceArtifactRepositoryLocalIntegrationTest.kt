package com.becalm.android.integration.local.data.repository

import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.EmailOriginalArchiveInput
import com.becalm.android.data.repository.SourceArchiveStore
import com.becalm.android.data.repository.SourceArtifactRepositoryImpl
import com.becalm.android.integration.local.LocalIntegrationSupport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SourceArtifactRepositoryLocalIntegrationTest {
    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val store = SourceArchiveStore(LocalIntegrationSupport.appContext())
    private val repository = SourceArtifactRepositoryImpl(
        dao = db.sourceArtifactDao(),
        store = store,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    @After
    fun tearDown() {
        store.deleteUserArchive(USER_ID)
        db.close()
    }

    @Test
    fun `archiveEmailOriginal writes markdown file and metadata`() = runTest {
        val artifact = repository.archiveEmailOriginal(
            input(
                rawEventId = "raw-email-1",
                sourceRef = "message-1",
                occurredAt = Instant.parse("2026-04-28T01:00:00Z"),
                bodyPlain = "안녕하세요.\n내일까지 제안서를 보내주세요.",
            ),
        )

        assertNotNull(artifact)
        val archived = repository.findMarkdownOriginal(USER_ID, "raw-email-1")
        requireNotNull(archived)
        assertTrue(archived.exists)
        assertTrue(requireNotNull(archived.markdown).contains("raw_event_id: raw-email-1"))
        assertTrue(requireNotNull(archived.markdown).contains("내일까지 제안서를 보내주세요."))
        assertEquals(1, repository.summary(USER_ID).count)
        assertTrue(repository.summary(USER_ID).totalBytes > 0L)
    }

    @Test
    fun `deleteBefore removes only older local original files and rows`() = runTest {
        repository.archiveEmailOriginal(
            input(
                rawEventId = "raw-old",
                sourceRef = "message-old",
                occurredAt = Instant.parse("2026-04-01T00:00:00Z"),
                bodyPlain = "old body",
            ),
        )
        repository.archiveEmailOriginal(
            input(
                rawEventId = "raw-new",
                sourceRef = "message-new",
                occurredAt = Instant.parse("2026-04-20T00:00:00Z"),
                bodyPlain = "new body",
            ),
        )

        val result = repository.deleteBefore(USER_ID, Instant.parse("2026-04-10T00:00:00Z"))

        assertEquals(1, result.deletedCount)
        assertEquals(0, result.failedCount)
        assertNull(repository.findMarkdownOriginal(USER_ID, "raw-old"))
        assertNotNull(repository.findMarkdownOriginal(USER_ID, "raw-new"))
        assertEquals(1, repository.summary(USER_ID).count)
    }

    @Test
    fun `findMarkdownOriginal returns bounded preview for large originals`() = runTest {
        repository.archiveEmailOriginal(
            input(
                rawEventId = "raw-large",
                sourceRef = "message-large",
                occurredAt = Instant.parse("2026-04-28T01:00:00Z"),
                bodyPlain = "a".repeat(80_000),
            ),
        )

        val archived = repository.findMarkdownOriginal(USER_ID, "raw-large")

        requireNotNull(archived)
        assertTrue(archived.markdownTruncated)
        assertTrue(requireNotNull(archived.markdown).length <= 64 * 1024)
    }

    private fun input(
        rawEventId: String,
        sourceRef: String,
        occurredAt: Instant,
        bodyPlain: String,
    ): EmailOriginalArchiveInput = EmailOriginalArchiveInput(
        userId = USER_ID,
        rawEventId = rawEventId,
        sourceType = SourceType.NAVER_IMAP,
        sourceRef = sourceRef,
        occurredAt = occurredAt,
        title = "제안서 요청",
        folder = "INBOX",
        fromAddress = "alice@example.com",
        toAddresses = listOf("me@example.com"),
        attachmentsCount = 0,
        bodyPlain = bodyPlain,
        bodyHtml = null,
    )

    private companion object {
        const val USER_ID = "source-artifact-user"
    }
}
