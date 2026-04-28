package com.becalm.android.core.util

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonQualifier
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import java.lang.reflect.Type
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaInstant

/** Moshi adapter for [Instant]. Serializes as ISO-8601 with `Z` UTC offset (e.g. `"2026-04-16T05:30:00Z"`). */
public class InstantAdapter {
    @ToJson public fun toJson(value: Instant): String = value.toString()
    @FromJson public fun fromJson(value: String): Instant = Instant.parse(value)
}

/** Moshi adapter for [LocalDate]. Serializes as `"YYYY-MM-DD"` (e.g. `"2026-04-16"`). */
public class LocalDateAdapter {
    @ToJson public fun toJson(value: LocalDate): String = value.toString()
    @FromJson public fun fromJson(value: String): LocalDate = LocalDate.parse(value)
}

/**
 * Moshi [JsonQualifier] requesting that an [Instant] field be emitted on the wire
 * with an explicit `+09:00` KST offset instead of UTC `Z` — the default
 * [InstantAdapter] behaviour.
 *
 * Required for commitment `due_at` payloads per
 * `.spec/contracts/api-contract.yml:32`:
 * > ISO8601 with explicit +09:00 offset (KST). Android는 UTC로 저장·계산하되
 * > payload는 KST로 전송한다.
 *
 * Apply field-scoped (e.g. `@field:KstInstant @field:Json(name = "due_at")`) rather
 * than globally — only commitment due-date fields require KST-on-the-wire. All
 * other timestamps continue to serialize as UTC.
 *
 * Storage and domain representation are unaffected; this is strictly a wire-format
 * qualifier.
 */
@Retention(AnnotationRetention.RUNTIME)
@JsonQualifier
public annotation class KstInstant

private val KST_OFFSET: ZoneOffset = ZoneOffset.ofHours(9)

/**
 * Dedicated Moshi adapter for [Instant] fields marked with [@KstInstant].
 *
 * Serialization: converts the UTC [Instant] into the Asia/Seoul civil time and
 * appends the literal `+09:00` offset — e.g. `2026-04-19T15:00:00Z` becomes
 * `2026-04-20T00:00:00+09:00`. Sub-second precision is preserved when present:
 * `2026-04-19T15:00:00.123Z` emits as `2026-04-20T00:00:00.123+09:00`.
 *
 * Deserialization is tolerant: accepts both `+09:00` KST offsets AND UTC `Z`
 * payloads (and any other ISO-8601 offset). Server responses that echo `due_at`
 * back as UTC must still round-trip cleanly — internal storage remains UTC-based
 * regardless of wire representation.
 *
 * Spec refs: `.spec/contracts/api-contract.yml:32`.
 */
public object KstInstantAdapterFactory : JsonAdapter.Factory {
    override fun create(
        type: Type,
        annotations: Set<Annotation>,
        moshi: Moshi,
    ): JsonAdapter<*>? {
        if (type != Instant::class.java) {
            return null
        }

        val delegateAnnotations = annotations.filterNot { it is KstInstant }.toSet()
        if (delegateAnnotations.size == annotations.size) {
            return null
        }

        val delegate = moshi.adapter<String>(String::class.java, delegateAnnotations)
        return object : JsonAdapter<Instant>() {
            override fun toJson(writer: JsonWriter, value: Instant?) {
                if (value == null) {
                    writer.nullValue()
                    return
                }

                writer.value(
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                        value.toJavaInstant().atOffset(KST_OFFSET),
                    ),
                )
            }

            override fun fromJson(reader: JsonReader): Instant? {
                if (reader.peek() == JsonReader.Token.NULL) {
                    return reader.nextNull()
                }
                return delegate.fromJson(reader)?.let(Instant::parse)
            }
        }
    }
}

/**
 * Registers all BeCalm kotlinx-datetime adapters on this [Moshi.Builder] in a single call.
 *
 * SP-06 (AppModule) calls this when constructing the application-scoped [Moshi] instance.
 */
public fun Moshi.Builder.addBecalmAdapters(): Moshi.Builder =
    this
        .add(KstInstantAdapterFactory)
        .add(InstantAdapter())
        .add(LocalDateAdapter())
