package com.becalm.android.data.remote.gmail

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Lightweight descriptor for one email attachment part surfaced via the Gmail
 * `messages.get?format=full` MIME walk.
 *
 * EMAIL-004 (`.spec/email-pipeline.spec.yml:40-45`) requires the pipeline to
 * record attachment **metadata only** — filename, mime type, and declared byte
 * size — not the binary payload. Persisted as JSON inside
 * [com.becalm.android.data.local.db.entity.EmailBodyEntity.attachmentsMeta];
 * never uploaded to Railway (EMAIL-006 room-only invariant).
 *
 * @property filename Content-Disposition `filename=` value, or the `filename`
 *   field Gmail attaches to the part. Empty parts are filtered out before this
 *   DTO is constructed.
 * @property mime The part's declared MIME type (e.g. `application/pdf`,
 *   `image/png`). Copied verbatim from Gmail's `mimeType` field.
 * @property sizeBytes Declared part size in bytes (Gmail `body.size`). Zero
 *   when Gmail omits the field; never negative.
 */
@JsonClass(generateAdapter = true)
public data class GmailAttachmentMeta(
    @Json(name = "filename") val filename: String,
    @Json(name = "mime") val mime: String,
    @Json(name = "size_bytes") val sizeBytes: Long,
)
