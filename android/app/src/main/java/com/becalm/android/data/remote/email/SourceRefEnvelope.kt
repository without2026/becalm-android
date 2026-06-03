package com.becalm.android.data.remote.email

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Shared Moshi envelope for the `raw_ingestion_events.source_ref` column on email
 * source types.
 *
 * Per EMAIL-005 (`.spec/email-pipeline.spec.yml:49-54`) every email row stores a
 * JSON blob `{message_id, in_reply_to?, references?, source_account_key_hash?}` as `source_ref` so the
 * extraction and thread-view layers can reconstruct RFC 5322 threading without
 * a second fetch. Gmail, Outlook Mail, and IMAP ingestion all serialise through
 * this single type so Supabase mirrors, device-side dedupe queries, and any future
 * migration script observe one canonical shape.
 *
 * ## JSON field names
 * [inReplyTo], [references], and [sourceAccountKeyHash] use snake_case on the wire
 * (`in_reply_to`, `references`, `source_account_key_hash`). The first two match
 * the RFC 5322 header names as they appear in every provider's `internetMessageHeaders`
 * array; [sourceAccountKeyHash] is a privacy-preserving account scope for local
 * IMAP sources. [messageId] maps to `message_id`.
 *
 * ## Null handling
 * Both [inReplyTo] and [references] are nullable to mirror `header absent`. The
 * Moshi generated adapter emits absent keys entirely when the constructor defaults
 * kick in; the application-scoped [com.squareup.moshi.Moshi] instance provided by
 * [com.becalm.android.core.di.AppModule.provideMoshi] does not override
 * `serializeNulls`, so nulls are dropped on the wire by default — matching the
 * spec "omit when null" note.
 *
 * ## Coordination
 * This type is the canonical placement agreed during Wave 3 planning — both
 * backend-managed Gmail / Outlook Mail ingestion and local IMAP ingestion import
 * from `data/remote/email/` rather than duplicating per-provider envelopes.
 */
@JsonClass(generateAdapter = true)
public data class SourceRefEnvelope(
    @Json(name = "message_id") val messageId: String,
    @Json(name = "in_reply_to") val inReplyTo: String? = null,
    @Json(name = "references") val references: String? = null,
    @Json(name = "source_account_key_hash") val sourceAccountKeyHash: String? = null,
)
