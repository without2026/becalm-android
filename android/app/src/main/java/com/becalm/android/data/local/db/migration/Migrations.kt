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
// Stage-5 flows EDIT-001..008, including supersede corrections. The SQL mirrors
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

// ─── Migration 9 → 10 (local person identity / interaction index) ───────────
//
// Adds local-only index tables that group interactions by deterministic person_id.
// These tables are rebuilt by PersonInteractionIndexWorker from source rows, so they
// intentionally do not alter the Supabase mirror schema.
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `person_identities` (
                `id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `person_id` TEXT NOT NULL,
                `identity_key` TEXT NOT NULL,
                `identity_type` TEXT NOT NULL,
                `raw_value` TEXT NOT NULL,
                `display_name_hint` TEXT,
                `source_type` TEXT NOT NULL,
                `confidence` REAL NOT NULL,
                `verified` INTEGER NOT NULL,
                `last_seen_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `ux_person_identities_user_identity_key` " +
                "ON `person_identities` (`user_id`, `identity_key`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_person_identities_user_person` " +
                "ON `person_identities` (`user_id`, `person_id`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `person_interactions` (
                `id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `person_id` TEXT NOT NULL,
                `source_type` TEXT NOT NULL,
                `source_ref` TEXT NOT NULL,
                `interaction_kind` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `direction` TEXT,
                `status` TEXT,
                `occurred_at` INTEGER NOT NULL,
                `title` TEXT,
                `snippet` TEXT,
                `confidence` REAL NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `ux_person_interactions_user_source_person` " +
                "ON `person_interactions` (`user_id`, `source_type`, `source_ref`, `person_id`, `interaction_kind`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_person_interactions_user_person_time` " +
                "ON `person_interactions` (`user_id`, `person_id`, `occurred_at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_person_interactions_user_time` " +
                "ON `person_interactions` (`user_id`, `occurred_at`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `source_person_candidates` (
                `id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `source_type` TEXT NOT NULL,
                `source_ref` TEXT NOT NULL,
                `candidate_ref` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `name` TEXT,
                `email` TEXT,
                `phone` TEXT,
                `organization` TEXT,
                `evidence` TEXT,
                `confidence` REAL NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `ux_source_person_candidates_user_source_candidate` " +
                "ON `source_person_candidates` (`user_id`, `source_type`, `source_ref`, `candidate_ref`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_source_person_candidates_user_source` " +
                "ON `source_person_candidates` (`user_id`, `source_type`, `source_ref`)",
        )
    }
}

// ─── Migration 10 → 11 (manual person matching / alias learning) ─────────────
//
// Adds local-only repair tables for unresolved source rows. These tables preserve unmatched
// interactions for UI repair and let user-confirmed nicknames resolve future rows.
private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `unmatched_person_interactions` (
                `id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `source_type` TEXT NOT NULL,
                `source_ref` TEXT NOT NULL,
                `interaction_kind` TEXT NOT NULL,
                `title` TEXT,
                `snippet` TEXT,
                `suggested_label` TEXT,
                `occurred_at` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `ux_unmatched_person_interactions_user_source` " +
                "ON `unmatched_person_interactions` (`user_id`, `source_type`, `source_ref`, `interaction_kind`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_unmatched_person_interactions_user_time` " +
                "ON `unmatched_person_interactions` (`user_id`, `occurred_at`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `person_manual_matches` (
                `id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `source_type` TEXT NOT NULL,
                `source_ref` TEXT NOT NULL,
                `interaction_kind` TEXT NOT NULL,
                `matched_person_id` TEXT NOT NULL,
                `matched_identity_key` TEXT NOT NULL,
                `nickname` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `ux_person_manual_matches_user_source` " +
                "ON `person_manual_matches` (`user_id`, `source_type`, `source_ref`, `interaction_kind`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_person_manual_matches_user_person` " +
                "ON `person_manual_matches` (`user_id`, `matched_person_id`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `person_alias_rules` (
                `id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `alias` TEXT NOT NULL,
                `normalized_alias` TEXT NOT NULL,
                `person_id` TEXT NOT NULL,
                `identity_key` TEXT NOT NULL,
                `source_scope` TEXT,
                `enabled` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `ux_person_alias_rules_user_alias_scope` " +
                "ON `person_alias_rules` (`user_id`, `normalized_alias`, `source_scope`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_person_alias_rules_user_person` " +
                "ON `person_alias_rules` (`user_id`, `person_id`)",
        )
    }
}

