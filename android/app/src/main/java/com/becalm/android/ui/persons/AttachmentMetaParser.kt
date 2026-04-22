package com.becalm.android.ui.persons

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Parses the `email_body.attachments_meta` JSON column into a display-friendly
 * list of [AttachmentMeta].
 *
 * The column shape is `[{filename, mime, size_bytes}, ...]` per
 * `.spec/contracts/data-model.yml:327-390 § attachments_meta`; this parser mirrors
 * the existing on-wire shapes (`ImapAttachmentMeta`, `GmailAttachmentMeta`,
 * `GraphAttachmentMeta`) without creating a new UI-owned wire contract.
 *
 * Failure policy — **graceful degrade** (EMAIL-007): a malformed or unexpected
 * JSON payload yields an empty list rather than surfacing an error, so the sheet
 * renders "no attachments" instead of hiding the whole event. False-negative
 * attachment counts are acceptable; a missing event is not.
 */
internal object AttachmentMetaParser {

    private val adapter by lazy {
        val moshi = Moshi.Builder().build()
        val listType = Types.newParameterizedType(List::class.java, AttachmentMeta::class.java)
        moshi.adapter<List<AttachmentMeta>>(listType).lenient()
    }

    /**
     * Returns the attachment descriptors encoded in [json]. Returns an empty list
     * when [json] is null, blank, or unparseable.
     */
    fun parse(json: String?): List<AttachmentMeta> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { adapter.fromJson(json) }
            .getOrNull()
            .orEmpty()
    }
}

/**
 * Metadata-only descriptor for a single email attachment rendered by the raw-event
 * detail sheet. Mirrors the on-disk JSON shape produced by ingestion workers
 * (`ImapAttachmentMeta`, `GmailAttachmentMeta`, `GraphAttachmentMeta`).
 */
@JsonClass(generateAdapter = true)
internal data class AttachmentMeta(
    @Json(name = "filename") val filename: String,
    @Json(name = "mime") val mime: String,
    @Json(name = "size_bytes") val sizeBytes: Long,
)
