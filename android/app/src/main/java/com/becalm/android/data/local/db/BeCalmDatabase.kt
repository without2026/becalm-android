package com.becalm.android.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.becalm.android.data.local.db.dao.CalendarEventDao
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.PersonEnrichmentDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.migration.MIGRATIONS

/**
 * The single Room database for the BeCalm Android application.
 *
 * ## Entities
 * | Entity                      | Table name            | Owner SP |
 * |-----------------------------|-----------------------|----------|
 * | [RawIngestionEventEntity]   | raw_ingestion_events  | SP-09    |
 * | [CommitmentEntity]          | commitments           | SP-10    |
 * | [CalendarEventEntity]       | calendar_events       | SP-11    |
 * | [PersonEnrichmentEntity]    | persons_enrichment    | SP-12    |
 *
 * ## Type converters
 * [Converters] is applied at the database level so that every DAO and entity
 * inherits the [kotlinx.datetime.Instant], [kotlinx.datetime.LocalDate], and
 * [kotlinx.datetime.LocalDateTime] mappings without redundant per-class declarations.
 *
 * ## Schema export
 * `exportSchema = true` causes the Room KSP processor to write a versioned JSON snapshot of the
 * schema to `app/schemas/com.becalm.android.data.local.db.BeCalmDatabase/<version>.json` on each
 * build. These snapshots enable [androidx.room.testing.MigrationTestHelper] to verify migration
 * correctness. The required KSP argument must be present in `app/build.gradle.kts`:
 * ```kotlin
 * ksp {
 *     arg("room.schemaLocation", "$projectDir/schemas")
 * }
 * ```
 * Commit the generated JSON files — they serve as the authoritative schema changelog.
 *
 * ## Migrations
 * All [androidx.room.migration.Migration] instances are centralised in [MIGRATIONS].
 * Downgrades fail loudly: Room will throw at open time if the on-disk schema version is greater
 * than [DATABASE_VERSION]. We deliberately do NOT register a destructive-downgrade fallback,
 * because silently wiping the database on downgrade would destroy user-confirmed state (e.g.
 * `commitmentState`) that is not mirrored to Railway. Hot reverts must ship a forward-only
 * migration path or uninstall/reinstall the app.
 *
 * ## Obtaining an instance
 * Use [build] inside a Hilt [com.becalm.android.core.di.DatabaseModule] provider.
 * Never call [build] directly from application code — always inject the database via Hilt.
 */
@Database(
    entities = [
        RawIngestionEventEntity::class,
        CommitmentEntity::class,
        CalendarEventEntity::class,
        PersonEnrichmentEntity::class,
    ],
    version = DATABASE_VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
public abstract class BeCalmDatabase : RoomDatabase() {

    /** Returns the DAO for the `raw_ingestion_events` table (SP-09). */
    public abstract fun rawIngestionEventDao(): RawIngestionEventDao

    /** Returns the DAO for the `commitments` table (SP-10). */
    public abstract fun commitmentDao(): CommitmentDao

    /** Returns the DAO for the `calendar_events` table (SP-11). */
    public abstract fun calendarEventDao(): CalendarEventDao

    /** Returns the DAO for the `persons_enrichment` table (SP-12). */
    public abstract fun personEnrichmentDao(): PersonEnrichmentDao

    public companion object {

        /** File name of the SQLite database on disk, located in the app's internal data directory. */
        public const val DATABASE_NAME: String = "becalm.db"

        /**
         * Current schema version. Increment this integer whenever the schema changes and add
         * a corresponding [androidx.room.migration.Migration] to [MIGRATIONS].
         */
        public const val DATABASE_VERSION: Int = 3

        /**
         * Creates and opens the [BeCalmDatabase] using the standard Room builder.
         *
         * Configuration decisions:
         * - `addMigrations(*MIGRATIONS)` — Room applies the registered migrations in order when
         *   upgrading from an older schema version. See [MIGRATIONS] for the rationale.
         * - No destructive-downgrade fallback is registered. If the installed APK's
         *   [DATABASE_VERSION] is lower than the on-disk schema version, Room will throw at open
         *   time rather than silently dropping user data (e.g. `commitmentState`, which is not
         *   mirrored to Railway). Downgrades must be handled explicitly by a forward migration
         *   or by uninstalling the app.
         *
         * @param context Application context. Pass [android.app.Application] or a
         *   `@ApplicationContext`-qualified [Context] from a Hilt module — never an Activity.
         * @return A fully configured [BeCalmDatabase] ready for DAO access.
         */
        public fun build(context: Context): BeCalmDatabase =
            Room.databaseBuilder(
                context,
                BeCalmDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
