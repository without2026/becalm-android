package com.becalm.android.data.remote.imap

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Metadata-only descriptor for a single IMAP message attachment part.
 *
 * Mirrors the shape of `email_body.attachments_meta`
 * (`.spec/contracts/data-model.yml:327-390 § attachments_meta`) — a JSON array of
 * `[{filename, mime, size_bytes}, ...]`. EMAIL-004
 * (`.spec/email-pipeline.spec.yml:40-45`) forbids downloading the attachment bytes
 * from IMAP: the worker MUST populate these fields from BODYSTRUCTURE only and
 * never trigger a `BODY[part]` FETCH.
 *
 * ## Sources of each field
 * - [filename]   — `part.getFileName()`, MIME-decoded by Jakarta Mail.
 * - [mime]       — `part.getContentType()`, stripped of parameters
 *                  (e.g. `application/pdf; name=foo.pdf` → `application/pdf`).
 * - [sizeBytes]  — `part.getSize()`, which reflects BODYSTRUCTURE's `SIZE` field
 *                  and therefore requires no extra network round-trip.
 *
 * ## JSON field names
 * snake_case on the wire (`size_bytes`) to match the Supabase `email_body` column
 * shape; Moshi generates the adapter at compile time via `@JsonClass`.
 */
@JsonClass(generateAdapter = true)
public data class ImapAttachmentMeta(
    @Json(name = "filename") val filename: String,
    @Json(name = "mime") val mime: String,
    @Json(name = "size_bytes") val sizeBytes: Long,
)
