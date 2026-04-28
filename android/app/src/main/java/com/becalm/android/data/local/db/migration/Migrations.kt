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

// ─── Migration 4 → 5 (commitments: edit / dispute / soft-delete / supersede) ──
//
// Adds the six user-facing lifecycle columns from `.spec/contracts/data-model.yml:188-210`
// plus the two supporting indices from `.spec/contracts/data-model.yml:219-225`. Unlocks
// Stage-5 flows EDIT-001..008 and MAN-001..006. The SQL mirrors
// `.spec/contracts/data-model.yml:484` 1:1 so Android (Room) ↔ Postgres (Supabase) drift
// is trivially diff-checkable.
//
// Why ALTER TABLE instead of a 3→4-style table rebuild: every delta is additive, so SQLite
// executes it in place. The self-referential `supersedes_commitment_id` column is declared
// as plain TEXT without a `REFERENCES` clause — Room warns on circular FKs and the real
// enforcement lives at the Postgres layer (`ON DELETE SET NULL`); the future EDIT-007
// writer is responsible for keeping the link consistent.
//
// Index rationale:
//   * idx_commitments_user_deleted — every per-user SELECT in CommitmentDao now appends
//     `AND deleted_at IS NULL` (data-model.yml:204-205 MUST-invariant). Covering
//     `(user_id, deleted_at)` keeps the live-rows scan index-only on large histories.
//   * idx_commitments_supersedes — backs the EDIT-007 "what rows supersede X?" audit render.
//     The column is intentionally sparse (most rows null), so the index stays cheap.
//
// Idempotency: ALTER TABLE ADD COLUMN is NOT re-runnable in SQLite. Matching MIGRATION_3_4's
// fail-loud posture, we do not wrap in try/catch — a double-application in tests should
// surface the bug rather than hide it.
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Edit-tracking columns (EDIT-003 banner, MAN-006 audit trail).
        db.execSQL("ALTER TABLE `commitments` ADD COLUMN `last_edited_by` TEXT")
        db.execSQL("ALTER TABLE `commitments` ADD COLUMN `last_edited_at` INTEGER")

        // 2. Dispute columns (EDIT-005 "this quote is wrong" flow). NOT NULL DEFAULT 0 so
        //    every backfilled row is "not disputed" — matches data-model.yml:197's
        //    nullable:false + default:"false" contract.
        db.execSQL(
            "ALTER TABLE `commitments` ADD COLUMN `quote_disputed` INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL("ALTER TABLE `commitments` ADD COLUMN `quote_disputed_at` INTEGER")

        // 3. Soft-delete marker (EDIT-006). Nullable — null means live. Every read path
        //    in CommitmentDao filters `AND deleted_at IS NULL`.
        db.execSQL("ALTER TABLE `commitments` ADD COLUMN `deleted_at` INTEGER")

        // 4. Supersede lineage (EDIT-007). Self-referential but declared as plain TEXT —
        //    no inline `REFERENCES` to avoid Room's circular-FK warning; enforcement is
        //    Postgres-side (`ON DELETE SET NULL`) per data-model.yml:209.
        db.execSQL("ALTER TABLE `commitments` ADD COLUMN `supersedes_commitment_id` TEXT")

        // 5. Supporting indices (data-model.yml:219-225). Names match CommitmentEntity's
        //    @Index(name = ...) declarations so MigrationTestHelper accepts them.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_commitments_user_deleted` " +
                "ON `commitments` (`user_id`, `deleted_at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_commitments_supersedes` " +
                "ON `commitments` (`supersedes_commitment_id`)",
        )
    }
}

// ─── Migration 5 → 6 (email_body table + raw_ingestion_events.folder) ───────
//
// Introduces the `email_body` room-only table (14 columns, 2 indices, FK CASCADE to
// raw_ingestion_events) and adds the `folder` TEXT column to `raw_ingestion_events`.
// Unlocks EMAIL-001..007 (`.spec/email-pipeline.spec.yml`) and ING-006..008. Mirrors
// `.spec/contracts/data-model.yml:327-390 § email_body` 1:1.
//
    // `email_body` is **room_only: true** per EMAIL-006 — no Supabase migration accompanies
    // this PR because the table does not exist upstream. body_plain may be sent later as
    // transient Vertex Gemini extraction context, but the full `email_body` row is not
    // mirrored into Railway/Supabase.
