package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

/**
 * Room entity storing the verbatim email body text for rows in [RawIngestionEventEntity].
 *
 * ## Room-only — `room_only: true`
 * This table is the canonical on-device store for email content. Mirrors
 * `.spec/contracts/data-model.yml:327-390 § email_body` **minus** the Supabase/Railway
 * mirror. Per EMAIL-006 (`.spec/email-pipeline.spec.yml:58-64`) the columns [bodyPlain],
 * [bodyHtml], [attachmentsMeta], and [rawHeaders] MUST NEVER leave the device. Any
 * upload path that serializes this entity is a production-blocking privacy bug.
 *
 * ## 30-day retention sweep
 * The future `RetentionSweepWorker` (`feat/worker/retention`) is the authoritative
 * driver — see [com.becalm.android.data.local.db.dao.EmailBodyDao.deleteOlderThanForSynced]
 * and `.spec/data-ingestion.spec.yml:160`. The [ForeignKey.CASCADE] below is a secondary
 * safety net for sign-out purge / ad-hoc deletes: when a parent `raw_ingestion_events`
 * row disappears, its body is co-deleted atomically, satisfying EMAIL-006's
 * "EmailBody와 raw_ingestion_events를 함께 DELETE" contract.
 *
 * ## Indices
 * - `index_email_body_raw_event_id` — 1:1 lookup from raw event → body (EMAIL-002
 *   person_ref resolution + retention-sweep join).
 * - `index_email_body_provider_message_id` — idempotency lookup by Gmail / Graph /
 *   IMAP message id on re-poll (EMAIL-001 folder-scoped fetch).
 *
 * Both indices use Room's default `index_<table>_<column>` naming so the v5→v6
 * migration SQL matches the KSP-generated schema snapshot (MigrationTestHelper drift
 * check).
 *
 * @see com.becalm.android.data.local.db.dao.EmailBodyDao
 * @see RawIngestionEventEntity
 */
