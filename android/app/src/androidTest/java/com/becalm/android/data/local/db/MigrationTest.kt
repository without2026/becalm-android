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
    }
}