// ─── Migration 11 → 12 (incremental person index state) ─────────────────────
//
// Tracks the source fingerprint processed by PersonInteractionIndexWorker so unchanged
// source rows do not force a full person-interaction table rebuild on every sync.
private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `person_index_source_state` (
                `id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `source_type` TEXT NOT NULL,
                `source_ref` TEXT NOT NULL,
                `interaction_kind` TEXT NOT NULL,
                `fingerprint` TEXT NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `ux_person_index_source_state_user_source` " +
                "ON `person_index_source_state` (`user_id`, `source_type`, `source_ref`, `interaction_kind`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_person_index_source_state_user_updated` " +
                "ON `person_index_source_state` (`user_id`, `updated_at`)",
        )
    }
}

// ─── Migration 12 → 13 (local source archive metadata) ───────────────────────
//
// Adds the Room-only `source_artifacts` table. The original Markdown files live
// under app-private files storage; this table stores only a relative path and
// integrity metadata. No Supabase/Railway migration accompanies this local table.
private val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `source_artifacts` (
                `id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `raw_event_id` TEXT,
                `source_type` TEXT NOT NULL,
                `source_ref` TEXT,
                `artifact_type` TEXT NOT NULL,
                `local_path` TEXT NOT NULL,
                `sha256` TEXT NOT NULL,
                `byte_size` INTEGER NOT NULL,
                `occurred_at` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `ux_source_artifacts_user_source_type` " +
                "ON `source_artifacts` (`user_id`, `source_type`, `source_ref`, `artifact_type`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_source_artifacts_user_occurred` " +
                "ON `source_artifacts` (`user_id`, `occurred_at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_source_artifacts_raw_event` " +
                "ON `source_artifacts` (`raw_event_id`)",
        )
    }
}

