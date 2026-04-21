package com.becalm.android.data.remote.dto

/**
 * Wire values for the `folder` column on `raw_ingestion_events` and `email_body`.
 *
 * Per EMAIL-001 (`.spec/email-pipeline.spec.yml:15-18`) the `folder` hint carries
 * the direction signal for email source types: `INBOX` means the authenticated user
 * is the recipient (`person_ref` derives from the `From` header), `SENT` means the
 * user is the sender (`person_ref` derives from the first `To` header). Non-email
 * sources leave `folder = null`.
 *
 * These are top-level `const val` — not members of an object — so that bytecode-level
 * references from [com.becalm.android.worker.ingestion.GmailWorker],
 * [com.becalm.android.worker.ingestion.OutlookMailWorker],
 * [com.becalm.android.worker.ingestion.ImapNaverWorker], and
 * [com.becalm.android.worker.ingestion.ImapDaumWorker] are inlined as plain string
 * literals and do not create an import-cycle risk with `email_body` types.
 */
public const val FOLDER_INBOX: String = "INBOX"

/** See the file-level KDoc above. */
public const val FOLDER_SENT: String = "SENT"
