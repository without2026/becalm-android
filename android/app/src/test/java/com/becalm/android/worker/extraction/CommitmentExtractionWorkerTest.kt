package com.becalm.android.worker.extraction

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.MetricsStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.EmailBodyDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.CommitmentDraftDto
import com.becalm.android.domain.email.EmailPromptBuilder
import com.becalm.android.domain.email.QuotedBlockSplitter
import com.becalm.android.domain.extractor.GeminiNanoExtractor
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [CommitmentExtractionWorker] (Wave 3 plan §6.5).
 *
 * Uses Robolectric so the real [EmailPromptBuilder] can load
 * `res/raw/email_system_prompt.txt` via [android.content.res.Resources]. All DAOs /
 * datastores / [GeminiNanoExtractor] / [Logger] are MockK stubs. [QuotedBlockSplitter] and
 * [EmailPromptBuilder] are real instances — they're pure-Kotlin helpers whose behavior these
 * tests also implicitly cover.
 *
 * Test matrix (one @Test per scenario — plan §5.3):
 * 1. Missing rawEventId input → Result.failure.
 * 2. Missing userId session → Result.failure.
 * 3. Raw event not found in Room → Result.failure.
 * 4. Email body row absent → Result.success (non-email source no-op).
 * 5. Subject-only (bodyPlain and bodyHtml both blank) → Result.success, metric incremented.
 * 6. Happy path: 2 drafts → 2 CommitmentEntity inserts + commitmentsExtractedCount update.
 * 7. AICORE_NOT_AVAILABLE failure → Result.success, no retry (device unsupported).
 * 8. LLM_JSON_PARSE_FAILED failure → Result.retry.
 * 9. AICORE_ERROR failure → Result.retry (generic SDK exception).
 *
 * Spec refs: EMAIL-001, EMAIL-003, EMAIL-005, EMAIL-008.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class CommitmentExtractionWorkerTest {

    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val rawIngestionEventDao: RawIngestionEventDao = mockk()
    private val emailBodyDao: EmailBodyDao = mockk()
    private val commitmentDao: CommitmentDao = mockk()
    private val userPrefsStore: UserPrefsStore = mockk()
    private val metricsStore: MetricsStore = mockk(relaxed = true)
    private val geminiNanoExtractor: GeminiNanoExtractor = mockk()
    private val logger: Logger = mockk(relaxed = true)

    private lateinit var appContext: Context
    private lateinit var worker: CommitmentExtractionWorker

    private val fakeUserId: String = "user-uuid-abc"
    private val fakeRawEventId: String = "raw-event-uuid-xyz"

    private val fakeRawEvent = RawIngestionEventEntity(
        id = fakeRawEventId,
        userId = fakeUserId,
        clientEventId = "client-uuid-001",
        sourceType = "gmail",
        sourceRef = "msg-001",
        personRef = "alice@example.com",
        eventTitle = "Re: Quarterly review",
        eventSnippet = "Looking forward to seeing you",
        durationSeconds = null,
        location = null,
        folder = "INBOX",
        commitmentsExtractedCount = 0,
        timestamp = Instant.fromEpochMilliseconds(1_700_000_000_000),
        syncStatus = "pending",
    )

    private val fakeEmailBody = EmailBodyEntity(
        id = "body-uuid-001",
        rawEventId = fakeRawEventId,
        providerMessageId = "msg-001",
        folder = "INBOX",
        subject = "Re: Quarterly review",
        fromAddress = "manager@example.com",
        toAddresses = """[{"email":"employee@example.com"}]""",
        bodyPlain = "I will send the deck by Friday.\n\nOn Mon Dec 18 John wrote:\n> Can you send the deck?",
        bodyHtml = null,
        attachmentsMeta = null,
        rawHeaders = null,
        parseFailed = false,
        groupEmail = false,
        receivedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
    )

    private val fakeDraftGive = CommitmentDraftDto(
        direction = "give",
        text = "I will send the deck by Friday",
        quote = "I will send the deck by Friday.",
        personRef = "manager@example.com",
        dueAt = null,
        confidence = 0.91f,
    )

    private val fakeDraftTake = CommitmentDraftDto(
        direction = "take",
        text = "Manager will review the deck",
        quote = "will review",
        personRef = "manager@example.com",
        dueAt = null,
        confidence = 0.71f,
    )

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        val promptBuilder = EmailPromptBuilder(appContext)
        val quotedSplitter = QuotedBlockSplitter()

        worker = CommitmentExtractionWorker(
            appContext = appContext,
            workerParams = workerParams,
            rawIngestionEventDao = rawIngestionEventDao,
            emailBodyDao = emailBodyDao,
            commitmentDao = commitmentDao,
            userPrefsStore = userPrefsStore,
            metricsStore = metricsStore,
            promptBuilder = promptBuilder,
            quotedBlockSplitter = quotedSplitter,
            geminiNanoExtractor = geminiNanoExtractor,
            logger = logger,
        )

        // Default: rawEventId input present
        every {
            workerParams.inputData.getString(CommitmentExtractionWorker.KEY_RAW_EVENT_ID)
        } returns fakeRawEventId

        // Default: active user session
        every { userPrefsStore.observeCurrentUserId() } returns flowOf(fakeUserId)

        // Default: raw event and email body both present
        coEvery {
            rawIngestionEventDao.findById(id = fakeRawEventId, userId = fakeUserId)
        } returns fakeRawEvent
        coEvery { rawIngestionEventDao.update(any()) } returns 1
        coEvery { emailBodyDao.getByRawEventId(fakeRawEventId) } returns fakeEmailBody
        coEvery { commitmentDao.insertAll(any()) } returns listOf(1L, 2L)
    }

    // ── T1: Missing rawEventId input → Result.failure ────────────────────────

    @Test
    fun `doWork returns failure when rawEventId input is missing`() = runTest {
        every {
            workerParams.inputData.getString(CommitmentExtractionWorker.KEY_RAW_EVENT_ID)
        } returns null

        val result = worker.doWork()

        assertEquals(Result.failure(), result)
        coVerify(exactly = 0) { geminiNanoExtractor.extract(any(), any()) }
    }

    // ── T2: Missing userId session → Result.failure ──────────────────────────

    @Test
    fun `doWork returns failure when no active userId`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf(null)

        val result = worker.doWork()

        assertEquals(Result.failure(), result)
        coVerify(exactly = 0) {
            rawIngestionEventDao.findById(any(), any())
        }
    }

    // ── T3: Raw event not found in Room → Result.failure ─────────────────────

    @Test
    fun `doWork returns failure when raw event is not found for user`() = runTest {
        coEvery {
            rawIngestionEventDao.findById(id = fakeRawEventId, userId = fakeUserId)
        } returns null

        val result = worker.doWork()

        assertEquals(Result.failure(), result)
        coVerify(exactly = 0) { emailBodyDao.getByRawEventId(any()) }
    }

    // ── T4: Email body row absent → Result.success no-op ─────────────────────

    @Test
    fun `doWork returns success no-op when email body row is missing`() = runTest {
        coEvery { emailBodyDao.getByRawEventId(fakeRawEventId) } returns null

        val result = worker.doWork()

        assertEquals(
            "non-email source (or body not yet persisted) must exit success without retry",
            Result.success(),
            result,
        )
        coVerify(exactly = 0) { geminiNanoExtractor.extract(any(), any()) }
        coVerify(exactly = 0) { metricsStore.incrementSubjectOnlySkipped() }
    }

    // ── T5: Subject-only (bodyPlain + bodyHtml both blank) → metric + success ─

    @Test
    fun `doWork increments subject-only metric when body is blank`() = runTest {
        val subjectOnlyBody = fakeEmailBody.copy(
            bodyPlain = null,
            bodyHtml = null,
        )
        coEvery { emailBodyDao.getByRawEventId(fakeRawEventId) } returns subjectOnlyBody
        coEvery { metricsStore.incrementSubjectOnlySkipped() } just Runs

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { metricsStore.incrementSubjectOnlySkipped() }
        coVerify(exactly = 0) { geminiNanoExtractor.extract(any(), any()) }
    }

    // ── T6: Happy path — 2 drafts → 2 inserts + count update ─────────────────

    @Test
    fun `doWork inserts 2 commitments and updates count on extractor success`() = runTest {
        coEvery { geminiNanoExtractor.extract(any(), any()) } returns
            BecalmResult.Success(listOf(fakeDraftGive, fakeDraftTake))

        val insertedSlot = slot<List<CommitmentEntity>>()
        coEvery { commitmentDao.insertAll(capture(insertedSlot)) } returns listOf(1L, 2L)

        val updatedSlot = slot<RawIngestionEventEntity>()
        coEvery { rawIngestionEventDao.update(capture(updatedSlot)) } returns 1

        val result = worker.doWork()

        assertEquals(Result.success(), result)

        // 2 drafts → 2 entities persisted
        assertEquals(2, insertedSlot.captured.size)
        assertEquals("give", insertedSlot.captured[0].direction)
        assertEquals("take", insertedSlot.captured[1].direction)
        assertEquals(fakeUserId, insertedSlot.captured[0].userId)
        assertEquals(fakeRawEvent.sourceType, insertedSlot.captured[0].sourceType)

        // Count mirrored onto the parent raw event
        assertEquals(2, updatedSlot.captured.commitmentsExtractedCount)
    }

    // ── T6b: Empty drafts list → no inserts, count zeroed ────────────────────

    @Test
    fun `doWork skips insertAll when extractor returns empty drafts`() = runTest {
        coEvery { geminiNanoExtractor.extract(any(), any()) } returns
            BecalmResult.Success(emptyList())

        val updatedSlot = slot<RawIngestionEventEntity>()
        coEvery { rawIngestionEventDao.update(capture(updatedSlot)) } returns 1

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 0) { commitmentDao.insertAll(any()) }
        assertEquals(0, updatedSlot.captured.commitmentsExtractedCount)
    }

    // ── T7: AICORE_NOT_AVAILABLE → Result.success (no retry) ─────────────────

    @Test
    fun `doWork returns success on AICORE_NOT_AVAILABLE — device unsupported never retried`() = runTest {
        coEvery { geminiNanoExtractor.extract(any(), any()) } returns
            BecalmResult.Failure(
                BecalmError.ExtractorUnavailable(reason = "AICORE_NOT_AVAILABLE"),
            )

        val result = worker.doWork()

        assertEquals(
            "unsupported-device extractor failure must not schedule retries",
            Result.success(),
            result,
        )
        coVerify(exactly = 0) { commitmentDao.insertAll(any()) }
        coVerify(exactly = 0) { rawIngestionEventDao.update(any()) }
    }

    // ── T8: LLM_JSON_PARSE_FAILED → Result.retry ─────────────────────────────

    @Test
    fun `doWork returns retry on LLM_JSON_PARSE_FAILED`() = runTest {
        coEvery { geminiNanoExtractor.extract(any(), any()) } returns
            BecalmResult.Failure(
                BecalmError.ExtractorUnavailable(reason = "LLM_JSON_PARSE_FAILED"),
            )

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
        coVerify(exactly = 0) { commitmentDao.insertAll(any()) }
    }

    // ── T9: AICORE_ERROR → Result.retry ──────────────────────────────────────

    @Test
    fun `doWork returns retry on generic AICORE_ERROR`() = runTest {
        coEvery { geminiNanoExtractor.extract(any(), any()) } returns
            BecalmResult.Failure(
                BecalmError.ExtractorUnavailable(
                    reason = "AICORE_ERROR",
                    cause = RuntimeException("transient sdk failure"),
                ),
            )

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
    }

    // ── T10: Body is HTML-only → HTML-stripped snippet is passed to extractor ─

    @Test
    fun `doWork falls back to HTML-stripped body when bodyPlain is blank`() = runTest {
        val htmlOnlyBody = fakeEmailBody.copy(
            bodyPlain = null,
            bodyHtml = "<html><body><p>I will send the deck by Friday</p></body></html>",
        )
        coEvery { emailBodyDao.getByRawEventId(fakeRawEventId) } returns htmlOnlyBody

        val systemSlot = slot<String>()
        val userSlot = slot<String>()
        coEvery {
            geminiNanoExtractor.extract(capture(systemSlot), capture(userSlot))
        } returns BecalmResult.Success(emptyList())

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        // HTML-stripped content reaches the user context (via the commitment_text section).
        assertTrue(
            "HTML-stripped body must land in user context, was:\n${userSlot.captured}",
            userSlot.captured.contains("I will send the deck by Friday"),
        )
        // Metric is NOT incremented: HTML fallback produced usable text.
        coVerify(exactly = 0) { metricsStore.incrementSubjectOnlySkipped() }
    }
}