// ─── Migration 13 → 14 (backend relation-intelligence mirror) ───────────────
//
// Aligns Room's canonical person/relation tables with the backend relation
// schema. Local-only person repair/projection columns remain in place, but Android
// now has first-class mirrors for persons, source_event_participants, and
// commitment_participants instead of relying on source_person_candidates/person_ref
// as the only person wiring surface.
private val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `persons` (
                `id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `display_name` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `primary_email` TEXT,
                `primary_phone` TEXT,
                `confidence` REAL NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `archived_at` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_persons_user_updated` " +
                "ON `persons` (`user_id`, `updated_at`)",
        )

        addColumnIfMissing(db, "person_identities", "identity_value", "TEXT NOT NULL DEFAULT ''")
        addColumnIfMissing(db, "person_identities", "normalized_value", "TEXT NOT NULL DEFAULT ''")
        addColumnIfMissing(db, "person_identities", "display_name", "TEXT")
        addColumnIfMissing(db, "person_identities", "source_ref", "TEXT")
        addColumnIfMissing(db, "person_identities", "is_primary", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, "person_identities", "created_at", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, "person_identities", "updated_at", "INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            """
            UPDATE `person_identities`
            SET
                identity_value = CASE WHEN identity_value = '' THEN raw_value ELSE identity_value END,
                normalized_value = CASE
                    WHEN normalized_value != '' THEN normalized_value
                    WHEN instr(identity_key, ':') > 0 THEN substr(identity_key, instr(identity_key, ':') + 1)
                    ELSE raw_value
                END,
                display_name = COALESCE(display_name, display_name_hint),
                is_primary = CASE WHEN verified != 0 THEN 1 ELSE is_primary END,
                created_at = CASE WHEN created_at = 0 THEN last_seen_at ELSE created_at END,
                updated_at = CASE WHEN updated_at = 0 THEN last_seen_at ELSE updated_at END
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `ux_person_identities_user_identity` " +
                "ON `person_identities` (`user_id`, `identity_type`, `normalized_value`)",
        )

        db.execSQL(
            """
            INSERT OR IGNORE INTO `persons` (
                `id`, `user_id`, `display_name`, `kind`, `primary_email`, `primary_phone`,
                `confidence`, `created_at`, `updated_at`, `archived_at`
            )
            SELECT
                person_id,
                user_id,
                COALESCE(MAX(display_name_hint), MAX(raw_value), person_id),
                'person',
                MAX(CASE WHEN identity_type = 'email' THEN normalized_value ELSE NULL END),
                MAX(CASE WHEN identity_type = 'phone' THEN normalized_value ELSE NULL END),
                MAX(confidence),
                MIN(created_at),
                MAX(updated_at),
                NULL
            FROM `person_identities`
            GROUP BY user_id, person_id
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `source_event_participants` (
                `id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `source_event_id` TEXT NOT NULL,
                `source_type` TEXT NOT NULL,
                `source_ref` TEXT,
                `person_id` TEXT,
                `role` TEXT NOT NULL,
                `relation_to_user` TEXT NOT NULL,
                `identity_type` TEXT,
                `normalized_value` TEXT,
                `display_name_raw` TEXT,
                `email_raw` TEXT,
                `phone_raw` TEXT,
                `organization_raw` TEXT,
                `evidence` TEXT,
                `confidence` REAL NOT NULL,
                `resolution_status` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_source_event_participants_user_event` " +
                "ON `source_event_participants` (`user_id`, `source_event_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_source_event_participants_user_person` " +
                "ON `source_event_participants` (`user_id`, `person_id`, `created_at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_source_event_participants_unresolved` " +
                "ON `source_event_participants` (`user_id`, `resolution_status`, `created_at`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `commitment_participants` (
                `id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `commitment_id` TEXT NOT NULL,
                `person_id` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `evidence` TEXT,
                `confidence` REAL NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `ux_commitment_participants_user_commitment_person_role` " +
                "ON `commitment_participants` (`user_id`, `commitment_id`, `person_id`, `role`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_commitment_participants_user_person` " +
                "ON `commitment_participants` (`user_id`, `person_id`, `created_at`)",
        )

        addColumnIfMissing(db, "person_interactions", "source_event_id", "TEXT")
        addColumnIfMissing(db, "person_interactions", "commitment_id", "TEXT")
        addColumnIfMissing(db, "person_interactions", "interaction_key", "TEXT NOT NULL DEFAULT ''")
        addColumnIfMissing(db, "person_interactions", "interaction_type", "TEXT NOT NULL DEFAULT ''")
        addColumnIfMissing(db, "person_interactions", "created_at", "INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            """
            UPDATE `person_interactions`
            SET
                source_event_id = CASE
                    WHEN source_event_id IS NULL AND source_ref LIKE 'raw:%' THEN substr(source_ref, 5)
                    ELSE source_event_id
                END,
                commitment_id = CASE
                    WHEN commitment_id IS NULL AND source_ref LIKE 'commitment:%' THEN substr(source_ref, 12)
                    ELSE commitment_id
                END,
                interaction_type = CASE WHEN interaction_type = '' THEN interaction_kind ELSE interaction_type END,
                interaction_key = CASE
                    WHEN interaction_key = '' THEN user_id || ':' || person_id || ':' ||
                        COALESCE(source_event_id, source_ref) || ':' || COALESCE(commitment_id, '') || ':' || interaction_kind
                    ELSE interaction_key
                END,
                created_at = CASE WHEN created_at = 0 THEN occurred_at ELSE created_at END
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `ux_person_interactions_user_key` " +
                "ON `person_interactions` (`user_id`, `interaction_key`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_person_interactions_user_person_occurred` " +
                "ON `person_interactions` (`user_id`, `person_id`, `occurred_at`)",
        )
    }
}

// ─── Migration 14 → 15 (counterparty_ref naming contract) ────────────────────
//
// raw_ingestion_events / commitments used to store the source counterparty seed in
// `person_ref`, which conflicted with the relation-intelligence contract where UI
// person identity is `person_id`. v15 renames those durable local columns to
// `counterparty_ref`. The contacts enrichment cache keeps `persons_enrichment.person_ref`
// because it is a local contacts lookup anchor, not the relation UI key.
private val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS `idx_raw_events_user_person_time`")
        db.execSQL("DROP INDEX IF EXISTS `idx_commitments_user_person_due`")

        db.execSQL("ALTER TABLE `raw_ingestion_events` RENAME COLUMN `person_ref` TO `counterparty_ref`")
        db.execSQL("ALTER TABLE `commitments` RENAME COLUMN `person_ref` TO `counterparty_ref`")

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_raw_events_user_counterparty_time` " +
                "ON `raw_ingestion_events` (`user_id`, `counterparty_ref`, `timestamp`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_commitments_user_counterparty_due` " +
                "ON `commitments` (`user_id`, `counterparty_ref`, `due_at`)",
        )
    }
}

