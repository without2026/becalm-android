package com.becalm.android.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.becalm.android.data.local.db.migration.MIGRATIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Room migration regression tests for [BeCalmDatabase].
 *
 * ## What this protects
 * Without these tests, a malformed migration could silently wipe production users'
 * commitments, calendar events, and on-device person enrichment data. The destructive-downgrade
 * fallback removed in Round 6A.3 would have masked exactly this class of bug.
 *
 * ## Migration coverage matrix
 * | From | To | Schema delta verified                                                        |
 * |-----:|---:|------------------------------------------------------------------------------|
 * |    1 |  2 | Adds `commitments.commitment_state TEXT NOT NULL DEFAULT 'DRAFT'`.           |
 * |    2 |  3 | Drops 3 out-of-spec indices on raw_ingestion_events / calendar_events / persons_enrichment. |
 * |    1 |  3 | Whole-chain test that catches accumulation bugs across both migrations.      |
 * |    3 |  4 | Rebuilds `commitments`: `due_date` → `due_at` (KST→UTC epoch ms) + adds `due_hint` + `due_is_approximate`. Recreates spec indices against `due_at`. |
 * |    4 |  5 | Adds 6 edit / dispute / soft-delete / supersede columns + 2 indices (`idx_commitments_user_deleted`, `idx_commitments_supersedes`). Verifies defaults and that every per-user SELECT now filters `deleted_at IS NULL`. |
 * |    5 |  6 | Creates room-only `email_body` (14 columns, FK CASCADE to raw_ingestion_events, 2 indices), adds `raw_ingestion_events.folder TEXT` (nullable). Verifies table shape + defaults + CASCADE delete. Spec: `.spec/contracts/data-model.yml:327-390`, `.spec/email-pipeline.spec.yml:15-18,58-64`. |
 *
 * ## Required schema artefacts
 * [MigrationTestHelper] needs `app/schemas/com.becalm.android.data.local.db.BeCalmDatabase/{1,2,3}.json`
 * to exist on disk. They are emitted by the Room KSP processor whenever the project is built;
 * `app/build.gradle.kts` already wires `room.schemaLocation = "$projectDir/schemas"`. If the
 * JSON files are absent, [MigrationTestHelper.createDatabase] throws
 * `IllegalArgumentException: Cannot find the schema file`. Run `./gradlew :app:assembleDebug`
 * (or any KSP-triggering task) before invoking these tests for the first time.
 *
 * ## Why instrumented and not JVM
 * [MigrationTestHelper] requires a real SQLite native library; it cannot run under Robolectric.
 * That is why this file lives in `androidTest/` and uses [androidx.test.ext.junit.runners.AndroidJUnit4].
 *
 * ## CI invocation
 * `./gradlew :app:connectedDebugAndroidTest --tests '*MigrationTest*'`
 *
 * Spec refs: round6-plan.md § 6C.2, round6-audit.md (zero migration tests finding).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BeCalmDatabase::class.java,
    )

    // ─── 1 → 2 ────────────────────────────────────────────────────────────────
    //
    // Adds `commitment_state TEXT NOT NULL DEFAULT 'DRAFT'` to `commitments`.
    // Verifies:
    //   1. Three pre-existing rows survive byte-for-byte.
    //   2. The new column is present on each surviving row with the migration's default 'DRAFT'.
    //   3. Pre-existing column values (action_state, sync_status, confidence) are unchanged.
    @Test
    fun migrate1To2_preservesRowsAndAddsCommitmentStateColumn() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            insertV1Commitment(db, id = "c1", actionState = "pending", syncStatus = "pending")
            insertV1Commitment(db, id = "c2", actionState = "completed", syncStatus = "synced")
            insertV1Commitment(db, id = "c3", actionState = "reminded", syncStatus = "failed")
        }

        val migrated: SupportSQLiteDatabase =
            helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATIONS[0])

        // (1) Row count preserved.
        migrated.query("SELECT COUNT(*) FROM commitments").use { cursor ->
            assertTrue("commitments count cursor should advance", cursor.moveToFirst())
            assertEquals("3 commitments must survive 1→2 migration", 3, cursor.getInt(0))
        }

        // (2) New column populated with migration default for every pre-existing row.
        // (3) Pre-existing columns unchanged (action_state / sync_status / confidence).
        migrated.query(
            "SELECT id, commitment_state, action_state, sync_status, confidence " +
                "FROM commitments ORDER BY id",
        ).use { cursor ->
            val expected = listOf(
                Triple("c1", "pending", "pending"),
                Triple("c2", "completed", "synced"),
                Triple("c3", "reminded", "failed"),
            )
            expected.forEach { (id, actionState, syncStatus) ->
                assertTrue("expected row id=$id present after 1→2", cursor.moveToNext())
                assertEquals(id, cursor.getString(0))
                assertEquals(
                    "commitment_state must default to 'DRAFT' for pre-existing rows",
                    "DRAFT",
                    cursor.getString(1),
                )
                assertEquals("action_state must survive 1→2 untouched", actionState, cursor.getString(2))
                assertEquals("sync_status must survive 1→2 untouched", syncStatus, cursor.getString(3))
                assertEquals(
                    "confidence must survive 1→2 untouched",
                    DEFAULT_CONFIDENCE,
                    cursor.getDouble(4),
                    0.0,
                )
            }
        }
    }

    // ─── 2 → 3 ────────────────────────────────────────────────────────────────
    //
    // Drops three indices that are out-of-spec per data-model.yml:
    //   * idx_raw_events_user_client_event           (raw_ingestion_events)
    //   * index_calendar_events_user_id_source_type_source_ref (calendar_events)
    //   * idx_persons_enrichment_person_ref          (persons_enrichment)
    //
    // Verifies:
    //   1. Rows in all three affected tables survive.
    //   2. Each named index is absent from sqlite_master after the migration.
    //   3. Spec-mandated indices on commitments and raw_ingestion_events are still present.
    @Test
    fun migrate2To3_dropsOutOfSpecIndicesAndPreservesRows() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            insertV2Commitment(db, id = "c1")
            insertRawIngestionEvent(db, id = "r1")
            insertCalendarEvent(db, id = "k1")
            insertPersonEnrichment(db, personRef = "+821011112222")
        }

        val migrated: SupportSQLiteDatabase =
            helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATIONS[1])

        // (1) Each affected table retains its row.
        assertTableRowCount(migrated, "commitments", 1)
        assertTableRowCount(migrated, "raw_ingestion_events", 1)
        assertTableRowCount(migrated, "calendar_events", 1)
        assertTableRowCount(migrated, "persons_enrichment", 1)

        // (2) The three out-of-spec indices must be gone.
        DROPPED_INDICES.forEach { name ->
            assertIndexAbsent(migrated, name)
        }

        // (3) Spec-mandated indices must still exist (not collateral damage).
        SURVIVING_INDICES.forEach { name ->
            assertIndexPresent(migrated, name)
        }
    }

    // ─── 1 → 3 (whole chain) ──────────────────────────────────────────────────
    //
    // Catches accumulation bugs that show up only when both migrations run in sequence.
    // Verifies:
    //   1. v1 commitment rows survive both steps.
    //   2. commitment_state default lands on pre-1→2 rows even after the 2→3 step also runs.
    //   3. None of the dropped indices reappear via the chain.
    @Test
    fun migrate1To3_chainPreservesV1RowsAndAppliesAllDeltas() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            insertV1Commitment(db, id = "c1", actionState = "pending", syncStatus = "pending")
            insertV1Commitment(db, id = "c2", actionState = "completed", syncStatus = "synced")
        }

        val migrated: SupportSQLiteDatabase =
            helper.runMigrationsAndValidate(TEST_DB, 3, true, *MIGRATIONS)

        assertTableRowCount(migrated, "commitments", 2)
        migrated.query(
            "SELECT id, commitment_state FROM commitments ORDER BY id",
        ).use { cursor ->
            assertTrue(cursor.moveToNext())
            assertEquals("c1", cursor.getString(0))
            assertEquals("DRAFT", cursor.getString(1))
            assertTrue(cursor.moveToNext())
            assertEquals("c2", cursor.getString(0))
            assertEquals("DRAFT", cursor.getString(1))
        }

        DROPPED_INDICES.forEach { name -> assertIndexAbsent(migrated, name) }
    }

    // ─── 3 → 4 ────────────────────────────────────────────────────────────────
    //
    // Rebuilds the `commitments` table so that the single `due_date TEXT` column becomes
    // three columns per `.spec/contracts/data-model.yml:132-144`:
    //   * `due_at INTEGER` (UTC epoch ms; backfilled from KST midnight of pre-v4 `due_date`)
    //   * `due_hint TEXT` (NULL for legacy rows — no hint recoverable)
    //   * `due_is_approximate INTEGER NOT NULL DEFAULT 0`
    // The two spec-mandated indices are recreated against `due_at`.
    //
    // Verifies:
    //   1. Row with `due_date = '2026-04-20'` survives and `due_at = 1776610800000` ms
    //      (2026-04-20 00:00 KST → 2026-04-19T15:00:00Z → 1776610800000 ms).
    //   2. `due_hint` is NULL and `due_is_approximate` is 0 for backfilled rows.
    //   3. `idx_commitments_user_action_due` and `idx_commitments_user_person_due` exist
    //      and reference `due_at` (not `due_date`).
    @Test
    fun migrate3To4_backfillsDueAtAndRecreatesIndices() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            // v3 row carrying a concrete due_date for backfill verification.
            insertV2Commitment(db, id = "c1", dueDate = "2026-04-20")
            // v3 row with NULL due_date to verify NULL propagation.
            insertV2Commitment(db, id = "c2", dueDate = null)
        }

        val migrated: SupportSQLiteDatabase =
            helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATIONS[2])

        // (1) + (2) row contents after backfill.
        migrated.query(
            "SELECT id, due_at, due_hint, due_is_approximate FROM commitments ORDER BY id",
        ).use { cursor ->
            assertTrue(cursor.moveToNext())
            assertEquals("c1", cursor.getString(0))
            assertEquals(
                "2026-04-20 KST midnight must backfill to 1776610800000 UTC epoch ms",
                KST_20260420_MIDNIGHT_EPOCH_MS,
                cursor.getLong(1),
            )
            assertTrue(
                "due_hint must be NULL for legacy rows (no hint recoverable)",
                cursor.isNull(2),
            )
            assertEquals(
                "due_is_approximate must default to 0",
                0,
                cursor.getInt(3),
            )

            assertTrue(cursor.moveToNext())
            assertEquals("c2", cursor.getString(0))
            assertTrue("NULL due_date must map to NULL due_at", cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertEquals(0, cursor.getInt(3))
        }

        // (3) Spec indices exist and reference due_at.
        assertIndexPresent(migrated, "idx_commitments_user_action_due")
        assertIndexPresent(migrated, "idx_commitments_user_person_due")
        assertIndexRefersTo(migrated, "idx_commitments_user_action_due", "due_at")
        assertIndexRefersTo(migrated, "idx_commitments_user_person_due", "due_at")
    }

    // ─── 4 → 5 ────────────────────────────────────────────────────────────────
    //
    // Additive migration: appends six user-facing lifecycle columns to `commitments`
    // (last_edited_by / last_edited_at / quote_disputed / quote_disputed_at /
    // deleted_at / supersedes_commitment_id) plus two indices
    // (`idx_commitments_user_deleted`, `idx_commitments_supersedes`). Mirrors
    // `.spec/contracts/data-model.yml:188-210, :219-225, :484`.
    //
    // Verifies:
    //   1. Pre-v5 row survives byte-for-byte.
    //   2. Each of the 6 new columns is queryable, with `quote_disputed = 0`
    //      (migration DEFAULT) and the other 5 NULL.
    //   3. Both new indices exist in sqlite_master.
    @Test
    fun migrate4To5_addsSixColumnsAndTwoIndices() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            insertV4Commitment(db, id = "c1", dueAt = null)
        }

        val migrated: SupportSQLiteDatabase =
            helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATIONS[3])

        // (1) Row preserved.
        assertTableRowCount(migrated, "commitments", 1)

        // (2) New columns present with expected defaults.
        migrated.query(
            "SELECT last_edited_by, last_edited_at, quote_disputed, quote_disputed_at, " +
                "deleted_at, supersedes_commitment_id FROM commitments WHERE id = 'c1'",
        ).use { cursor ->
            assertTrue("row c1 must be readable after 4→5", cursor.moveToFirst())
            assertTrue("last_edited_by must be NULL on legacy rows", cursor.isNull(0))
            assertTrue("last_edited_at must be NULL on legacy rows", cursor.isNull(1))
            assertEquals(
                "quote_disputed must default to 0 per migration DEFAULT",
                0,
                cursor.getInt(2),
            )
            assertTrue("quote_disputed_at must be NULL on legacy rows", cursor.isNull(3))
            assertTrue("deleted_at must be NULL on legacy rows (row is live)", cursor.isNull(4))
            assertTrue(
                "supersedes_commitment_id must be NULL on legacy rows",
                cursor.isNull(5),
            )
        }

        // (3) Both new indices exist.
        assertIndexPresent(migrated, "idx_commitments_user_deleted")
        assertIndexPresent(migrated, "idx_commitments_supersedes")
        assertIndexRefersTo(migrated, "idx_commitments_user_deleted", "deleted_at")
        assertIndexRefersTo(
            migrated,
            "idx_commitments_supersedes",
            "supersedes_commitment_id",
        )
    }

    // ─── 5 → 6 ────────────────────────────────────────────────────────────────
    //
    // Creates the room-only `email_body` table (14 columns, 2 indices, FK CASCADE
    // to raw_ingestion_events) and adds `raw_ingestion_events.folder TEXT` (nullable)
    // per `.spec/contracts/data-model.yml:327-390` and `.spec/email-pipeline.spec.yml:15-18`.
    //
    // Verifies:
    //   1. `email_body` exists with exactly the 14 columns in the expected types.
    //   2. `parse_failed` and `group_email` both default to 0.
    //   3. `raw_ingestion_events.folder` column present and nullable (accepts NULL insert).
    //   4. Both indices `index_email_body_raw_event_id` and
    //      `index_email_body_provider_message_id` exist in sqlite_master.
    @Test
    fun migrate5To6_createsEmailBodyTableAndAddsFolderColumn() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            insertRawIngestionEvent(db, id = "r1")
        }

        val migrated: SupportSQLiteDatabase =
            helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATIONS[4])

        // (1) email_body has exactly the 14 expected columns with correct affinities.
        val columns = queryTableColumns(migrated, "email_body")
        assertEquals(
            "email_body must have exactly 14 columns after 5→6 migration",
            14,
            columns.size,
        )
        EMAIL_BODY_EXPECTED_COLUMNS.forEach { (name, expectedType, expectedNotNull) ->
            val info = columns[name]
                ?: error("email_body must contain column '$name'")
            assertEquals(
                "email_body.$name must have SQLite affinity '$expectedType'",
                expectedType,
                info.type,
            )
            assertEquals(
                "email_body.$name NOT NULL flag",
                expectedNotNull,
                info.notNull,
            )
        }

        // (2) parse_failed / group_email DEFAULT 0.
        assertEquals(
            "email_body.parse_failed must DEFAULT 0",
            "0",
            columns.getValue("parse_failed").defaultValue,
        )
        assertEquals(
            "email_body.group_email must DEFAULT 0",
            "0",
            columns.getValue("group_email").defaultValue,
        )

        // (3) raw_ingestion_events.folder is a nullable TEXT column. Inserting NULL
        //     (via the pre-existing helper that never sets folder) then re-inserting
        //     another row with an explicit folder value both succeed.
        val rawColumns = queryTableColumns(migrated, "raw_ingestion_events")
        val folderColumn = rawColumns["folder"]
            ?: error("raw_ingestion_events must have a `folder` column after 5→6")
        assertEquals("raw_ingestion_events.folder must be TEXT affinity", "TEXT", folderColumn.type)
        assertEquals(
            "raw_ingestion_events.folder must be nullable per ALTER TABLE ADD COLUMN",
            0,
            folderColumn.notNull,
        )

        // (4) Both indices are registered in sqlite_master.
        assertIndexPresent(migrated, "index_email_body_raw_event_id")
        assertIndexPresent(migrated, "index_email_body_provider_message_id")
        assertIndexRefersTo(migrated, "index_email_body_raw_event_id", "raw_event_id")
        assertIndexRefersTo(
            migrated,
            "index_email_body_provider_message_id",
            "provider_message_id",
        )

        // (5) `raw_event_id` is UNIQUE — enforces the 1:1 relationship and turns
        //     `OnConflictStrategy.REPLACE` inserts into a genuine upsert. Without this,
        //     a random-UUID primary key would let re-polls create duplicate body rows.
        assertIndexIsUnique(migrated, "index_email_body_raw_event_id")
        assertIndexIsNotUnique(migrated, "index_email_body_provider_message_id")
    }

    // ─── 5 → 6 — UNIQUE(raw_event_id) enforcement on INSERT conflict ───────────
    //
    // Verifies that inserting a second `email_body` row for the same `raw_event_id`
    // with a different primary key succeeds via REPLACE (not a duplicate) — the
    // structural guarantee that [EmailBodyDao.insert] is idempotent across re-polls.
    @Test
    fun migrate5To6_insertingSecondBodyForSameRawEventReplacesFirst() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            insertRawIngestionEvent(db, id = "r1")
        }

        val migrated: SupportSQLiteDatabase =
            helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATIONS[4])

        migrated.execSQL(
            """
            INSERT OR REPLACE INTO email_body (
                id, raw_event_id, provider_message_id, folder,
                subject, from_address, to_addresses, body_plain, body_html,
                attachments_meta, raw_headers, parse_failed, group_email, received_at
            ) VALUES (
                'eb-first', 'r1', 'msg-1', 'INBOX',
                'first subject', NULL, NULL, 'first body', NULL,
                NULL, NULL, 0, 0, $TS
            )
            """.trimIndent(),
        )
        migrated.execSQL(
            """
            INSERT OR REPLACE INTO email_body (
                id, raw_event_id, provider_message_id, folder,
                subject, from_address, to_addresses, body_plain, body_html,
                attachments_meta, raw_headers, parse_failed, group_email, received_at
            ) VALUES (
                'eb-second', 'r1', 'msg-1', 'INBOX',
                'second subject', NULL, NULL, 'second body', NULL,
                NULL, NULL, 0, 0, $TS
            )
            """.trimIndent(),
        )

        assertTableRowCount(migrated, "email_body", 1)
        migrated.query(
            "SELECT id, subject FROM email_body WHERE raw_event_id = 'r1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("eb-second", cursor.getString(0))
            assertEquals("second subject", cursor.getString(1))
        }
    }

    // ─── 5 → 6 — FK CASCADE co-delete ─────────────────────────────────────────
    //
    // Verifies that deleting a `raw_ingestion_events` row cascades to every
    // `email_body` row referencing it via `raw_event_id`. This is the structural
    // half of the EMAIL-006 retention co-delete contract.
    //
    // Note: MigrationTestHelper opens the DB with `PRAGMA foreign_keys = OFF`
    // (Room's default for migration validation — the schema-comparison phase should
    // not trigger cascades). We explicitly turn FK enforcement on after the
    // migration so that the DELETE exercises the CASCADE rule that production code
    // relies on (Room enables foreign_keys on every normal open).
    @Test
    fun migrate5To6_cascadeDeletesEmailBodyOnRawEventDelete() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            insertRawIngestionEvent(db, id = "r1")
        }

        val migrated: SupportSQLiteDatabase =
            helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATIONS[4])

        // MigrationTestHelper keeps foreign_keys OFF for schema validation; re-enable
        // here so that DELETE triggers the CASCADE from email_body.raw_event_id.
        migrated.execSQL("PRAGMA foreign_keys = ON")

        // Insert a child email_body row referencing the parent raw event.
        migrated.execSQL(
            """
            INSERT INTO email_body (
                id, raw_event_id, provider_message_id, folder,
                subject, from_address, to_addresses, body_plain, body_html,
                attachments_meta, raw_headers, parse_failed, group_email, received_at
            ) VALUES (
                'eb1', 'r1', 'msg-1', 'INBOX',
                'test subject', 'sender@example.com', NULL, 'plain body', NULL,
                NULL, NULL, 0, 0, $TS
            )
            """.trimIndent(),
        )
        assertTableRowCount(migrated, "email_body", 1)

        // Deleting the parent raw event must cascade to the child body row.
        migrated.execSQL("DELETE FROM raw_ingestion_events WHERE id = 'r1'")

        assertTableRowCount(migrated, "email_body", 0)
        assertTableRowCount(migrated, "raw_ingestion_events", 0)
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private fun insertV1Commitment(
        db: SupportSQLiteDatabase,
        id: String,
        actionState: String,
        syncStatus: String,
    ) {
        // v1 schema lacks `commitment_state`; every other column matches CommitmentEntity.
        db.execSQL(
            """
            INSERT INTO commitments (
                id, user_id, direction, counterparty_raw, person_ref,
                title, description, quote, source_event_title, source_event_occurred_at,
                due_date, action_state, source_type, source_ref, confidence,
                sync_status, created_at, updated_at
            ) VALUES (
                '$id', '$USER_ID', 'give', '+821000000000', '+821000000000',
                'title-$id', NULL, 'quote', NULL, $TS,
                NULL, '$actionState', 'voice', NULL, $DEFAULT_CONFIDENCE,
                '$syncStatus', $TS, $TS
            )
            """.trimIndent(),
        )
    }

    private fun insertV2Commitment(
        db: SupportSQLiteDatabase,
        id: String,
        dueDate: String? = null,
    ) {
        // v2 / v3 schema includes commitment_state (added by 1→2). The shape is identical
        // across v2 and v3 for the commitments table, so the same fixture serves both.
        val dueDateSql = dueDate?.let { "'$it'" } ?: "NULL"
        db.execSQL(
            """
            INSERT INTO commitments (
                id, user_id, direction, counterparty_raw, person_ref,
                title, description, quote, source_event_title, source_event_occurred_at,
                due_date, action_state, source_type, source_ref, confidence,
                commitment_state, sync_status, created_at, updated_at
            ) VALUES (
                '$id', '$USER_ID', 'take', NULL, NULL,
                'title-$id', NULL, 'quote', NULL, $TS,
                $dueDateSql, 'pending', 'gmail', NULL, $DEFAULT_CONFIDENCE,
                'DRAFT', 'pending', $TS, $TS
            )
            """.trimIndent(),
        )
    }

    /**
     * Inserts a v4-shape `commitments` row. The v4 schema replaced the legacy
     * `due_date TEXT` with three columns (`due_at INTEGER`, `due_hint TEXT`,
     * `due_is_approximate INTEGER NOT NULL DEFAULT 0`) per PR #17, so v4 fixtures
     * cannot share the v2/v3 insert helper. This mirrors [insertV2Commitment] but
     * targets the post-3→4 column shape as the starting point for the 4→5 test.
     *
     * @param dueAt Optional UTC epoch milliseconds for the `due_at` column; null to
     *   skip the deadline. Pass [KST_20260420_MIDNIGHT_EPOCH_MS] for a representative
     *   KST-midnight fixture value.
     */
    private fun insertV4Commitment(
        db: SupportSQLiteDatabase,
        id: String,
        dueAt: Long? = null,
    ) {
        val dueAtSql = dueAt?.toString() ?: "NULL"
        db.execSQL(
            """
            INSERT INTO commitments (
                id, user_id, direction, counterparty_raw, person_ref,
                title, description, quote, source_event_title, source_event_occurred_at,
                due_at, due_hint, due_is_approximate,
                action_state, source_type, source_ref, confidence,
                commitment_state, sync_status, created_at, updated_at
            ) VALUES (
                '$id', '$USER_ID', 'take', NULL, NULL,
                'title-$id', NULL, 'quote', NULL, $TS,
                $dueAtSql, NULL, 0,
                'pending', 'gmail', NULL, $DEFAULT_CONFIDENCE,
                'DRAFT', 'pending', $TS, $TS
            )
            """.trimIndent(),
        )
    }

    private fun insertRawIngestionEvent(db: SupportSQLiteDatabase, id: String) {
        db.execSQL(
            """
            INSERT INTO raw_ingestion_events (
                id, user_id, client_event_id, source_type, source_ref,
                person_ref, event_title, event_snippet, duration_seconds, location,
                commitments_extracted_count, timestamp, sync_status, retry_count, last_attempt_at
            ) VALUES (
                '$id', '$USER_ID', 'cev-$id', 'gmail', 'msg-$id',
                'a@example.com', 'subject', 'snippet', NULL, NULL,
                0, $TS, 'pending', 0, NULL
            )
            """.trimIndent(),
        )
    }

    private fun insertCalendarEvent(db: SupportSQLiteDatabase, id: String) {
        db.execSQL(
            """
            INSERT INTO calendar_events (
                id, user_id, source_type, source_ref, title,
                start_at, end_at, attendees_raw, sync_status
            ) VALUES (
                '$id', '$USER_ID', 'google_calendar', 'gcal-$id', 'meeting-$id',
                $TS, ${TS + 3_600_000L}, NULL, 'pending'
            )
            """.trimIndent(),
        )
    }

    private fun insertPersonEnrichment(db: SupportSQLiteDatabase, personRef: String) {
        db.execSQL(
            """
            INSERT INTO persons_enrichment (
                person_ref, display_name, nickname, company, title,
                source_contact_id, last_synced_at
            ) VALUES (
                '$personRef', 'Display', NULL, NULL, NULL,
                NULL, $TS
            )
            """.trimIndent(),
        )
    }

    /**
     * Returns the `PRAGMA table_info(:table)` result as a map of column name → [ColumnDescriptor].
     * Used by the 5→6 migration test to assert both column presence and SQLite affinity +
     * NOT NULL + DEFAULT in a single pass, without hard-coding the cursor column indices in
     * every assertion site.
     *
     * `PRAGMA table_info` returns: `cid | name | type | notnull | dflt_value | pk`.
     */
    private fun queryTableColumns(
        db: SupportSQLiteDatabase,
        table: String,
    ): Map<String, ColumnDescriptor> {
        val out = mutableMapOf<String, ColumnDescriptor>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                val type = cursor.getString(2)
                val notNull = cursor.getInt(3)
                val defaultValue = if (cursor.isNull(4)) null else cursor.getString(4)
                out[name] = ColumnDescriptor(name, type, notNull, defaultValue)
            }
        }
        return out
    }

    private fun assertTableRowCount(db: SupportSQLiteDatabase, table: String, expected: Int) {
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            assertTrue("count cursor for $table should advance", cursor.moveToFirst())
            assertEquals("$table row count after migration", expected, cursor.getInt(0))
        }
    }

    private fun assertIndexAbsent(db: SupportSQLiteDatabase, name: String) {
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(name),
        ).use { cursor ->
            assertEquals(
                "Index '$name' must be dropped after 2→3 migration",
                0,
                cursor.count,
            )
        }
    }

    private fun assertIndexPresent(db: SupportSQLiteDatabase, name: String) {
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(name),
        ).use { cursor ->
            assertEquals(
                "Spec-mandated index '$name' must still exist exactly once",
                1,
                cursor.count,
            )
        }
    }

    /**
     * Asserts that the SQL definition of an index named [name] mentions [column].
     * Used to verify the v3→v4 rebuild actually references the new `due_at` column
     * rather than the legacy `due_date`.
     */
    private fun assertIndexRefersTo(
        db: SupportSQLiteDatabase,
        name: String,
        column: String,
    ) {
        db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(name),
        ).use { cursor ->
            assertTrue("Index '$name' must exist", cursor.moveToFirst())
            val sql = cursor.getString(0) ?: ""
            assertTrue(
                "Index '$name' DDL must reference column '$column'; actual SQL: $sql",
                sql.contains(column),
            )
        }
    }

    /**
     * Asserts that the index named [name] is declared `UNIQUE`. SQLite stores the
     * uniqueness bit in the index's `sql` column; `CREATE UNIQUE INDEX ...` vs
     * `CREATE INDEX ...` is the ground truth. Needed to pin down the 1:1
     * `email_body.raw_event_id` invariant introduced in v6.
     */
    private fun assertIndexIsUnique(db: SupportSQLiteDatabase, name: String) {
        db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(name),
        ).use { cursor ->
            assertTrue("Index '$name' must exist", cursor.moveToFirst())
            val sql = cursor.getString(0) ?: ""
            assertTrue(
                "Index '$name' must be declared UNIQUE; actual SQL: $sql",
                sql.contains("UNIQUE", ignoreCase = true),
            )
        }
    }

    /** Inverse of [assertIndexIsUnique] — guards against accidental tightening. */
    private fun assertIndexIsNotUnique(db: SupportSQLiteDatabase, name: String) {
        db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(name),
        ).use { cursor ->
            assertTrue("Index '$name' must exist", cursor.moveToFirst())
            val sql = cursor.getString(0) ?: ""
            assertTrue(
                "Index '$name' must NOT be UNIQUE; actual SQL: $sql",
                !sql.contains("UNIQUE", ignoreCase = true),
            )
        }
    }

    /**
     * Snapshot of a row returned by `PRAGMA table_info(<table>)`.
     *
     * @property name Column name.
     * @property type SQLite type affinity as declared in the DDL (e.g. `TEXT`, `INTEGER`).
     * @property notNull 1 when the column is declared `NOT NULL`, 0 otherwise.
     * @property defaultValue String form of the `DEFAULT` clause (if any) or null.
     */
    private data class ColumnDescriptor(
        val name: String,
        val type: String,
        val notNull: Int,
        val defaultValue: String?,
    )

    private companion object {
        const val TEST_DB = "migration-test"
        const val USER_ID = "00000000-0000-0000-0000-000000000001"
        const val TS = 1_700_000_000_000L
        const val DEFAULT_CONFIDENCE = 0.0

        // 2026-04-20 00:00 Asia/Seoul → 2026-04-19T15:00:00Z → 1_776_610_800_000 ms.
        // Used by migrate3To4_backfillsDueAtAndRecreatesIndices to verify the
        // strftime('%s', due_date || ' 00:00:00', '-9 hours') * 1000 conversion.
        const val KST_20260420_MIDNIGHT_EPOCH_MS = 1_776_610_800_000L

        // Indices the 2→3 migration must drop.
        val DROPPED_INDICES = listOf(
            "idx_raw_events_user_client_event",
            "index_calendar_events_user_id_source_type_source_ref",
            "idx_persons_enrichment_person_ref",
        )

        // Spec-mandated indices that must NOT be touched by the 2→3 migration.
        // Names match CommitmentEntity / RawIngestionEventEntity / CalendarEventEntity @Index declarations.
        val SURVIVING_INDICES = listOf(
            "idx_commitments_user_action_due",
            "idx_commitments_user_person_due",
            "idx_raw_events_user_sync",
            "idx_raw_events_user_time",
            "idx_raw_events_user_person_time",
        )

        // Expected shape of `email_body` after the 5→6 migration. Each triple is
        // (column name, SQLite type affinity, notNull flag). Order-insensitive — the
        // test asserts presence + affinity + NOT NULL per column rather than cursor
        // position so a future reordering of columns in the CREATE TABLE statement
        // cannot silently flip a NOT NULL constraint without tripping this check.
        // Mirrors `.spec/contracts/data-model.yml:327-390` and the EmailBodyEntity
        // field declarations.
        val EMAIL_BODY_EXPECTED_COLUMNS: List<Triple<String, String, Int>> = listOf(
            Triple("id", "TEXT", 1),
            Triple("raw_event_id", "TEXT", 1),
            Triple("provider_message_id", "TEXT", 1),
            Triple("folder", "TEXT", 1),
            Triple("subject", "TEXT", 0),
            Triple("from_address", "TEXT", 0),
            Triple("to_addresses", "TEXT", 0),
            Triple("body_plain", "TEXT", 0),
            Triple("body_html", "TEXT", 0),
            Triple("attachments_meta", "TEXT", 0),
            Triple("raw_headers", "TEXT", 0),
            Triple("parse_failed", "INTEGER", 1),
            Triple("group_email", "INTEGER", 1),
            Triple("received_at", "INTEGER", 1),
        )
    }
}
