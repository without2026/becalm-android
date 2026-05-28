package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.db.entity.MeetingSpeakerPreviewEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

public data class MeetingSpeakerPreviewWithSourceEntity(
    @Embedded val preview: MeetingSpeakerPreviewEntity,
    @ColumnInfo(name = "source_type") val sourceType: String,
)

@Dao
public interface MeetingSpeakerPreviewDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entity: MeetingSpeakerPreviewEntity)

    @Query(
        """
        SELECT * FROM meeting_speaker_previews
        WHERE raw_event_id = :rawEventId
        LIMIT 1
        """,
    )
    public suspend fun findByRawEventId(rawEventId: String): MeetingSpeakerPreviewEntity?

    @Query(
        """
        SELECT meeting_speaker_previews.*, raw_ingestion_events.source_type AS source_type
        FROM meeting_speaker_previews
        INNER JOIN raw_ingestion_events
          ON raw_ingestion_events.id = meeting_speaker_previews.raw_event_id
         AND raw_ingestion_events.user_id = meeting_speaker_previews.user_id
        WHERE meeting_speaker_previews.user_id = :userId
          AND meeting_speaker_previews.status = 'meeting_review_required'
          AND meeting_speaker_previews.speaker_preview_id IS NOT NULL
          AND meeting_speaker_previews.speaker_preview_id != ''
          AND LENGTH(TRIM(meeting_speaker_previews.speakers_json)) > 2
        ORDER BY meeting_speaker_previews.updated_at DESC
        LIMIT 1
        """,
    )
    public fun observeLatestReviewRequired(userId: String): Flow<MeetingSpeakerPreviewWithSourceEntity?>

    @Query(
        """
        SELECT COUNT(*) FROM meeting_speaker_previews
        WHERE user_id = :userId
          AND status = 'meeting_review_required'
          AND speaker_preview_id IS NOT NULL
          AND speaker_preview_id != ''
          AND LENGTH(TRIM(speakers_json)) > 2
        """,
    )
    public fun observeReviewRequiredCount(userId: String): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM meeting_speaker_previews
        WHERE user_id = :userId
          AND status IN (
            'meeting_preview_pending',
            'meeting_extract_pending',
            'meeting_extract_running'
          )
        """,
    )
    public fun observeProcessingCount(userId: String): Flow<Int>

    @Query(
        """
        SELECT MIN(updated_at) FROM meeting_speaker_previews
        WHERE user_id = :userId
          AND status IN (
            'meeting_preview_pending',
            'meeting_extract_pending',
            'meeting_extract_running'
          )
        """,
    )
    public fun observeOldestProcessingAt(userId: String): Flow<Instant?>

    @Query(
        """
        SELECT COUNT(*) FROM meeting_speaker_previews
        INNER JOIN raw_ingestion_events
          ON raw_ingestion_events.id = meeting_speaker_previews.raw_event_id
         AND raw_ingestion_events.user_id = meeting_speaker_previews.user_id
        WHERE meeting_speaker_previews.user_id = :userId
          AND raw_ingestion_events.source_type = :sourceType
          AND meeting_speaker_previews.status IN (
            'meeting_preview_pending',
            'meeting_extract_pending',
            'meeting_extract_running'
          )
        """,
    )
    public suspend fun countProcessingForSource(
        userId: String,
        sourceType: String,
    ): Int

    @Query(
        """
        UPDATE meeting_speaker_previews
        SET status = 'meeting_extract_done',
            last_error = NULL,
            updated_at = :updatedAt
        WHERE user_id = :userId
          AND status IN (
            'meeting_preview_pending',
            'meeting_extract_pending',
            'meeting_extract_running'
          )
          AND EXISTS (
            SELECT 1 FROM raw_ingestion_events
            WHERE raw_ingestion_events.id = meeting_speaker_previews.raw_event_id
              AND raw_ingestion_events.user_id = meeting_speaker_previews.user_id
              AND raw_ingestion_events.sync_status = 'synced'
          )
        """,
    )
    public suspend fun markProcessingDoneForSyncedRawEvents(
        userId: String,
        updatedAt: Instant,
    ): Int

    @Query(
        """
        UPDATE meeting_speaker_previews
        SET status = 'meeting_extract_failed',
            last_error = COALESCE(last_error, :lastError),
            updated_at = :updatedAt
        WHERE user_id = :userId
          AND status IN (
            'meeting_preview_pending',
            'meeting_extract_pending',
            'meeting_extract_running'
          )
          AND EXISTS (
            SELECT 1 FROM raw_ingestion_events
            WHERE raw_ingestion_events.id = meeting_speaker_previews.raw_event_id
              AND raw_ingestion_events.user_id = meeting_speaker_previews.user_id
              AND raw_ingestion_events.sync_status = 'failed'
          )
        """,
    )
    public suspend fun markProcessingFailedForFailedRawEvents(
        userId: String,
        lastError: String,
        updatedAt: Instant,
    ): Int

    @Query(
        """
        UPDATE meeting_speaker_previews
        SET status = :status,
            speaker_preview_id = :speakerPreviewId,
            speakers_json = :speakersJson,
            transcript_segments_json = :transcriptSegmentsJson,
            billable_seconds = :billableSeconds,
            expires_at = :expiresAt,
            last_error = NULL,
            updated_at = :updatedAt
        WHERE raw_event_id = :rawEventId
        """,
    )
    public suspend fun markPreviewReady(
        rawEventId: String,
        status: String,
        speakerPreviewId: String,
        speakersJson: String,
        transcriptSegmentsJson: String?,
        billableSeconds: Int,
        expiresAt: Instant?,
        updatedAt: Instant,
    ): Int

    @Query(
        """
        UPDATE meeting_speaker_previews
        SET status = :status,
            selected_self_speaker_id = :selectedSelfSpeakerId,
            last_error = NULL,
            updated_at = :updatedAt
        WHERE raw_event_id = :rawEventId
        """,
    )
    public suspend fun markSelected(
        rawEventId: String,
        selectedSelfSpeakerId: String,
        status: String,
        updatedAt: Instant,
    ): Int

    @Query(
        """
        UPDATE meeting_speaker_previews
        SET status = :status,
            last_error = :lastError,
            updated_at = :updatedAt
        WHERE raw_event_id = :rawEventId
        """,
    )
    public suspend fun markStatus(
        rawEventId: String,
        status: String,
        lastError: String?,
        updatedAt: Instant,
    ): Int
}
