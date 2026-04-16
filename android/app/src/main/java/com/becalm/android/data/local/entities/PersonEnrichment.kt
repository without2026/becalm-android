package com.becalm.android.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// spec: data-model — persons_enrichment Room-ONLY entity
// PIPA invariant: contact data is on-device only. NEVER send to Railway or Supabase.
// Populated by EnrichmentWorker reading ContactsContract.Data (spec: ENR-001..ENR-008).

@Entity(tableName = "persons_enrichment")
data class PersonEnrichment(
    // spec: data-model — JOIN key matching person_ref in raw_ingestion_events / commitments
    @PrimaryKey
    @ColumnInfo(name = "person_ref")
    val personRef: String,

    // ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME
    @ColumnInfo(name = "display_name")
    val displayName: String? = null,

    // ContactsContract.CommonDataKinds.Nickname.NAME
    @ColumnInfo(name = "nickname")
    val nickname: String? = null,

    // ContactsContract.CommonDataKinds.Organization.COMPANY
    @ColumnInfo(name = "company")
    val company: String? = null,

    // ContactsContract.CommonDataKinds.Organization.TITLE
    @ColumnInfo(name = "title")
    val title: String? = null,

    // Raw contact ID used for change detection in EnrichmentWorker
    @ColumnInfo(name = "source_contact_id")
    val sourceContactId: String? = null,

    // epoch millis — updated on every EnrichmentWorker run
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long = System.currentTimeMillis()
)
