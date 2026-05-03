package com.becalm.android.ui.persons

/**
 * UI-layer projection of [com.becalm.android.data.local.db.entity.EmailBodyEntity].
 *
 * Deliberately narrower than the Room entity — only the fields
 * [RawEventDetailSheet] renders are carried across the VM↔UI boundary. Fields such
 * as `raw_headers`, `from_address`, `to_addresses`, `received_at`, and the full
 * `parse_failed` / `group_email` flags are intentionally not lifted here so that a
 * future UI change cannot accidentally serialize them onto a network DTO (EMAIL-006
 * PIPA invariant — `.spec/email-pipeline.spec.yml:58-64`).
 *
 * `attachmentsMetaJson` is kept as the raw JSON string, not a parsed list, so that
 * parsing can be delegated to [AttachmentMetaParser] on a background dispatcher and
 * the UI only receives a count via [RawEventDetailUiState.attachmentCount].
 */
public data class EmailBodyUi(
    val bodyPlain: String?,
    val bodyHtml: String?,
)

public data class ArchivedOriginalUi(
    val bodyText: String?,
    val deletedFromDevice: Boolean,
    val truncated: Boolean,
)
