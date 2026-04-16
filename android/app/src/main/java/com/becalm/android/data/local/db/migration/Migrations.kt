package com.becalm.android.data.local.db.migration

import androidx.room.migration.Migration

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
public val MIGRATIONS: Array<Migration> = emptyArray()

// ─── Example migration (keep as a reference template) ────────────────────────
//
// When version 1 → 2 requires adding a new column, add an entry like:
//
// private val MIGRATION_1_2 = Migration(1, 2) { db ->
//     db.execSQL(
//         "ALTER TABLE raw_ingestion_events ADD COLUMN new_column TEXT"
//     )
// }
//
// Then update the array:
// public val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