//
// Column list (14 — order matches EmailBodyEntity field order so reviewers can
// diff side-by-side. Any drift here causes MigrationTestHelper.runMigrationsAndValidate
// to reject with a schema mismatch at test time — intended fail-loudly behaviour):
//   1. id                    TEXT PK                      (UUID v4)
//   2. raw_event_id          TEXT NOT NULL                (FK → raw_ingestion_events.id)
//   3. provider_message_id   TEXT NOT NULL                (Gmail msgId / Graph id / IMAP UIDVALIDITY+UID)
//   4. folder                TEXT NOT NULL                (INBOX | SENT)
//   5. subject               TEXT
//   6. from_address          TEXT                         (lowercase normalized)
//   7. to_addresses          TEXT                         (JSON array, Moshi-serialized)
//   8. body_plain            TEXT                         (200-char snippet per EMAIL-003)
//   9. body_html             TEXT
//  10. attachments_meta      TEXT                         (JSON array, EMAIL-004)
//  11. raw_headers           TEXT                         (EMAIL-005 — In-Reply-To, References)
//  12. parse_failed          INTEGER NOT NULL DEFAULT 0   (EMAIL-007)
//  13. group_email           INTEGER NOT NULL DEFAULT 0   (>10 recipients)
//  14. received_at           INTEGER NOT NULL             (epoch ms via Instant converter)
//
// FK CASCADE rationale: `ON DELETE CASCADE` on `raw_event_id` provides a structural
// co-delete aligned with EMAIL-006 (`.spec/email-pipeline.spec.yml:58-64`):
// > "EmailBody와 raw_ingestion_events를 함께 DELETE"
// The authoritative retention driver is the timestamp-based RetentionSweepWorker
// (future `feat/worker/retention`), but CASCADE also protects against orphans left
// behind by sign-out purge or ad-hoc deletes.
//
// Idempotency: `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` make table
// and index creation re-runnable. `ALTER TABLE ... ADD COLUMN` is NOT idempotent in
// SQLite — we allow this migration to fail loudly on a double-run rather than
// swallowing the duplicate-column SQLiteException (matches MIGRATION_3_4 / MIGRATION_4_5).
//
// Index naming: both indices use Room's default template `index_<table>_<column>`.
// Matching Room's KSP-emitted names in `6.json` is critical — MigrationTestHelper
// compares this SQL against the snapshot and rejects any name divergence as drift.
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Create the `email_body` table with a CASCADE FK back to raw_ingestion_events.
        //    Column list order + affinity + NOT NULL match [EmailBodyEntity] exactly;
        //    Room's schema validator rejects startup otherwise.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `email_body` (
                `id` TEXT NOT NULL,
                `raw_event_id` TEXT NOT NULL,
                `provider_message_id` TEXT NOT NULL,
                `folder` TEXT NOT NULL,
                `subject` TEXT,
                `from_address` TEXT,
                `to_addresses` TEXT,
                `body_plain` TEXT,
                `body_html` TEXT,
                `attachments_meta` TEXT,
                `raw_headers` TEXT,
                `parse_failed` INTEGER NOT NULL DEFAULT 0,
                `group_email` INTEGER NOT NULL DEFAULT 0,
                `received_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`raw_event_id`) REFERENCES `raw_ingestion_events`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        // 2. Indices use Room's default `index_<table>_<column>` template so KSP's 6.json
        //    snapshot and this hand-written SQL agree — MigrationTestHelper will reject
        //    any other name as schema drift.
        //
        //    `raw_event_id` is UNIQUE: the 1:1 relationship (one body per raw event)
        //    turns [EmailBodyDao.insert] with OnConflictStrategy.REPLACE into a genuine
        //    re-poll-safe upsert. Without UNIQUE, a second insert with a different random
        //    primary-key UUID but the same raw_event_id would silently create a duplicate
        //    row and [EmailBodyDao.getByRawEventId] would return an arbitrary copy.
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_email_body_raw_event_id` " +
                "ON `email_body` (`raw_event_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_email_body_provider_message_id` " +
                "ON `email_body` (`provider_message_id`)",
        )

        // 3. EMAIL-001 direction hint column on raw_ingestion_events. Nullable because
        //    (a) SQLite `ALTER TABLE ADD COLUMN` forbids NOT NULL without a DEFAULT and
        //    the valid values INBOX | SENT have no sensible default for non-email sources,
        //    and (b) only email ingestion owners populate this column. Current Android
        //    local owners are the IMAP workers; backend-managed mail rows may arrive
        //    through mirror/import code. Non-email sources MUST leave null.
        db.execSQL("ALTER TABLE `raw_ingestion_events` ADD COLUMN `folder` TEXT")
    }
}

