package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.db.entity.EmailBodyEntity

/**
 * Room DAO for the `email_body` table.
 *
 * ## Room-only store
 * Every method here operates on data that MUST NOT cross the device boundary
 * (EMAIL-006 / `.spec/email-pipeline.spec.yml:58-64`). Callers that persist rows via
 * [insert] are trusted not to mirror the result into any network DTO; the Repository
 * layer (future `feat/repo/email` PR) enforces this at the boundary.
 *
 * ## Cold vs one-shot
 * All methods here are one-shot `suspend` functions — the local IMAP adapters and
 * retention sweep never require reactive re-emission. No Flow-returning queries are
 * declared on this DAO by design.
 *
 * ## Index coverage
 * See [EmailBodyEntity] for the two declared indices; every query below is designed
 * so that the planner can use one of them — full-table scans on email_body would
 * violate the performance-tier expectations described in the ingestion spec.
 */
@Dao
public interface EmailBodyDao {

    // ─── Write ─────────────────────────────────────────────────────────────────

    /**
     * Inserts a single [EmailBodyEntity], replacing any existing row that collides on
     * either the primary key `id` or the `UNIQUE(raw_event_id)` index declared on
     * [EmailBodyEntity]. The 1:1 relationship between a raw ingestion event and its
     * body is enforced at the SQLite layer, so re-polling the same email yields a
     * genuine upsert (one row per `raw_event_id`) even though [EmailBodyEntity.id] is
     * a client-generated random UUID that would otherwise never collide on PK.
     *
     * Used by local IMAP adapters under EMAIL-001..007.
     *
     * @param entity The body row to persist.
     * @return The SQLite rowid of the inserted row. Identical rowids across
     *   successive REPLACE calls are not guaranteed — callers that need the row's
     *   `id` MUST read [EmailBodyEntity.id] from the argument rather than the return.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: EmailBodyEntity): Long

    /**
     * Returns the single body row associated with [rawEventId], or null when no body
     * has been captured yet (e.g. the raw event was ingested before EMAIL-001 ran, or
     * parsing failed and the body was cleared).
     *
     * Used by: EMAIL-002 person_ref derivation (reading [EmailBodyEntity.fromAddress]
     * or the first entry of [EmailBodyEntity.toAddresses]) and local email detail views.
     *
     * The `LIMIT 1` is defensive — the 1:1 relationship is enforced by the
     * UNIQUE `raw_event_id` index declared on [EmailBodyEntity].
     *
     * @param rawEventId Foreign-key value pointing at [RawIngestionEventEntity.id].
     * @return The matching body row, or null if none exists.
     */
    @Query(
        "SELECT * FROM email_body WHERE raw_event_id = :rawEventId LIMIT 1",
    )
    suspend fun getByRawEventId(rawEventId: String): EmailBodyEntity?

    /**
     * Returns an existing body for the same provider message scoped by the owning
     * raw event. IMAP workers use this as a second dedupe guard when UIDVALIDITY
     * changes and a provider returns the same RFC 5322 Message-ID under a new UID.
     */
    @Query(
        """
        SELECT email_body.* FROM email_body
        INNER JOIN raw_ingestion_events ON raw_ingestion_events.id = email_body.raw_event_id
        WHERE raw_ingestion_events.user_id = :userId
          AND raw_ingestion_events.source_type = :sourceType
          AND raw_ingestion_events.folder = :folder
          AND email_body.provider_message_id = :providerMessageId
        ORDER BY raw_ingestion_events.timestamp DESC
        LIMIT 1
        """,
    )
    suspend fun findByProviderMessage(
        userId: String,
        sourceType: String,
        folder: String,
        providerMessageId: String,
    ): EmailBodyEntity?

    /**
     * Marks an email body as unparseable: sets `parse_failed = 1` and clears
     * [EmailBodyEntity.bodyPlain].
     *
     * Called by the ingestion workers whenever the MIME / Jsoup / encoding pipeline
     * throws. Clearing the plain-text body in the same statement prevents partially
     * parsed content from being surfaced to the user or fed into the LLM extractor —
     * EMAIL-007 requires a visible-to-user degrade state rather than a silent partial
     * result.
     *
     * @param id Primary key of the row to mark.
     * @return The number of rows updated (0 when [id] does not exist, 1 otherwise).
     */
    @Query(
        "UPDATE email_body SET parse_failed = 1, body_plain = NULL WHERE id = :id",
    )
    suspend fun markParseFailed(id: String): Int

    /**
     * Deletes every `email_body` row whose parent `raw_ingestion_events` row is older
     * than [cutoffMillis] **and** has `sync_status = 'synced'` — the two-condition
     * gate mandated by `.spec/data-ingestion.spec.yml:160`:
     *
     * > "Room raw_ingestion_events와 EmailBody는 timestamp 기준 30일 rolling window로
     * >  자동 삭제된다 — 단 sync_status='synced' 조건을 만족할 때만."
     *
     * Used by the future `RetentionSweepWorker` (separate PR `feat/worker/retention`).
     * Although the foreign key declares `ON DELETE CASCADE`, the worker purges
     * `email_body` first so that a partial crash between the two DELETE statements
     * never leaves orphan body rows whose retention state is ambiguous — the sweep
     * then explicitly DELETEs the matching raw_ingestion_events rows in a second
     * statement inside the same transaction.
     *
     * @param cutoffMillis Inclusive upper bound as UTC epoch milliseconds — rows whose
     *   parent `timestamp < :cutoffMillis` are eligible. Callers compute this as
     *   `now - 30.days` using [kotlinx.datetime.Clock.System].
     * @return The number of `email_body` rows deleted.
     */
    @Query(
        "DELETE FROM email_body WHERE raw_event_id IN " +
            "(SELECT id FROM raw_ingestion_events " +
            "WHERE sync_status = 'synced' AND timestamp < :cutoffMillis)",
    )
    suspend fun deleteOlderThanForSynced(cutoffMillis: Long): Int

    @Query(
        """
        SELECT email_body.* FROM email_body
        INNER JOIN raw_ingestion_events ON raw_ingestion_events.id = email_body.raw_event_id
        WHERE raw_ingestion_events.user_id = :userId
        ORDER BY raw_ingestion_events.timestamp DESC
        """,
    )
    suspend fun findAllForUser(userId: String): List<EmailBodyEntity>
}
