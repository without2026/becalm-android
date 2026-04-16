package com.becalm.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.entities.Transcript

// spec: VOI-001 — STT transcripts, local only

@Dao
interface TranscriptDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(transcript: Transcript): Long

    @Query("SELECT * FROM transcripts WHERE raw_ingestion_id = :rawIngestionId")
    suspend fun getByRawIngestionId(rawIngestionId: String): Transcript?

    @Query("SELECT COUNT(*) FROM transcripts")
    suspend fun count(): Int
}
