package com.becalm.android.data.remote.dto

import com.squareup.moshi.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField

/**
 * Architectural invariant tests guarding the EMAIL-006 PIPA boundary at the wire layer.
 *
 * These tests catch a regression where a future PR adds an `email_body` field to
 * [RawIngestionEventDto] (or any other email-body field) so that body content would
 * leak to Railway/Supabase. Since `RawIngestionEventDto` is the ONLY type passed to
 * [com.becalm.android.data.repository.RawIngestionRepository.uploadBatch], pinning
 * its allowed JSON-key set is sufficient to prevent the invariant violation.
 *
 * The third test scans the source text of [com.becalm.android.data.repository.EmailBodyRepository]
 * to assert no DTO type is imported — a structural seal that survives even if a future
 * developer accidentally adds a transitive type leak through a new helper function.
 *
 * Spec refs: EMAIL-006, `data-ingestion.spec.yml:152`,
 * `.spec/contracts/data-model.yml:327-328 § email_body.room_only`.
 */
class DtoInvariantTest {

    /**
     * Wire JSON keys that MUST NEVER appear on [RawIngestionEventDto]. Adding any of
     * these would mean `EmailBody` content (or person-identifying email-body metadata)
     * is being uploaded to Railway, violating the room-only contract.
     */
    private val forbiddenJsonKeys: Set<String> = setOf(
        "body_plain",
        "body_html",
        "attachments_meta",
        "raw_headers",
        "from_address",
        "to_addresses",
        "parse_failed",
        "group_email",
    )

    /**
     * The exhaustive allowed set of wire JSON keys on [RawIngestionEventDto], in lockstep
     * with `.spec/contracts/data-model.yml § raw_ingestion_events`. Any deviation here
     * is an intentional wire-contract change that requires an explicit review — pinning
     * the set turns the test failure into a review trigger.
     */
    private val allowedJsonKeys: Set<String> = setOf(
        "id",
        "client_event_id",
        "source_type",
        "source_ref",
        "person_ref",
        "event_title",
        "event_snippet",
        "duration_seconds",
        "location",
        "folder",
        "commitments_extracted_count",
        "timestamp",
    )

    // ─── Test 1 ─────────────────────────────────────────────────────────────

    @Test
    fun rawIngestionEventDto_doesNotExposeEmailBodyFields() {
        val wireKeys = collectWireJsonKeys(RawIngestionEventDto::class)

        val intersection = wireKeys.intersect(forbiddenJsonKeys)
        assertTrue(
            "EMAIL-006 violation: RawIngestionEventDto exposes forbidden email-body keys " +
                "$intersection. Body fields must remain in EmailBody (Room-only) and never " +
                "reach the wire — see .spec/email-pipeline.spec.yml:58-64.",
            intersection.isEmpty(),
        )
    }

    // ─── Test 2 ─────────────────────────────────────────────────────────────

    @Test
    fun rawIngestionEventDto_exactFieldSet() {
        val wireKeys = collectWireJsonKeys(RawIngestionEventDto::class)

        assertEquals(
            "RawIngestionEventDto wire-key set drifted from data-model.yml. " +
                "Adding/removing a wire key is an intentional contract change — " +
                "update both the spec AND the allowedJsonKeys constant in this test.",
            allowedJsonKeys,
            wireKeys,
        )
    }

    // ─── Test 3 ─────────────────────────────────────────────────────────────

    @Test
    fun emailBodyRepository_doesNotImportDtoTypes() {
        // Read the EmailBodyRepository.kt source via the test classloader's
        // resource lookup. The source file is NOT a runtime resource, so we
        // resolve via the project tree relative to the test working directory.
        val sourceFile = java.io.File(
            "src/main/java/com/becalm/android/data/repository/EmailBodyRepository.kt",
        )
        assertTrue(
            "Source file not found at ${sourceFile.absolutePath} — test must run with " +
                "Gradle's :app test working directory.",
            sourceFile.exists(),
        )

        val text = sourceFile.readText()

        // Grep for the forbidden import package. We allow KDoc references to
        // `com.becalm.android.data.remote.dto` because those don't actually
        // import the type — only top-of-file `import` statements do.
        val forbiddenImport = "import com.becalm.android.data.remote.dto"
        assertFalse(
            "EMAIL-006 architectural seal broken: EmailBodyRepository.kt imports a " +
                "wire DTO type. Repository must never expose body fields to any wire " +
                "layer. Found '$forbiddenImport' in source.",
            text.contains(forbiddenImport),
        )
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    /**
     * Collects every `@field:Json(name = "...")` wire key declared on the data-class
     * properties of [klass]. Constructor properties annotated with `@Json(ignore=true)`
     * (no field-level `@Json` name) are intentionally skipped — they are not part of
     * the wire contract.
     */
    private fun <T : Any> collectWireJsonKeys(klass: kotlin.reflect.KClass<T>): Set<String> {
        val keys = mutableSetOf<String>()
        for (prop in klass.memberProperties) {
            val field = prop.javaField ?: continue
            val ann = field.getAnnotation(Json::class.java) ?: continue
            // Defensive: a `@Json(ignore=true)` annotation has no `name` (defaults to
            // [Json.UNSET_NAME] which is the single null char "\u0000"). Treat
            // unset-name as "not part of the wire" and skip.
            if (ann.name != Json.UNSET_NAME && ann.name.isNotEmpty()) {
                keys.add(ann.name)
            }
        }
        assertNotNull("Reflection collected no @field:Json keys on ${klass.simpleName} — " +
            "kotlin-reflect missing or annotation retention wrong?", keys.takeIf { it.isNotEmpty() })
        return keys
    }
}
