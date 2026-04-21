package com.becalm.android.data.local.db

import androidx.room.TypeConverter
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * Room [TypeConverter] implementations for kotlinx.datetime types.
 *
 * Registered at the database level via `@TypeConverters(Converters::class)` on
 * [BeCalmDatabase]. Do NOT register these on individual entity or DAO classes —
 * database-level registration makes the converters visible to every query and column
 * in the schema, which is the correct scope for shared types like [Instant].
 *
 * ## Type mapping strategy
 *
 * | Kotlin type      | SQLite column type | Encoding            |
 * |------------------|--------------------|---------------------|
 * | [Instant]        | INTEGER (NOT NULL) | epoch milliseconds  |
 * | [Instant]?       | INTEGER            | epoch ms, NULL safe |
 * | [LocalDate]      | TEXT (NOT NULL)    | ISO "YYYY-MM-DD"    |
 * | [LocalDate]?     | TEXT               | ISO "YYYY-MM-DD", NULL safe |
 * | [LocalDateTime]  | TEXT (NOT NULL)    | ISO "YYYY-MM-DDTHH:MM:SS" |
 * | [LocalDateTime]? | TEXT               | ISO "YYYY-MM-DDTHH:MM:SS", NULL safe |
 *
 * ## Why epoch milliseconds for Instant?
 * SQLite INTEGER stores up to 8 bytes (int64), which covers the full epoch-millisecond
 * range without precision loss. Millisecond resolution is sufficient for all BeCalm
 * timestamps; sub-millisecond precision is not needed and would waste storage.
 *
 * ## Why ISO strings for LocalDate / LocalDateTime?
 * These types have no meaningful numeric epoch. ISO 8601 strings are lexicographically
 * sortable, human-readable in SQLite browser tools, and round-trip exactly through
 * kotlinx.datetime's [LocalDate.parse] / [LocalDateTime.parse].
 *
 * ## Nullability contract
 * All six converter pairs have nullable variants. Room calls the nullable form when
 * the column is declared nullable in the entity; the non-null form is provided as
 * a convenience but Room will also select the nullable form for non-null columns if
 * that is the only registered converter for the type.
 */
public class Converters {

    // ─── Instant ↔ Long (epoch milliseconds) ─────────────────────────────────

    /**
     * Converts an [Instant] to a [Long] epoch-millisecond value for SQLite storage.
     *
     * @param value The [Instant] to encode, or null.
     * @return Epoch milliseconds, or null when [value] is null.
     */
    @TypeConverter
    public fun fromInstant(value: Instant?): Long? = value?.toEpochMilliseconds()

    /**
     * Reconstructs an [Instant] from a stored epoch-millisecond [Long].
     *
     * @param value Epoch milliseconds read from the SQLite column, or null.
     * @return The decoded [Instant], or null when [value] is null.
     */
    @TypeConverter
    public fun toInstant(value: Long?): Instant? =
        value?.let { Instant.fromEpochMilliseconds(it) }

    // ─── LocalDate ↔ String (ISO "YYYY-MM-DD") ────────────────────────────────

    /**
     * Encodes a [LocalDate] as its ISO 8601 string representation, e.g. `"2026-04-16"`.
     *
     * [LocalDate.toString] produces the ISO 8601 `"YYYY-MM-DD"` format directly.
     * The resulting string is lexicographically sortable, so ORDER BY clauses on
     * date columns work correctly without special treatment.
     *
     * @param value The [LocalDate] to encode, or null.
     * @return ISO date string, or null when [value] is null.
     */
    @TypeConverter
    public fun fromLocalDate(value: LocalDate?): String? = value?.toString()

    /**
     * Reconstructs a [LocalDate] from an ISO 8601 date string.
     *
     * @param value ISO date string (e.g. `"2026-04-16"`), or null.
     * @return The decoded [LocalDate], or null when [value] is null.
     * @throws IllegalArgumentException if the string is non-null but not valid ISO 8601.
     */
    @TypeConverter
    public fun toLocalDate(value: String?): LocalDate? =
        value?.let { LocalDate.parse(it) }

    // ─── CommitmentLifecycleLegacy ↔ String (enum name) ──────────────────────

    /**
     * Encodes a [CommitmentLifecycleLegacy] as its enum [name] for SQLite TEXT storage.
     *
     * This converter backs the dead `commitments.commitment_state` column; it is
     * preserved only so Room can still round-trip rows written by pre-Wave-4 app
     * versions. New code should rely on [CommitmentEntity.actionState] instead.
     *
     * @param value The [CommitmentLifecycleLegacy] to encode, or null.
     * @return The enum name (e.g. "DRAFT"), or null when [value] is null.
     */
    @TypeConverter
    public fun fromCommitmentLifecycleLegacy(value: CommitmentLifecycleLegacy?): String? = value?.name

    /**
     * Reconstructs a [CommitmentLifecycleLegacy] from its stored enum name.
     *
     * Falls back to [CommitmentLifecycleLegacy.DRAFT] when the stored string is
     * unrecognized, so that a stored value unknown to this app version does not crash.
     *
     * @param value The stored string (e.g. "DRAFT"), or null.
     * @return The decoded [CommitmentLifecycleLegacy], or null when [value] is null.
     */
    @TypeConverter
    public fun toCommitmentLifecycleLegacy(value: String?): CommitmentLifecycleLegacy? =
        value?.let {
            runCatching { CommitmentLifecycleLegacy.valueOf(it) }
                .getOrDefault(CommitmentLifecycleLegacy.DRAFT)
        }

    // ─── LocalDateTime ↔ String (ISO "YYYY-MM-DDTHH:MM:SS") ──────────────────

    /**
     * Encodes a [LocalDateTime] as its ISO 8601 string representation,
     * e.g. `"2026-04-16T14:30:00"`.
     *
     * [LocalDateTime.toString] produces the ISO 8601 combined date-time format.
     * Note that [LocalDateTime] carries no timezone information — callers are
     * responsible for applying the correct timezone context when converting from
     * an [Instant].
     *
     * @param value The [LocalDateTime] to encode, or null.
     * @return ISO datetime string, or null when [value] is null.
     */
    @TypeConverter
    public fun fromLocalDateTime(value: LocalDateTime?): String? = value?.toString()

    /**
     * Reconstructs a [LocalDateTime] from an ISO 8601 datetime string.
     *
     * @param value ISO datetime string (e.g. `"2026-04-16T14:30:00"`), or null.
     * @return The decoded [LocalDateTime], or null when [value] is null.
     * @throws IllegalArgumentException if the string is non-null but not valid ISO 8601.
     */
    @TypeConverter
    public fun toLocalDateTime(value: String?): LocalDateTime? =
        value?.let { LocalDateTime.parse(it) }
}
