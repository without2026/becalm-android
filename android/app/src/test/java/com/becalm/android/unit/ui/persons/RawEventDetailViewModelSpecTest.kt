package com.becalm.android.unit.ui.persons

import androidx.lifecycle.SavedStateHandle
import com.becalm.android.R
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.EmailBodyRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceArtifactRepository
import com.becalm.android.data.repository.SourceOriginalResolver
import com.becalm.android.ui.persons.ARG_EVENT_ID
import com.becalm.android.ui.persons.RawEventDetailProjectionPort
import com.becalm.android.ui.persons.RawEventDetailViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RawEventDetailViewModelSpecTest {

    private val testDispatcher = StandardTestDispatcher()
    private val rawIngestionRepository: RawIngestionRepository = mockk()
    private val emailBodyRepository: EmailBodyRepository = mockk()
    private val sourceArtifactRepository: SourceArtifactRepository = mockk()
    private val sourceOriginalResolver = SourceOriginalResolver(
        emailBodyRepository = emailBodyRepository,
        sourceArtifactRepository = sourceArtifactRepository,
    )
    private val projectionPort: RawEventDetailProjectionPort = mockk()
    private val userPrefsStore: UserPrefsStore = mockk()
    private val logger: Logger = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        coEvery { projectionPort.loadCommitmentQuotes(any(), any()) } returns emptyList()
        coEvery { projectionPort.loadCalendarAttendeesRaw(any(), any()) } returns null
        coEvery { sourceArtifactRepository.findMarkdownOriginal(any(), any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `missing event id yields immediate error state`() = runTest {
        val viewModel = buildViewModel(eventId = "")

        assertEquals(R.string.raw_event_detail_error_missing_id, viewModel.uiState.value.error?.resId)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `absent signed-in user is treated as not found without querying repository`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf(null)

        val viewModel = buildViewModel(eventId = "evt-1")
        advanceUntilIdle()

        assertEquals(R.string.raw_event_detail_not_found, viewModel.uiState.value.error?.resId)
        coVerify(exactly = 0) { rawIngestionRepository.findById(any(), any()) }
    }

    @Test
    // spec: EMAIL-004
    fun `SRC-004 email detail joins full body and attachment metadata from Room without projection lookups`() = runTest {
        coEvery { rawIngestionRepository.findById("evt-1", "user-1") } returns
            rawEvent(
                id = "evt-1",
                sourceType = SourceType.GMAIL,
                eventTitle = "Subject line",
                snippet = "x".repeat(240),
                commitmentsExtractedCount = 3,
                timestamp = Instant.fromEpochMilliseconds(5_000),
            )
        coEvery { emailBodyRepository.getByRawEventId("evt-1") } returns
            emailBody(
                rawEventId = "evt-1",
                attachmentsMeta = """
                [
                  {"filename":"a.pdf","mime":"application/pdf","size_bytes":10},
                  {"filename":"b.png","mime":"image/png","size_bytes":20}
                ]
                """.trimIndent(),
            )

        val viewModel = buildViewModel(eventId = "evt-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals("evt-1", state.eventId)
        assertEquals(SourceType.GMAIL, state.sourceType)
        assertEquals("Subject line", state.eventTitle)
        assertEquals(Instant.fromEpochMilliseconds(5_000), state.timestamp)
        assertEquals(200, state.snippet!!.length)
        assertEquals(2, state.attachmentCount)
        assertEquals(3, state.commitmentsExtractedCount)
        assertEquals("plain body", state.emailBody!!.bodyPlain)
        assertEquals(emptyList<String>(), state.commitmentQuotes)
        assertNull(state.attendeesRaw)
    }

    @Test
    fun `SRC-004 voice detail exposes duration and commitment quotes`() = runTest {
        val quotes = listOf("이번 주 금요일까지 공유드릴게요", "회의록은 제가 내일 보낼게요")
        coEvery { rawIngestionRepository.findById("evt-2", "user-1") } returns
            rawEvent(
                id = "evt-2",
                sourceType = SourceType.VOICE,
                eventTitle = "Team meeting",
                snippet = "voice snippet",
                durationSeconds = 1_800,
                commitmentsExtractedCount = 2,
                timestamp = Instant.fromEpochMilliseconds(2_000),
            )
        coEvery { projectionPort.loadCommitmentQuotes("user-1", any()) } returns quotes

        val viewModel = buildViewModel(eventId = "evt-2")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(SourceType.VOICE, state.sourceType)
        assertEquals("Team meeting", state.eventTitle)
        assertEquals(Instant.fromEpochMilliseconds(2_000), state.timestamp)
        assertEquals(1_800, state.durationSeconds)
        assertEquals(2, state.commitmentsExtractedCount)
        assertEquals(quotes, state.commitmentQuotes)
        assertTrue(state.commitmentQuotes.all { it.length <= 100 })
        assertNull(state.emailBody)
        assertNull(state.attendeesRaw)
        assertEquals(0, state.attachmentCount)
        coVerify(exactly = 1) { projectionPort.loadCommitmentQuotes("user-1", any()) }
        coVerify(exactly = 0) { emailBodyRepository.getByRawEventId(any()) }
    }

    @Test
    fun `SRC-004 call recording shares the voice quote and duration contract`() = runTest {
        val quotes = listOf("다음 주까지 확인해서 전화드릴게요")
        coEvery { rawIngestionRepository.findById("evt-2b", "user-1") } returns
            rawEvent(
                id = "evt-2b",
                sourceType = SourceType.CALL_RECORDING,
                eventTitle = "Call with client",
                snippet = "call snippet",
                durationSeconds = 420,
                commitmentsExtractedCount = 1,
                timestamp = Instant.fromEpochMilliseconds(7_000),
            )
        coEvery { projectionPort.loadCommitmentQuotes("user-1", any()) } returns quotes

        val viewModel = buildViewModel(eventId = "evt-2b")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(SourceType.CALL_RECORDING, state.sourceType)
        assertEquals("Call with client", state.eventTitle)
        assertEquals(420, state.durationSeconds)
        assertEquals(1, state.commitmentsExtractedCount)
        assertEquals(quotes, state.commitmentQuotes)
        assertNull(state.location)
        assertNull(state.attendeesRaw)
        assertNull(state.emailBody)
        coVerify(exactly = 1) { projectionPort.loadCommitmentQuotes("user-1", any()) }
        coVerify(exactly = 0) { emailBodyRepository.getByRawEventId(any()) }
    }

    @Test
    fun `SRC-004 calendar detail exposes location and attendees via projection seam`() = runTest {
        coEvery { rawIngestionRepository.findById("evt-3", "user-1") } returns
            rawEvent(
                id = "evt-3",
                sourceType = SourceType.GOOGLE_CALENDAR,
                eventTitle = "Customer sync",
                snippet = null,
                location = "Seoul HQ",
                commitmentsExtractedCount = 0,
                timestamp = Instant.fromEpochMilliseconds(9_000),
            )
        coEvery { projectionPort.loadCalendarAttendeesRaw("user-1", any()) } returns "kim@corp.com, lee@corp.com"

        val viewModel = buildViewModel(eventId = "evt-3")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(SourceType.GOOGLE_CALENDAR, state.sourceType)
        assertEquals("Customer sync", state.eventTitle)
        assertEquals(Instant.fromEpochMilliseconds(9_000), state.timestamp)
        assertEquals("Seoul HQ", state.location)
        assertEquals("kim@corp.com, lee@corp.com", state.attendeesRaw)
        assertNull(state.snippet)
        assertNull(state.emailBody)
        assertEquals(emptyList<String>(), state.commitmentQuotes)
        assertEquals(0, state.attachmentCount)
        assertEquals(0, state.commitmentsExtractedCount)
        coVerify(exactly = 1) { projectionPort.loadCalendarAttendeesRaw("user-1", any()) }
        coVerify(exactly = 0) { emailBodyRepository.getByRawEventId(any()) }
    }

    private fun buildViewModel(eventId: String): RawEventDetailViewModel = RawEventDetailViewModel(
        rawIngestionRepository = rawIngestionRepository,
        sourceOriginalResolver = sourceOriginalResolver,
        projectionPort = projectionPort,
        userPrefsStore = userPrefsStore,
        savedStateHandle = SavedStateHandle(mapOf(ARG_EVENT_ID to eventId)),
        logger = logger,
        ioDispatcher = testDispatcher,
    )

    private fun rawEvent(
        id: String,
        sourceType: String,
        eventTitle: String = "Title",
        snippet: String?,
        durationSeconds: Int? = null,
        location: String? = null,
        commitmentsExtractedCount: Int = 3,
        timestamp: Instant = Instant.fromEpochMilliseconds(1_000),
    ): RawIngestionEventEntity = RawIngestionEventEntity(
        id = id,
        userId = "user-1",
        clientEventId = "client-$id",
        sourceType = sourceType,
        eventTitle = eventTitle,
        eventSnippet = snippet,
        durationSeconds = durationSeconds,
        location = location,
        commitmentsExtractedCount = commitmentsExtractedCount,
        timestamp = timestamp,
    )

    private fun emailBody(
        rawEventId: String,
        attachmentsMeta: String?,
    ): EmailBodyEntity = EmailBodyEntity(
        id = "body-$rawEventId",
        rawEventId = rawEventId,
        providerMessageId = "provider-$rawEventId",
        folder = "INBOX",
        subject = "Subject",
        fromAddress = "alice@example.com",
        toAddresses = """[{"email":"bob@example.com"}]""",
        bodyPlain = "plain body",
        bodyHtml = "<p>plain body</p>",
        attachmentsMeta = attachmentsMeta,
        rawHeaders = null,
        parseFailed = false,
        groupEmail = false,
        receivedAt = Instant.fromEpochMilliseconds(1_000),
    )
}
