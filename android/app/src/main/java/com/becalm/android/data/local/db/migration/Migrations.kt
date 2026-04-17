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
        db.execSQL(
            "ALTER TABLE commitments ADD COLUMN commitment_state TEXT NOT NULL DEFAULT 'DRAFT'"
        )
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

public val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
