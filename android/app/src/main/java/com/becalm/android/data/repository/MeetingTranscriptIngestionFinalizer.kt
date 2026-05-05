package com.becalm.android.data.repository

import com.becalm.android.worker.WorkScheduler
import kotlinx.datetime.Instant

public class MeetingTranscriptIngestionFinalizer(
    private val sourceArtifactRepository: SourceArtifactRepository,
    private val workScheduler: WorkScheduler,
) {
    public suspend fun archiveAndEnqueue(
        userId: String,
        rawEventId: String,
        sourceRef: String,
        occurredAt: Instant,
        title: String?,
        text: String?,
        syncStatus: String,
    ) {
        if (!text.isNullOrBlank()) {
            sourceArtifactRepository.archiveMeetingTranscript(
                MeetingTranscriptArchiveInput(
                    userId = userId,
                    rawEventId = rawEventId,
                    sourceRef = sourceRef,
                    occurredAt = occurredAt,
                    title = title,
                    text = text,
                ),
            )
        }
        if (syncStatus == STATUS_PENDING) workScheduler.enqueueMeetingTranscriptUpload(rawEventId)
    }

    private companion object {
        private const val STATUS_PENDING: String = "pending"
    }
}
