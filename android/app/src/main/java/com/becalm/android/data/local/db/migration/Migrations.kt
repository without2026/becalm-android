package com.becalm.android.data.local.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Central registry of all Room database [Migration] objects for [com.becalm.android.data.local.db.BeCalmDatabase].
 *
 * ## Usage
 * [MIGRATIONS] is consumed by [BeCalmDatabase.build] via `addMigrations(*MIGRATIONS)`. Room
 * walks this array at startup to find a migration path from the on-device schema version to the
 * current [com.becalm.android.data.local.db.BeCalmDatabase.DATABASE_VERSION]. If no path is
 * found and `fallbackToDestructiveMigrationOnDowngrade` applies, Room drops and recreates the
 * database — all local data is lost.
 *
 * ## Adding a new migration
 * 1. Bump `version` in the `@Database` annotation on [BeCalmDatabase] (e.g. 1 → 2).
 * 2. Export the new schema JSON by building the project — KSP writes it to `app/schemas/`.
 *    The JSON file name encodes the version number (e.g. `2.json`).
 * 3. Create a `Migration(oldVersion, newVersion)` instance below, referencing the schema diff.
 * 4. Append the instance to [MIGRATIONS].
 *
 * ## Schema JSON export
 * `exportSchema = true` in the `@Database` annotation directs the Room KSP processor to write
 * a `<version>.json` schema snapshot into `app/schemas/com.becalm.android.data.local.db.BeCalmDatabase/`.
 * These snapshots enable `MigrationTestHelper` to validate that your `execSQL` statements produce
 * the expected table shape. Commit them to source control; never edit them by hand.
 *
 * The KSP argument `room.schemaLocation` must point to `$projectDir/schemas` in
 * `app/build.gradle.kts` for the export to land in the correct directory:
 * ```kotlin
 * ksp {
 *     arg("room.schemaLocation", "$projectDir/schemas")
 * }
 * ```
 */
// ─── Migration 1 → 2 (SP-36: commitment_state column) ────────────────────────
//
// Adds the `commitment_state` TEXT column to the `commitments` table.
// The DEFAULT 'DRAFT' ensures existing rows are placed in the DRAFT state,
// which is the correct starting point for the SP-36 state machine.
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Idempotency: SQLite's ALTER TABLE ADD COLUMN has no IF NOT EXISTS clause.
        // If a prior migration attempt added the column before crashing, replaying
        // this statement would throw "duplicate column name". Swallowing the error
        // here makes the migration safely re-runnable.
        try {
            db.execSQL(
                "ALTER TABLE commitments ADD COLUMN commitment_state TEXT NOT NULL DEFAULT 'DRAFT'"
            )
        } catch (e: android.database.sqlite.SQLiteException) {
            // Column already exists — migration is idempotent, continue.
        }
    }
}

// ─── Migration 2 → 3 (R2-02/04/05: drop out-of-spec indices) ────────────────
//
// Removes three indices not declared in `.spec/contracts/data-model.yml`:
//   1. idx_raw_events_user_client_event — dedup enforced at Supabase/Railway layer
//   2. index_calendar_events_user_id_source_type_source_ref — spec only has (user_id, start_at)
//   3. idx_persons_enrichment_person_ref — redundant with PK auto-index
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS `idx_raw_events_user_client_event`")
        db.execSQL("DROP INDEX IF EXISTS `index_calendar_events_user_id_source_type_source_ref`")
        db.execSQL("DROP INDEX IF EXISTS `idx_persons_enrichment_person_ref`")
    }
}