@Entity(
    tableName = "email_body",
    foreignKeys = [
        ForeignKey(
            entity = RawIngestionEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["raw_event_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // `raw_event_id` is UNIQUE: each `raw_ingestion_events` row carries at most one
        // body (1:1). Enforcing this at the storage layer turns [EmailBodyDao.insert]
        // with `OnConflictStrategy.REPLACE` into a genuine re-poll-safe upsert — without
        // a unique constraint, a random-UUID primary key would let a second insert silently
        // create a duplicate row and `getByRawEventId(... LIMIT 1)` would return a stale copy.
        Index(value = ["raw_event_id"], unique = true),
        Index(value = ["provider_message_id"]),
    ],
)
public data class EmailBodyEntity(

    /**
     * UUID v4 primary key, generated client-side so Room INSERT and follow-up worker
     * reads share the same identifier without a DB round-trip.
     * Spec: `.spec/contracts/data-model.yml:327-390 § email_body.id`.
     */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /**
     * Foreign key to [RawIngestionEventEntity.id]. `ON DELETE CASCADE` co-deletes this
     * row when the parent disappears — the structural half of EMAIL-006's retention
     * co-delete contract. The parent raw-event UUID is the only cross-layer handle
     * that reaches Railway as a stored raw-event reference. When backend Gemini email
     * extraction is enabled, [bodyPlain] may be sent transiently in the upload request as
     * extraction context; the backend does not persist it in `raw_ingestion_events`.
     * Spec: `.spec/contracts/data-model.yml:327-390 § email_body.raw_event_id`.
     */
    @ColumnInfo(name = "raw_event_id")
    val rawEventId: String,

    /**
     * Provider-side message identifier used for idempotent re-polling:
     * - Gmail: `messages/{id}`
     * - Microsoft Graph: message `id` field
     * - IMAP (Naver / Daum): RFC 5322 `Message-ID`, falling back to `UIDVALIDITY:UID`
     *
     * Indexed via `index_email_body_provider_message_id` so ingestion workers can
     * short-circuit "already stored" checks without a full scan.
     * Spec: EMAIL-001 (`.spec/email-pipeline.spec.yml:15-18`) +
     * `.spec/contracts/data-model.yml:327-390 § provider_message_id`.
     */
    @ColumnInfo(name = "provider_message_id")
    val providerMessageId: String,

    /**
     * Folder / direction hint. Valid values: `"INBOX"` | `"SENT"`.
     *
     * EMAIL-002 uses this to derive [RawIngestionEventEntity.personRef]:
     * `INBOX` → `From` header, `SENT` → first `To` recipient. Also mirrored to the
     * parent [RawIngestionEventEntity.folder] column so downstream workers can read
     * the hint without a JOIN.
     * Spec: `.spec/email-pipeline.spec.yml:15-18 § EMAIL-001`.
     */
    @ColumnInfo(name = "folder")
    val folder: String,

    /**
     * Email subject line, verbatim. Null when the source message has no subject header
     * (RFC 5322 permits empty subjects). Unbounded TEXT — UI layer truncates.
     * Spec: `.spec/contracts/data-model.yml:327-390 § subject`.
     */
    @ColumnInfo(name = "subject")
    val subject: String? = null,

    /**
     * Normalized sender email address, lowercased. Null when the header is missing or
     * unparseable ([parseFailed] = true). EMAIL-002 uses this for `INBOX`-direction
     * [RawIngestionEventEntity.personRef] assignment.
     * Spec: `.spec/contracts/data-model.yml:327-390 § from_address`.
     */
    @ColumnInfo(name = "from_address")
    val fromAddress: String? = null,

    /**
     * JSON-encoded recipient list, shape `[{"email": "...", "name": "..."?}, ...]`.
     * Serialized via Moshi in the Repository layer; opaque TEXT to Room. EMAIL-002 uses
     * the first entry for `SENT`-direction personRef; the full list is retained for
     * future multi-recipient UI. When recipients exceed 10 the ingestion worker sets
     * [groupEmail] true per `.spec/email-pipeline.spec.yml § group_email`.
     * Spec: `.spec/contracts/data-model.yml:327-390 § to_addresses`.
     */
    @ColumnInfo(name = "to_addresses")
    val toAddresses: String? = null,

    /**
     * Plain-text email body. The ingestion worker stores a 200-char snippet per
     * EMAIL-003, but the column is unbounded TEXT so a future fetch-full expansion
     * would not require a schema change. When [parseFailed] flips true this column
     * MUST be cleared via [com.becalm.android.data.local.db.dao.EmailBodyDao.markParseFailed]
     * — EMAIL-007 requires a visible-to-user degrade state, not a silent partial result.
     * Spec: `.spec/contracts/data-model.yml:327-390 § body_plain`.
     */
    @ColumnInfo(name = "body_plain")
    val bodyPlain: String? = null,

    /**
     * Raw HTML email body. Retained for future extraction workers (Jsoup-based
     * `EmailHtmlParser`, EXTRACT-EMAIL-005). Null when the message has no HTML part.
     * Spec: `.spec/contracts/data-model.yml:327-390 § body_html`.
     */
    @ColumnInfo(name = "body_html")
    val bodyHtml: String? = null,

    /**
     * JSON-encoded attachment descriptors, shape
     * `[{"filename": "...", "mime": "...", "size_bytes": N}, ...]`. Metadata only —
     * binary content is NOT stored, keeping the DB footprint bounded.
     * Spec: EMAIL-004 + `.spec/contracts/data-model.yml:327-390 § attachments_meta`.
     */
    @ColumnInfo(name = "attachments_meta")
    val attachmentsMeta: String? = null,

    /**
     * Raw email headers with threading fields (`In-Reply-To`, `References`, etc.)
     * preserved. Future thread-aware UI and `Message-ID` deduplication read from this
     * column.
     * Spec: EMAIL-005 + `.spec/contracts/data-model.yml:327-390 § raw_headers`.
     */
    @ColumnInfo(name = "raw_headers")
    val rawHeaders: String? = null,

    /**
     * True when the ingestion worker failed to parse the message body (malformed
     * MIME / unknown charset / Jsoup exception). Stored as INTEGER 0/1 with SQL-level
     * `DEFAULT 0` so ALTER TABLE backfills land as "not failed". On transition to true,
     * [bodyPlain] MUST be cleared via
     * [com.becalm.android.data.local.db.dao.EmailBodyDao.markParseFailed] — EMAIL-007
     * requires a visible degrade state, never a silent partial result.
     * Spec: EMAIL-007 + `.spec/contracts/data-model.yml:327-390 § parse_failed`.
     */
    @ColumnInfo(name = "parse_failed", defaultValue = "0")
    val parseFailed: Boolean = false,

    /**
     * True when the message was addressed to more than 10 recipients (mailing list /
     * BCC blast). UI MAY hide such emails from the person-centric timeline to avoid
     * noisy contact surfaces. INTEGER 0/1 with `DEFAULT 0` so backfills land as
     * "not a group".
     * Spec: `.spec/contracts/data-model.yml:327-390 § group_email`.
     */
    @ColumnInfo(name = "group_email", defaultValue = "0")
    val groupEmail: Boolean = false,

    /**
     * `timestamptz NOT NULL` — server-assigned receive timestamp. Stored as epoch
     * milliseconds via the [Instant] Room converter registered on
     * [com.becalm.android.data.local.db.BeCalmDatabase]; UI renders in Asia/Seoul.
     * Spec: `.spec/contracts/data-model.yml:327-390 § received_at`.
     */
    @ColumnInfo(name = "received_at")
    val receivedAt: Instant,
)
