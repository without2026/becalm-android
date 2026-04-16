package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

/**
 * Room entity for the `persons_enrichment` table.
 *
 * **PIPA INVARIANT — ON-DEVICE ONLY.**
 * This table stores contact data read from [android.provider.ContactsContract] and is
 * NEVER uploaded to Railway or Supabase. Transmitting rows from this entity to any
 * remote endpoint is strictly prohibited under the Korean Personal Information
 * Protection Act (PIPA / 개인정보 보호법). Do NOT add sync_status, retry_count,
 * upload, or any network-facing field to this entity.
 *
 * **Purpose.**
 * [PersonEnrichmentEntity] enriches the virtual "person" groupings formed by the
 * shared `person_ref` key across [RawIngestionEventEntity] and [CommitmentEntity].
 * Because those tables only carry a canonicalized string key (phone E.164, lowercase
 * email, or normalized display name), this table supplies the human-readable metadata
 * (display name, company, title) needed to render PersonsScreen rows and
 * PersonDetailScreen headers — entirely from on-device contact data.
 *
 * **Lifecycle.**
 * Rows are inserted or replaced by `EnrichmentWorker` after CONTACTS permission is
 * granted. The entire table is wiped on user logout via
 * [PersonEnrichmentDao.deleteAll].
 *
 * **Type converters.**
 * SP-13 (BeCalmDatabase) supplies the TypeConverter that maps [Instant] ↔ [Long]
 * (epoch milliseconds). Do not add inline converters here.
 *
 * Indices defined per `data-model.yml § persons_enrichment`:
 * - `person_ref` is the primary key; Room automatically creates a unique index on it.
 *   An explicit named index is also declared so coverage queries can reference it by name.
 */
@Entity(
    tableName = "persons_enrichment",
    indices = [
        Index(
            name = "idx_persons_enrichment_person_ref",
            value = ["person_ref"],
            unique = true,
        ),
    ],
)
public data class PersonEnrichmentEntity(

    /**
     * Canonicalized counterparty identifier; primary key for this table.
     *
     * Matches `person_ref` in `raw_ingestion_events` and `commitments` and is used
     * exclusively as a local JOIN key. The value is never transmitted to Railway or
     * Supabase.
     *
     * Precedence used when deriving the key: E.164 phone > lowercase email >
     * normalized display name.
     */
    @PrimaryKey
    @ColumnInfo(name = "person_ref")
    val personRef: String,

    /**
     * Human-readable full name sourced from
     * [android.provider.ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME].
     * Null when the ContactsContract row does not carry a structured name.
     */
    @ColumnInfo(name = "display_name")
    val displayName: String? = null,

    /**
     * Informal name sourced from
     * [android.provider.ContactsContract.CommonDataKinds.Nickname.NAME].
     * Null when no nickname is stored for this contact.
     */
    @ColumnInfo(name = "nickname")
    val nickname: String? = null,

    /**
     * Employer / organisation name sourced from
     * [android.provider.ContactsContract.CommonDataKinds.Organization.COMPANY].
     * Null when no organisation record exists for this contact.
     */
    @ColumnInfo(name = "company")
    val company: String? = null,

    /**
     * Job title sourced from
     * [android.provider.ContactsContract.CommonDataKinds.Organization.TITLE].
     * Null when no title is stored for this contact.
     */
    @ColumnInfo(name = "title")
    val title: String? = null,

    /**
     * Raw contact ID from [android.provider.ContactsContract] used by
     * `EnrichmentWorker` to detect contact changes between runs.
     * Null until the first enrichment run that resolves this [personRef] to a
     * ContactsContract entry.
     */
    @ColumnInfo(name = "source_contact_id")
    val sourceContactId: String? = null,

    /**
     * Timestamp of the most recent `EnrichmentWorker` run that wrote this row.
     * Updated on every upsert so that [PersonEnrichmentDao.findStale] can identify
     * rows that have not been refreshed within a given window and re-enrich them
     * incrementally.
     *
     * Stored as epoch milliseconds via the BeCalmDatabase TypeConverter.
     */
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Instant,
)