// ─── Migration 3 → 4 (commitments.due_date → due_at + due_hint + due_is_approximate) ──
//
// Expands the single `commitments.due_date TEXT` column into three columns, per
// `.spec/contracts/data-model.yml:132-144` and the explicit DDL instruction at
// `.spec/contracts/data-model.yml:471`. Also recreates the two `due_at`-indexed indices
// (idx_commitments_user_action_due, idx_commitments_user_person_due) so that
// CommitmentManagementScreen / PersonDetailScreen queries still have matching coverage.
//
// Why table rebuild (not ALTER TABLE ... DROP COLUMN)?
//   `DROP COLUMN` requires SQLite >= 3.35 (Android API 30+). App `minSdk = 28` targets
//   Android 9 / SQLite 3.19, which does not support it. We therefore follow the SQLite
//   "12-step" table-rebuild pattern: create a shadow table in the v4 shape, copy rows
//   with the one conversion, drop the legacy table, rename, and recreate indices. This
//   is the exact approach the data-model.yml:471 note anticipates ("SQLite < 3.35 on API
//   28 requires table rebuild").
//
// due_at backfill: `due_date` is a TEXT ISO-8601 date (yyyy-MM-dd) stored by the pre-v4
// LocalDate converter. We interpret it as Asia/Seoul midnight and convert to UTC epoch
// milliseconds by subtracting the +09:00 offset (`'-9 hours'` modifier in strftime).
// This is the "least wrong" assumption per plan Section 7 — existing rows lost the
// time component at extraction time, so KST 00:00 is the closest canonical point.
private val MIGRATION_3_4 = object : Migration(3, 4) {
    // Single source of truth for the v4 `commitments` column list so the CREATE and the
    // INSERT column list cannot drift. Names and order must match CommitmentEntity @v4 so
    // MigrationTestHelper.runMigrationsAndValidate accepts the rebuilt table.
    private val V4_COLUMN_LIST_SQL = listOf(
        "`id`", "`user_id`", "`direction`", "`counterparty_raw`", "`person_ref`",
        "`title`", "`description`", "`quote`", "`source_event_title`", "`source_event_occurred_at`",
        "`due_at`", "`due_hint`", "`due_is_approximate`",
        "`action_state`", "`source_type`", "`source_ref`", "`confidence`",
        "`commitment_state`", "`sync_status`", "`created_at`", "`updated_at`",
    ).joinToString(", ")

    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Create the v4-shape shadow table. Column list, types, NOT NULL, and
        //    DEFAULTs must match what Room generates for CommitmentEntity at v4.
        db.execSQL(
            """
            CREATE TABLE `commitments_new` (
                `id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `direction` TEXT NOT NULL,
                `counterparty_raw` TEXT,
                `person_ref` TEXT,
                `title` TEXT NOT NULL,
                `description` TEXT,
                `quote` TEXT NOT NULL,
                `source_event_title` TEXT,
                `source_event_occurred_at` INTEGER NOT NULL,
                `due_at` INTEGER,
                `due_hint` TEXT,
                `due_is_approximate` INTEGER NOT NULL DEFAULT 0,
                `action_state` TEXT NOT NULL DEFAULT 'pending',
                `source_type` TEXT NOT NULL,
                `source_ref` TEXT,
                `confidence` REAL NOT NULL DEFAULT 0.0,
                `commitment_state` TEXT NOT NULL DEFAULT 'DRAFT',
                `sync_status` TEXT NOT NULL DEFAULT 'pending',
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )

        // 2. Copy rows, converting due_date (TEXT yyyy-MM-dd) → due_at (INTEGER epoch ms
        //    at Asia/Seoul midnight, expressed as UTC). due_hint defaults to NULL (no
        //    hint is recoverable from legacy rows); due_is_approximate defaults to 0
        //    (existing due_date values were treated as exact).
        //    strftime('%s', ...) returns UTC epoch seconds — multiplying by 1000 yields
        //    milliseconds, matching the kotlinx.datetime.Instant Room converter.
        //
        //    The SELECT list mirrors [V4_COLUMN_LIST_SQL] exactly with three columns
        //    substituted: `due_at` is the CASE-computed backfill, `due_hint` is NULL, and
        //    `due_is_approximate` is 0.
        db.execSQL(
            """
            INSERT INTO `commitments_new` ($V4_COLUMN_LIST_SQL)
            SELECT
                `id`, `user_id`, `direction`, `counterparty_raw`, `person_ref`,
                `title`, `description`, `quote`, `source_event_title`, `source_event_occurred_at`,
                CASE
                    WHEN `due_date` IS NOT NULL
                        THEN CAST(strftime('%s', `due_date` || ' 00:00:00', '-9 hours') AS INTEGER) * 1000
                    ELSE NULL
                END AS `due_at`,
                NULL AS `due_hint`,
                0 AS `due_is_approximate`,
                `action_state`, `source_type`, `source_ref`, `confidence`,
                `commitment_state`, `sync_status`, `created_at`, `updated_at`
            FROM `commitments`
            """.trimIndent(),
        )

        // 3. Drop the legacy table. The old indices go with it; we recreate the two
        //    spec-mandated ones below against `due_at`.
        db.execSQL("DROP TABLE `commitments`")

        // 4. Rename the shadow table into place.
        db.execSQL("ALTER TABLE `commitments_new` RENAME TO `commitments`")

        // 5. Recreate spec-mandated indices against the new `due_at` column.
        //    Names match CommitmentEntity @Index declarations so that
        //    MigrationTestHelper's schema comparison passes. The (user_id, sync_status)
        //    index is Room-auto-named `index_commitments_user_id_sync_status` and must
        //    also be recreated so that SyncWorker's pending-sync batch reads remain fast.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_commitments_user_action_due` " +
                "ON `commitments` (`user_id`, `action_state`, `due_at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_commitments_user_person_due` " +
                "ON `commitments` (`user_id`, `person_ref`, `due_at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_commitments_user_id_sync_status` " +
                "ON `commitments` (`user_id`, `sync_status`)",
        )
    }
}

public val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