// ─── Migration 15 → 16 (remove source_person_candidates legacy cache) ───────
//
// Person matching and PersonWorker indexing now read source_event_participants directly.
// The former source_person_candidates cache duplicated the same data for review UI and
// could drift from the canonical participant table, so v16 drops it.
private val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `source_person_candidates`")
    }
}

// ─── Migration 16 → 17 (incremental person-index dirty queue) ───────────────
//
// PersonInteractionIndexWorker no longer needs to rescan every source participant row on
// each refresh. Source graph write paths enqueue changed source keys here; the worker drains
// this Room-only queue and rebuilds only those projection slices.
private val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `person_manual_matches`")
        db.execSQL("DROP TABLE IF EXISTS `person_alias_rules`")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `person_index_dirty_sources` (
                `id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `source_type` TEXT NOT NULL,
                `source_ref` TEXT NOT NULL,
                `interaction_kind` TEXT NOT NULL,
                `reason` TEXT,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `ux_person_index_dirty_sources_user_source` " +
                "ON `person_index_dirty_sources` (`user_id`, `source_type`, `source_ref`, `interaction_kind`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_person_index_dirty_sources_user_updated` " +
                "ON `person_index_dirty_sources` (`user_id`, `updated_at`)",
        )
    }
}

// ─── Migration 17 → 18 (remove person_index_source_state cache) ─────────────
//
// PersonInteractionIndexWorker now treats person_interactions as a rebuildable graph
// projection. Deduplication belongs to canonical source ids and the dirty queue; no
// durable fingerprint cache is maintained.
private val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `person_index_source_state`")
    }
}

// ─── Migration 18 → 19 (source participant profile title evidence) ──────────
//
// Person memory/profile enrichment stores only source-backed facts. `title_raw`
// captures an explicitly stated job title from the same canonical participant row as
// organization/name/email so downstream memory generation never has to infer roles from
// work context or event titles.
private val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addColumnIfMissing(db, "source_event_participants", "title_raw", "TEXT")
    }
}

// ─── Migration 19 → 20 (person memory semantic matching index) ──────────────
//
// Adds a Room-only bounded semantic term projection for unresolved participant
// recommendation. Matching reads this table instead of parsing `memory.md` at runtime.
private val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `person_memory_semantic_index` (
                `person_id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `display_name_terms_json` TEXT NOT NULL,
                `aliases_json` TEXT NOT NULL,
                `organizations_json` TEXT NOT NULL,
                `titles_json` TEXT NOT NULL,
                `work_terms_json` TEXT NOT NULL,
                `decision_terms_json` TEXT NOT NULL,
                `open_commitment_terms_json` TEXT NOT NULL,
                `confirmed_patterns_json` TEXT NOT NULL,
                `rejected_patterns_json` TEXT NOT NULL,
                `recent_source_types_json` TEXT NOT NULL,
                `content_hash` TEXT NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`person_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_person_memory_semantic_index_user_updated` " +
                "ON `person_memory_semantic_index` (`user_id`, `updated_at`)",
        )
    }
}

private fun addColumnIfMissing(
    db: SupportSQLiteDatabase,
    tableName: String,
    columnName: String,
    definition: String,
) {
    try {
        db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $definition")
    } catch (e: android.database.sqlite.SQLiteException) {
        if (!e.message.orEmpty().contains("duplicate column", ignoreCase = true)) throw e
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
    MIGRATION_9_10,
    MIGRATION_10_11,
    MIGRATION_11_12,
    MIGRATION_12_13,
    MIGRATION_13_14,
    MIGRATION_14_15,
    MIGRATION_15_16,
    MIGRATION_16_17,
    MIGRATION_17_18,
    MIGRATION_18_19,
    MIGRATION_19_20,
)
