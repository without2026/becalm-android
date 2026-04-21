package com.becalm.android.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.becalm.android.data.local.db.dao.CalendarEventDao
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.EmailBodyDao
import com.becalm.android.data.local.db.dao.PersonEnrichmentDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.migration.MIGRATIONS

/**
 * The single Room database for the BeCalm Android application.
 *
 * ## Entities
 * | Entity                      | Table name            | Owner SP  | Room-only |
 * |-----------------------------|-----------------------|-----------|-----------|
 * | [RawIngestionEventEntity]   | raw_ingestion_events  | SP-09     | no        |
 * | [CommitmentEntity]          | commitments           | SP-10     | no        |
 * | [CalendarEventEntity]       | calendar_events       | SP-11     | no        |
 * | [PersonEnrichmentEntity]    | persons_enrichment    | SP-12     | no        |
 * | [EmailBodyEntity]           | email_body            | SP-TBD    | yes       |
 *
 * ## Schema version history
 * - v5: commitments gains `last_edited_by`, `last_edited_at`, `quote_disputed`,
 *   `quote_disputed_at`, `deleted_at`, `supersedes_commitment_id` plus two indices
 *   (`idx_commitments_user_deleted`, `idx_commitments_supersedes`). Every SELECT in
 *   [com.becalm.android.data.local.db.dao.CommitmentDao] now filters `deleted_at IS NULL`
 *   per `.spec/contracts/data-model.yml:204-205`. Enables EDIT-001..008 and MAN-001..006
 *   Stage-5 UI flows.
 * - v6: introduces the `email_body` room-only table (14 columns, 2 indices, FK to
 *   `raw_ingestion_events` with `ON DELETE CASCADE`) and adds `raw_ingestion_events.folder`
 *   (nullable TEXT) as the EMAIL-001 direction hint. Enables EMAIL-001..007
 *   (`.spec/email-pipeline.spec.yml`) and ING-006..008. The new table is PIPA room-only
 *   per EMAIL-006 — body_plain / body_html / attachments_meta / raw_headers MUST NEVER
 *   leave the device. Spec refs: `.spec/contracts/data-model.yml:327-390`,
 *   `.spec/email-pipeline.spec.yml:15-18,58-64`.
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
// `version` is an inline literal because KSP2 (and indirectly KSP1 via Room's annotation
// proxy) cannot resolve a companion-object `const val` reference at this annotation site —
// see google/ksp#2439, #1909, #839. Keep [DATABASE_VERSION] for non-annotation use sites
// (migrations, build-time assertions); it must stay in sync with the literal below.
@Database(
    entities = [
        RawIngestionEventEntity::class,
        CommitmentEntity::class,
        CalendarEventEntity::class,
        PersonEnrichmentEntity::class,
        EmailBodyEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
public abstract class BeCalmDatabase : RoomDatabase() {
    init {
        // Fail-loudly guard: the @Database(version = …) literal above must stay in lockstep
        // with [DATABASE_VERSION] below. KSP2 cannot resolve the const reference at the
        // annotation site (ksp#2439), so both sites must be bumped together on every schema
        // migration. Plan: docs/plans/db-commitment-due-at-hint-approximate.md §Migration Impact.
        require(DATABASE_VERSION == 6) {
            "DATABASE_VERSION ($DATABASE_VERSION) drifted from @Database(version = 6) literal"
        }
    }

    /** Returns the DAO for the `raw_ingestion_events` table (SP-09). */
    public abstract fun rawIngestionEventDao(): RawIngestionEventDao

    /** Returns the DAO for the `commitments` table (SP-10). */
    public abstract fun commitmentDao(): CommitmentDao

    /** Returns the DAO for the `calendar_events` table (SP-11). */
    public abstract fun calendarEventDao(): CalendarEventDao

    /** Returns the DAO for the `persons_enrichment` table (SP-12). */
    public abstract fun personEnrichmentDao(): PersonEnrichmentDao

    /**
     * Returns the DAO for the `email_body` room-only table (v6+).
     *
     * The referenced rows MUST stay on-device (EMAIL-006). Any code path that
     * serializes an [EmailBodyEntity] into a network DTO is a production-blocking
     * privacy regression — see `.spec/email-pipeline.spec.yml:58-64`.
     *
     * `internal` visibility because no production consumer exists yet — the schema
     * and DAO land in Wave 1 so future ADAPT-EMAIL-* workers (`GmailWorker`,
     * `OutlookMailWorker`, IMAP workers, `RetentionSweepWorker`) can wire against a
     * stable shape. Making this public now would breach DEADCODE-02; the accessor is
     * promoted to `public` in the PR that adds the first real caller.
     */
    internal abstract fun emailBodyDao(): EmailBodyDao

    public companion object {

        /** File name of the SQLite database on disk, located in the app's internal data directory. */
        public const val DATABASE_NAME: String = "becalm.db"

        /**
         * Current schema version. Increment this integer whenever the schema changes and add
         * a corresponding [androidx.room.migration.Migration] to [MIGRATIONS].
         */
        public const val DATABASE_VERSION: Int = 6

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
