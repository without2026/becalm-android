package com.becalm.android.ui.persons

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.EmailBodyRepository
import com.becalm.android.data.repository.RawIngestionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Email-branch unit tests for [RawEventDetailViewModel] — covers the EmailBody
 * JOIN, attachment JSON parsing, and commitments-extracted count propagation
 * per the S5-D plan acceptance criteria.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RawEventDetailViewModelEmailTest {

    private val testDispatcher = StandardTestDispatcher()

    private val rawIngestionRepository: RawIngestionRepository = mockk()
    private val emailBodyRepository: EmailBodyRepository = mockk()
    private val userPrefsStore: UserPrefsStore = mockk()
    private val logger: Logger = mockk(relaxed = true)

    private val eventId = "raw-primary-id-gmail-42"
    private val userId = "user-1"
    private val now = Clock.System.now()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { userPrefsStore.observeCurrentUserId() } returns flowOf(userId)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): RawEventDetailViewModel {
        val handle = SavedStateHandle(mapOf(ARG_EVENT_ID to eventId))
        return RawEventDetailViewModel(
            rawIngestionRepository = rawIngestionRepository,
            emailBodyRepository = emailBodyRepository,
            userPrefsStore = userPrefsStore,
            savedStateHandle = handle,
            logger = logger,
        )
    }

    private fun makeEntity(
        sourceType: String = SourceType.GMAIL,
        commitmentsExtractedCount: Int = 0,
    ): RawIngestionEventEntity = RawIngestionEventEntity(
        id = eventId,
        userId = userId,
        clientEventId = "client-event-gmail-42",
        sourceType = sourceType,
        eventTitle = "Project update",
        timestamp = now,
        commitmentsExtractedCount = commitmentsExtractedCount,
    )

    private fun makeEmailBody(
        bodyPlain: String? = "Hello, please review by Friday.",
        bodyHtml: String? = null,
        attachmentsMeta: String? = null,
    ): EmailBodyEntity = EmailBodyEntity(
        id = "body-id-1",
        rawEventId = eventId,
        providerMessageId = "gmail-msg-abc",
        folder = "INBOX",
        bodyPlain = bodyPlain,
        bodyHtml = bodyHtml,
        attachmentsMeta = attachmentsMeta,
        receivedAt = now,
    )

    // ─── Test: email source triggers EmailBody JOIN ──────────────────────────

    @Test
    fun loadEvent_emailSourceType_joinsEmailBody() = runTest(testDispatcher) {
        val entity = makeEntity(sourceType = SourceType.GMAIL)
        val body = makeEmailBody()
        coEvery { rawIngestionRepository.findById(id = eventId, userId = userId) } returns entity
        coEvery { emailBodyRepository.getByRawEventId(eventId) } returns body

        val vm = buildViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            assertEquals(SourceType.GMAIL, state.sourceType)
            assertNotNull(state.emailBody)
            assertEquals(body.bodyPlain, state.emailBody?.bodyPlain)
            assertEquals(body.bodyHtml, state.emailBody?.bodyHtml)

            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { emailBodyRepository.getByRawEventId(eventId) }
    }

    // ─── Test: non-email source skips the JOIN entirely ──────────────────────

    @Test
    fun loadEvent_voiceSourceType_doesNotCallEmailBodyRepository() = runTest(testDispatcher) {
        val entity = makeEntity(sourceType = SourceType.VOICE)
        coEvery { rawIngestionRepository.findById(id = eventId, userId = userId) } returns entity

        val vm = buildViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            assertEquals(SourceType.VOICE, state.sourceType)
            assertNull(state.emailBody)
            assertEquals(0, state.attachmentCount)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { emailBodyRepository.getByRawEventId(any()) }
    }

    // ─── Test: attachments_meta JSON parses into a count ─────────────────────

    @Test
    fun loadEvent_attachmentsMetaParsed_countMatches() = runTest(testDispatcher) {
        val entity = makeEntity()
        val body = makeEmailBody(
            attachmentsMeta = """
                [
                  {"filename":"report.pdf","mime":"application/pdf","size_bytes":12345},
                  {"filename":"data.xlsx","mime":"application/vnd.ms-excel","size_bytes":6789}
                ]
            """.trimIndent(),
        )
        coEvery { rawIngestionRepository.findById(id = eventId, userId = userId) } returns entity
        coEvery { emailBodyRepository.getByRawEventId(eventId) } returns body

        val vm = buildViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(2, state.attachmentCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Test: malformed attachments_meta → graceful degrade ─────────────────

    @Test
    fun loadEvent_attachmentsMetaInvalidJson_countZero() = runTest(testDispatcher) {
        val entity = makeEntity()
        val body = makeEmailBody(attachmentsMeta = "{not a valid json array")
        coEvery { rawIngestionRepository.findById(id = eventId, userId = userId) } returns entity
        coEvery { emailBodyRepository.getByRawEventId(eventId) } returns body

        val vm = buildViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(0, state.attachmentCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Test: commitments_extracted_count propagated ────────────────────────

    @Test
    fun loadEvent_commitmentsExtractedCountPropagated() = runTest(testDispatcher) {
        val entity = makeEntity(commitmentsExtractedCount = 3)
        coEvery { rawIngestionRepository.findById(id = eventId, userId = userId) } returns entity
        coEvery { emailBodyRepository.getByRawEventId(eventId) } returns makeEmailBody()

        val vm = buildViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(3, state.commitmentsExtractedCount)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
