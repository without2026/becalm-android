package com.becalm.android.unit.data.repository

import android.content.Context
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.MeetingSpeakerPreviewDao
import com.becalm.android.data.local.db.entity.MeetingSpeakerPreviewEntity
import com.becalm.android.data.local.db.entity.MeetingSpeakerPreviewStatus
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.SourceExtractionApi
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.MeetingImportRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceImportRepository
import com.becalm.android.worker.WorkScheduler
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceImportRepositoryRetryTest {

    private val context: Context = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val rawIngestionRepository: RawIngestionRepository = mockk(relaxed = true)
    private val meetingImportRepository: MeetingImportRepository = mockk(relaxed = true)
    private val meetingSpeakerPreviewDao: MeetingSpeakerPreviewDao = mockk(relaxed = true)
    private val sourceExtractionApi: SourceExtractionApi = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)

    @Test
    fun `retry failed meeting import resets raw row and reenqueues extraction once`() = runTest {
        everyCurrentUser()
        val event = rawEvent(sourceType = SourceType.MEETING)
        coEvery { rawIngestionRepository.findFailedEvidenceImportsForRetry(USER_ID) } returns
            BecalmResult.Success(listOf(event))
        coEvery {
            rawIngestionRepository.resetFailedEvidenceImportForRetry(event.id, USER_ID, any())
        } returns BecalmResult.Success(Unit)
        coEvery { meetingSpeakerPreviewDao.findByRawEventId(event.id) } returns meetingPreview(event)
        coEvery {
            meetingSpeakerPreviewDao.markStatus(
                rawEventId = event.id,
                status = MeetingSpeakerPreviewStatus.EXTRACT_PENDING,
                lastError = null,
                updatedAt = any(),
            )
        } returns 1

        val result = repository().retryFailedEvidenceImports()

        assertEquals(BecalmResult.Success(1), result)
        coVerify(exactly = 1) {
            rawIngestionRepository.resetFailedEvidenceImportForRetry(event.id, USER_ID, any())
        }
        coVerify(exactly = 1) {
            meetingSpeakerPreviewDao.markStatus(
                rawEventId = event.id,
                status = MeetingSpeakerPreviewStatus.EXTRACT_PENDING,
                lastError = null,
                updatedAt = any(),
            )
        }
        verify(exactly = 1) {
            workScheduler.enqueueVoiceUpload(
                rawEventId = event.id,
                audioUri = "file://meeting.mp3",
                selfSpeakerId = "SPEAKER_01",
                speakerPreviewId = "preview-1",
                speakerMappingsJson = match { it.contains("\"relation_to_user\":\"self\"") },
            )
        }
    }

    @Test
    fun `retry failed message screenshot resets raw row and reenqueues screenshot upload`() = runTest {
        everyCurrentUser()
        val event = rawEvent(sourceType = SourceType.MESSAGE_SCREENSHOT)
        coEvery { rawIngestionRepository.findFailedEvidenceImportsForRetry(USER_ID) } returns
            BecalmResult.Success(listOf(event))
        coEvery {
            rawIngestionRepository.resetFailedEvidenceImportForRetry(event.id, USER_ID, any())
        } returns BecalmResult.Success(Unit)

        val result = repository().retryFailedEvidenceImports()

        assertEquals(BecalmResult.Success(1), result)
        coVerify(exactly = 1) {
            rawIngestionRepository.resetFailedEvidenceImportForRetry(event.id, USER_ID, any())
        }
        verify(exactly = 1) {
            workScheduler.enqueueMessageScreenshotUpload(event.id)
        }
    }

    private fun everyCurrentUser() {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf(USER_ID)
    }

    private fun repository(): SourceImportRepository =
        SourceImportRepository(
            context = context,
            userPrefsStore = userPrefsStore,
            rawIngestionRepository = rawIngestionRepository,
            meetingImportRepository = meetingImportRepository,
            meetingSpeakerPreviewDao = meetingSpeakerPreviewDao,
            sourceExtractionApi = sourceExtractionApi,
            workScheduler = workScheduler,
            moshi = Moshi.Builder().build(),
            ioDispatcher = Dispatchers.Unconfined,
        )

    private fun rawEvent(sourceType: String): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = "raw-1",
            userId = USER_ID,
            clientEventId = "client-1",
            sourceType = sourceType,
            sourceRef = "file://meeting.mp3",
            eventTitle = "meeting",
            timestamp = NOW,
            syncStatus = "failed",
            lastError = "non_retryable_http_422",
        )

    private fun meetingPreview(event: RawIngestionEventEntity): MeetingSpeakerPreviewEntity =
        MeetingSpeakerPreviewEntity(
            id = "meeting-preview-1",
            userId = USER_ID,
            rawEventId = event.id,
            sourceRef = event.sourceRef.orEmpty(),
            speakerPreviewId = "preview-1",
            speakersJson = """[{"speaker_id":"SPEAKER_01","sample_texts":["제가 하겠습니다"],"total_seconds":5.0}]""",
            transcriptSegmentsJson = "[]",
            billableSeconds = 5,
            status = MeetingSpeakerPreviewStatus.FAILED,
            selectedSelfSpeakerId = "SPEAKER_01",
            lastError = "raw_event_failed",
            expiresAt = null,
            createdAt = NOW,
            updatedAt = NOW,
        )

    private companion object {
        const val USER_ID: String = "user-1"
        val NOW: Instant = Instant.fromEpochMilliseconds(1_779_824_635_523)
    }
}
