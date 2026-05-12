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
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.dao.SourceArtifactDao
import com.becalm.android.data.local.db.dao.UserProfileDao
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentParticipantEntity
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.PersonEntity
import com.becalm.android.data.local.db.entity.PersonIndexDirtySourceEntity
import com.becalm.android.data.local.db.entity.PersonMemorySemanticIndexEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SourceArtifactEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.local.db.entity.UnmatchedPersonInteractionEntity
import com.becalm.android.data.local.db.entity.UserProfileEntity
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
 * | [UserProfileEntity]         | user_profile          | COLD-001  | no        |
 *
 * ## Schema version history
 * - v5: commitments gains `last_edited_by`, `last_edited_at`, `quote_disputed`,
 *   `quote_disputed_at`, `deleted_at`, `supersedes_commitment_id` plus two indices
 *   (`idx_commitments_user_deleted`, `idx_commitments_supersedes`). Every SELECT in
 *   [com.becalm.android.data.local.db.dao.CommitmentDao] now filters `deleted_at IS NULL`
 *   per `.spec/contracts/data-model.yml:204-205`. Enables EDIT-001..008, including
 *   supersede correction links.
 * - v6: introduces the `email_body` room-only table (14 columns, 2 indices, FK to
 *   `raw_ingestion_events` with `ON DELETE CASCADE`) and adds `raw_ingestion_events.folder`
 *   (nullable TEXT) as the EMAIL-001 direction hint. The table remains the local owner
 *   for IMAP body capture and on-device extraction. Spec refs:
 *   `.spec/contracts/data-model.yml:327-390`, `.spec/email-pipeline.spec.yml:15-18,58-64`.
 * - v7: introduces the `user_profile` local mirror table used by Cold Sync Stage 1
 *   bootstrap. Android creates this row from the authenticated session plus default
 *   timezone / locale before any external `PATCH /v1/user_profile` mirror runs.
 * - v8: expands `commitments` into a persisted trackable-item table by adding
 *   `item_type`, `schedule_status`, and `decision_status`, and by relaxing
 *   `direction` to nullable for non-action rows. Action-only queries now filter
 *   `item_type='action'`; person-detail flows can read `action + schedule + decision`.
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
        PersonEntity::class,
        PersonEnrichmentEntity::class,
        PersonIdentityEntity::class,
        SourceEventParticipantEntity::class,
        CommitmentParticipantEntity::class,
        PersonInteractionEntity::class,
        UnmatchedPersonInteractionEntity::class,
        PersonIndexDirtySourceEntity::class,
        PersonMemorySemanticIndexEntity::class,
        SourceArtifactEntity::class,
        EmailBodyEntity::class,
        UserProfileEntity::class,
    ],
    version = 20,
    exportSchema = true,
)
@TypeConverters(Converters::class)
public abstract class BeCalmDatabase : RoomDatabase() {
    init {
        // Fail-loudly guard: the @Database(version = …) literal above must stay in lockstep
        // with [DATABASE_VERSION] below. KSP2 cannot resolve the const reference at the
        // annotation site (ksp#2439), so both sites must be bumped together on every schema
        // migration. Plan: docs/plans/db-commitment-due-at-hint-approximate.md §Migration Impact.
        require(DATABASE_VERSION == 20) {
            "DATABASE_VERSION ($DATABASE_VERSION) drifted from @Database(version = 20) literal"
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

    /** Returns the DAO for local person identity and interaction indexes. */
    public abstract fun personIndexDao(): PersonIndexDao

    /**
     * Returns the DAO for the `email_body` room-only table (v6+).
     *
     * The referenced rows are not mirrored as a backend table. The upload path may send
     * bounded [EmailBodyEntity.bodyPlain] as transient Vertex Gemini extraction context,
     * but must not persist the full body in Railway/Supabase.
     *
     * `internal` visibility because only local IMAP / extraction paths consume this
     * table today. Keeping the accessor non-public avoids accidental network-mirror
     * style callers while the room-only ownership remains explicit.
     */
    internal abstract fun emailBodyDao(): EmailBodyDao

    /** Returns the DAO for app-private source archive metadata. */
    public abstract fun sourceArtifactDao(): SourceArtifactDao

    /** Returns the DAO for the `user_profile` local bootstrap table (v7+). */
    public abstract fun userProfileDao(): UserProfileDao

    public companion object {

        /**
         * Pre-user-scoping (pre-S6-A) single-file database name.
         *
         * Retained as a `const` only so that [BeCalmDatabaseProvider] can call
         * `context.deleteDatabase(LEGACY_DATABASE_NAME)` exactly once when it first opens a
         * per-user file on a device that still has the alpha single-file residue. Post-S6-A
         * code must never pass this string to [Room.databaseBuilder] — build callers must
         * supply the user-scoped [databaseFilename] instead.
         */
        public const val LEGACY_DATABASE_NAME: String = "becalm.db"

        /**
         * Prefix for per-user SQLite files produced by [databaseFilename].
         *
         * Underscore is the canonical separator in `.spec/auth.spec.yml:73` AUTH-008
         * ("파일명 규칙 `becalm_<sha256(user_id)[:16]>.db`"). Using a dash here would
         * drift the filename away from spec and from [UserPrefsStoreImpl]'s
         * `user_<hash>_*` key namespace.
         */
        private const val USER_DATABASE_PREFIX = "becalm_"

        /** Suffix (file extension) for per-user SQLite files produced by [databaseFilename]. */
        private const val USER_DATABASE_SUFFIX = ".db"

        /**
         * Length (in hex characters) of the userId hash embedded in per-user database file names.
         *
         * 16 hex characters = 8 bytes = 64 bits of SHA-256 output, giving a birthday-collision
         * probability of ~2^-32 — vanishingly small at the per-device user counts BeCalm can
         * ever reach, while keeping the file name short enough to fit well under any filesystem
         * path-length limit. Longer than 16 yields no practical security benefit.
         */
        private const val USER_ID_HASH_LENGTH_CHARS: Int = 16

        /**
         * Current schema version. Increment this integer whenever the schema changes and add
         * a corresponding [androidx.room.migration.Migration] to [MIGRATIONS].
         */
        public const val DATABASE_VERSION: Int = 20

        /**
         * Returns the per-user SQLite filename for the given [userIdHash].
         *
         * The hash MUST be the output of [deriveUserIdHash] — callers pass already-hashed
         * values so the raw Supabase userId never reaches a file name (PIPA defence-in-depth
         * against filesystem-snapshot attacks).
         *
         * @param userIdHash 16-character lowercase hex prefix returned by [deriveUserIdHash].
         * @return The SQLite file name (e.g. `becalm-8b1a…f2.db`) to pass to Room's builder.
         */
        public fun databaseFilename(userIdHash: String): String =
            "$USER_DATABASE_PREFIX$userIdHash$USER_DATABASE_SUFFIX"

        /**
         * Derives the per-user filename hash from a raw Supabase userId.
         *
         * Returns the first [USER_ID_HASH_LENGTH_CHARS] hex characters of SHA-256(userId) —
         * deterministic so a given user always resolves to the same filename across sign-ins,
         * and one-way so the file name does not leak the plaintext userId.
         *
         * @param userId Raw Supabase user UUID.
         * @return Lowercase hex string of length [USER_ID_HASH_LENGTH_CHARS].
         * @throws IllegalArgumentException when [userId] is blank.
         */
        public fun deriveUserIdHash(userId: String): String {
            require(userId.isNotBlank()) { "userId must not be blank" }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(userId.toByteArray(Charsets.UTF_8))
            val hex = StringBuilder(USER_ID_HASH_LENGTH_CHARS)
            for (i in 0 until (USER_ID_HASH_LENGTH_CHARS / 2)) {
                val b = digest[i].toInt() and 0xFF
                hex.append(b.toString(16).padStart(2, '0'))
            }
            return hex.toString()
        }

        /**
         * Creates and opens a user-scoped [BeCalmDatabase] using the standard Room builder.
         *
         * Configuration decisions:
         * - File name comes from [databaseFilename] keyed on [userIdHash] — a different user on
         *   the same device gets a physically distinct SQLite file, eliminating the
         *   cross-account Room data-leakage surface (see `docs/plans/db-auth-user-scoped-database.md`).
         * - `addMigrations(*MIGRATIONS)` — Room applies the registered migrations in order when
         *   upgrading from an older schema version. See [MIGRATIONS] for the rationale.
         * - No destructive-downgrade fallback is registered. If the installed APK's
         *   [DATABASE_VERSION] is lower than the on-disk schema version, Room will throw at open
         *   time rather than silently dropping user data (e.g. `commitmentState`, which is not
         *   mirrored to Railway). Downgrades must be handled explicitly by a forward migration
         *   or by uninstalling the app.
         *
         * @param context     Application context. Pass [android.app.Application] or a
         *   `@ApplicationContext`-qualified [Context] from a Hilt module — never an Activity.
         * @param userIdHash  Output of [deriveUserIdHash] for the signed-in user.
         * @return A fully configured [BeCalmDatabase] ready for DAO access.
         */
        public fun build(context: Context, userIdHash: String): BeCalmDatabase =
            Room.databaseBuilder(
                context,
                BeCalmDatabase::class.java,
                databaseFilename(userIdHash),
            )
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