// ─── Migration 6 → 7 (user_profile bootstrap table) ────────────────────────
//
// Introduces the local `user_profile` table required by cold-sync Stage 1 bootstrap.
// Android owns the initial row creation from auth session + default timezone/locale;
// Railway mirror remains a separate concern.
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `user_profile` (
                `user_id` TEXT NOT NULL,
                `display_name_override` TEXT,
                `phone_e164_self` TEXT,
                `timezone` TEXT NOT NULL DEFAULT 'Asia/Seoul',
                `preferred_locale` TEXT NOT NULL DEFAULT 'ko',
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`user_id`)
            )
            """.trimIndent(),
        )
    }
}

// ─── Migration 7 → 8 (commitments trackable item types) ─────────────────────
//
// Expands `commitments` from action-only rows into a generic trackable-item table by:
//   1. adding `item_type` (default/action backfill),
//   2. adding nullable `schedule_status` / `decision_status`,
//   3. relaxing `direction` from NOT NULL to NULL so non-action rows can persist cleanly,
//   4. widening the primary action index to `(user_id, item_type, action_state, due_at)`.
//
// SQLite cannot alter existing column nullability in place, so we rebuild only the
// `commitments` table. Existing rows are backfilled as `item_type='action'`.
private val MIGRATION_7_8 = object : Migration(7, 8) {
    private val V8_COLUMN_LIST_SQL = listOf(
        "`id`", "`user_id`", "`item_type`", "`direction`", "`schedule_status`", "`decision_status`",
        "`counterparty_raw`", "`person_ref`", "`title`", "`description`", "`quote`",
        "`source_event_title`", "`source_event_occurred_at`", "`due_at`", "`due_hint`",
        "`due_is_approximate`", "`action_state`", "`source_type`", "`source_ref`",
        "`confidence`", "`commitment_state`", "`sync_status`", "`created_at`", "`updated_at`",
        "`last_edited_by`", "`last_edited_at`", "`quote_disputed`", "`quote_disputed_at`",
        "`deleted_at`", "`supersedes_commitment_id`",
    ).joinToString(", ")

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE `commitments_new` (
                `id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `item_type` TEXT NOT NULL DEFAULT 'action',
                `direction` TEXT,
                `schedule_status` TEXT,
                `decision_status` TEXT,
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
                `last_edited_by` TEXT,
                `last_edited_at` INTEGER,
                `quote_disputed` INTEGER NOT NULL DEFAULT 0,
                `quote_disputed_at` INTEGER,
                `deleted_at` INTEGER,
                `supersedes_commitment_id` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            INSERT INTO `commitments_new` ($V8_COLUMN_LIST_SQL)
            SELECT
                `id`, `user_id`, 'action' AS `item_type`, `direction`, NULL AS `schedule_status`,
                NULL AS `decision_status`, `counterparty_raw`, `person_ref`, `title`, `description`,
                `quote`, `source_event_title`, `source_event_occurred_at`, `due_at`, `due_hint`,
                `due_is_approximate`, `action_state`, `source_type`, `source_ref`, `confidence`,
                `commitment_state`, `sync_status`, `created_at`, `updated_at`, `last_edited_by`,
                `last_edited_at`, `quote_disputed`, `quote_disputed_at`, `deleted_at`,
                `supersedes_commitment_id`
            FROM `commitments`
            """.trimIndent(),
        )

        db.execSQL("DROP TABLE `commitments`")
        db.execSQL("ALTER TABLE `commitments_new` RENAME TO `commitments`")

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_commitments_user_action_due` " +
                "ON `commitments` (`user_id`, `item_type`, `action_state`, `due_at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_commitments_user_id_sync_status` " +
                "ON `commitments` (`user_id`, `sync_status`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_commitments_user_person_due` " +
                "ON `commitments` (`user_id`, `person_ref`, `due_at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_commitments_user_deleted` " +
                "ON `commitments` (`user_id`, `deleted_at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_commitments_supersedes` " +
                "ON `commitments` (`supersedes_commitment_id`)",
        )
    }
}

// ─── Migration 8 → 9 (local raw-event idempotency constraint) ─────────────────
//
// The ingestion workers rely on `(user_id, client_event_id)` being a storage-level
// idempotency key. Supabase already has this UNIQUE constraint, but Room v8 only had
// read-before-write guards, so batch inserts and MediaStore overlap scans could persist
// duplicate local raw events. Before creating the unique index, remove exact local
// duplicates and their 1:1 email bodies, keeping the oldest rowid in each duplicate group.
private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            DELETE FROM `email_body`
            WHERE `raw_event_id` IN (
                SELECT `id` FROM `raw_ingestion_events`
                WHERE rowid NOT IN (
                    SELECT MIN(rowid)
                    FROM `raw_ingestion_events`
                    GROUP BY `user_id`, `client_event_id`
                )
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            DELETE FROM `raw_ingestion_events`
            WHERE rowid NOT IN (
                SELECT MIN(rowid)
                FROM `raw_ingestion_events`
                GROUP BY `user_id`, `client_event_id`
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `ux_raw_events_user_client_event` " +
                "ON `raw_ingestion_events` (`user_id`, `client_event_id`)",
        )
    }
}

public val MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
    MIGRATION_8_9,
)
