package com.becalm.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.becalm.android.data.local.dao.CalendarEventDao
import com.becalm.android.data.local.dao.CommitmentDao
import com.becalm.android.data.local.dao.EmailBodyDao
import com.becalm.android.data.local.dao.PersonEnrichmentDao
import com.becalm.android.data.local.dao.RawIngestionEventDao
import com.becalm.android.data.local.dao.TranscriptDao
import com.becalm.android.data.local.entities.CalendarEvent
import com.becalm.android.data.local.entities.Commitment
import com.becalm.android.data.local.entities.EmailBody
import com.becalm.android.data.local.entities.PersonEnrichment
import com.becalm.android.data.local.entities.RawIngestionEvent
import com.becalm.android.data.local.entities.Transcript

// spec: data-model — Room database v1
// Tables:
//   - raw_ingestion_events (mirrors Supabase; ING-001..ING-015)
//   - commitments (mirrors Supabase; CMT-001..CMT-010)
//   - calendar_events (mirrors Supabase; ING-009..ING-010)
//   - persons_enrichment (Room-only; ENR-001..ENR-008 — PIPA)
//   - transcripts (Room-only; VOI-001 — never uploaded)
//   - email_bodies (Room-only; ING-006..ING-008 — never uploaded)

@Database(
    entities = [
        RawIngestionEvent::class,
        Commitment::class,
        CalendarEvent::class,
        PersonEnrichment::class,
        Transcript::class,
        EmailBody::class
    ],
    version = 1,
    exportSchema = true
)
abstract class BeCalmDatabase : RoomDatabase() {
    abstract fun rawIngestionEventDao(): RawIngestionEventDao
    abstract fun commitmentDao(): CommitmentDao
    abstract fun calendarEventDao(): CalendarEventDao
    abstract fun personEnrichmentDao(): PersonEnrichmentDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun emailBodyDao(): EmailBodyDao

    companion object {
        const val DATABASE_NAME = "becalm.db"
    }
}
