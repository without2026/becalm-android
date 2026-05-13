package com.becalm.android.unit.ui.evidence

import android.net.Uri
import com.becalm.android.R
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.remote.dto.MeetingSpeakerPreviewDto
import com.becalm.android.data.repository.MeetingImportResult
import com.becalm.android.data.repository.MeetingSpeakerPreviewResult
import com.becalm.android.data.repository.MeetingSpeakerReviewContext
import com.becalm.android.data.repository.SourceImportRepository
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.evidence.EvidenceImportPersistentStatus
import com.becalm.android.ui.evidence.EvidenceImportStatusProjectionPort
import com.becalm.android.ui.evidence.EvidenceImportViewModel
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EvidenceImportViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val sourceImportRepository: SourceImportRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `persistent projection status is restored by a new ViewModel instance`() = runTest {
        val recoveredStatus = EvidenceImportPersistentStatus.PROCESSING
        val first = EvidenceImportViewModel(sourceImportRepository, FakeStatusProjectionPort(recoveredStatus))
        advanceUntilIdle()
        assertEquals(
            UiMessage.resource(R.string.evidence_import_status_processing),
            first.awaitStatusMessage(),
        )

        val restored = EvidenceImportViewModel(sourceImportRepository, FakeStatusProjectionPort(recoveredStatus))
        advanceUntilIdle()
        assertEquals(
            UiMessage.resource(R.string.evidence_import_status_processing),
            restored.awaitStatusMessage(),
        )
    }

    @Test
    fun `meeting speaker review confirmation sends escaped mapping json`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        val capturedContext = slot<MeetingSpeakerReviewContext>()
        coEvery { sourceImportRepository.previewMeetingAudioSpeakers(uri) } returns BecalmResult.Success(
            MeetingSpeakerPreviewResult(
                rawEventId = "raw-preview",
                speakerPreviewId = "preview-1",
                speakers = listOf(
                    MeetingSpeakerPreviewDto(speakerId = "SPEAKER_01"),
                    MeetingSpeakerPreviewDto(speakerId = """SPEAKER_"02\민홍"""),
                ),
                billableSeconds = 60,
            ),
        )
        coEvery {
            sourceImportRepository.importMeetingAudio(uri, capture(capturedContext))
        } returns BecalmResult.Success(MeetingImportResult("raw-meeting", "content://saved/audio"))

        val viewModel = EvidenceImportViewModel(
            sourceImportRepository,
            FakeStatusProjectionPort(EvidenceImportPersistentStatus.NONE),
        )

        viewModel.onMeetingAudioSelected(uri)
        advanceUntilIdle()
        viewModel.onMeetingSelfSpeakerSelected("""SPEAKER_"02\민홍""")
        viewModel.onMeetingSpeakerReviewConfirmed()
        advanceUntilIdle()

        coVerify(exactly = 1) { sourceImportRepository.importMeetingAudio(uri, any()) }
        val rows = parseRows(capturedContext.captured.speakerMappingsJson)
        assertEquals("""SPEAKER_"02\민홍""", rows[1]["speaker_id"])
        assertEquals("self", rows[1]["relation_to_user"])
        assertEquals(true, rows[1]["confirmed_by_user"])
        assertEquals(false, rows[0]["confirmed_by_user"])
    }

    private suspend fun EvidenceImportViewModel.awaitStatusMessage(): UiMessage =
        requireNotNull(withTimeout(5_000) { state.first { it.statusMessage != null }.statusMessage })

    private fun parseRows(json: String): List<Map<String, Any?>> {
        val listType = Types.newParameterizedType(
            List::class.java,
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
        )
        return requireNotNull(Moshi.Builder().build().adapter<List<Map<String, Any?>>>(listType).fromJson(json))
    }

    private class FakeStatusProjectionPort(
        status: EvidenceImportPersistentStatus,
    ) : EvidenceImportStatusProjectionPort {
        private val statusFlow = MutableStateFlow(status)
        override fun observeStatus(): Flow<EvidenceImportPersistentStatus> = statusFlow
    }
}
