package com.becalm.android.data.remote.msgraph

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Lightweight attachment descriptor returned by `MsGraphClient.messageAttachments`.
 *
 * Per EMAIL-004 (`.spec/email-pipeline.spec.yml:40-45`) BeCalm stores **metadata only**
 * for email attachments — binary content never touches Room or the wire. The fields
 * here mirror the MS Graph `$select=name,contentType,size` projection one-for-one.
 *
 * ## Wire shape
 * Graph returns attachments as
 * `{ "value": [{ "name": "...", "contentType": "...", "size": <int64> }, ...] }`.
 * This DTO lives behind the `MsGraphClient` boundary and is serialised into
 * [com.becalm.android.data.local.db.entity.EmailBodyEntity.attachmentsMeta] by the
 * worker using Moshi's generated adapter.
 *
 * @property name Attachment filename as reported by Graph. May be empty for anonymous
 *   inline parts.
 * @property contentType MIME type (`image/jpeg`, `application/pdf`, …).
 * @property sizeBytes Declared payload size in bytes. `Long` because attachments
 *   can exceed [Int.MAX_VALUE] for large PDFs / videos.
 */
@JsonClass(generateAdapter = true)
public data class GraphAttachmentMeta(
    @Json(name = "name") val name: String,
    @Json(name = "contentType") val contentType: String,
    @Json(name = "sizeBytes") val sizeBytes: Long,
)
