package com.becalm.android.data.remote.dto

import com.becalm.android.core.util.KstInstant
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.datetime.Instant

/**
 * Expanded partial-edit body for `PATCH /v1/commitments/{id}` issued by the
 * `commitment-edit.spec.yml` Stage-5 flow (EDIT-003 / EDIT-005 / EDIT-006 /
 * EDIT-007).
 *
 * Unlike the legacy [PatchCommitmentRequest] which ships exactly one field
 * ([PatchCommitmentRequest.actionState]), this DTO mirrors every user-writable
 * column in the `commitments` table and marks each one nullable so the client can
 * send just the fields that changed. Moshi serialises Kotlin `null` as a JSON
 * `null`; the server's Pydantic model treats `null` as "field omitted / retain
 * the existing value" for these columns (see
 * `.spec/commitment-edit.spec.yml` EDIT-003 body contract).
 *
 * ## Invariants
 *
 * 1. The quote string itself is NEVER mutable. [quoteDisputed] toggles the
 *    dispute flag (EDIT-005) but the verbatim quote column remains read-only;
 *    intentionally omitted from this DTO so a future contributor cannot
 *    accidentally attach a `quote` payload.
 * 2. `source_type`, `source_event_id`, `source_event_occurred_at`,
 *    `source_event_title`, and `confidence` are immutable on edit
 *    (`.spec/commitment-edit.spec.yml` invariant 3) — also intentionally
 *    omitted.
 * 3. `id` / `user_id` / `created_at` / `updated_at` are server-owned — omitted.
 *
 * ## Field ordering
 *
 * Matches `data-model.yml` column order so PR reviewers can diff the wire shape
 * against the schema without re-sorting.
 *
 * Spec refs: `.spec/commitment-edit.spec.yml` EDIT-003, EDIT-005, EDIT-006,
 * `.spec/contracts/data-model.yml:188-210`.
 */
@JsonClass(generateAdapter = true)
public data class CommitmentPatchDto(

    /** New title (1..200 chars after trim) or null to leave unchanged. */
    @field:Json(name = "title") val title: String? = null,

    /**
     * New deadline instant, or null to leave the server value unchanged.
     *
     * Wire format: ISO-8601 with explicit `+09:00` KST offset via the shared
     * [KstInstant] qualifier. Note that this column is **also legitimately null**
     * for a commitment with no deadline; the client expresses "clear the
     * deadline" by a separate idempotency rule in EDIT-003 — the current spec
     * treats a missing key as "unchanged" and an explicit `null` as "clear".
     */
    @field:KstInstant @field:Json(name = "due_at") val dueAt: Instant? = null,

    /** Verbatim due-date expression from the source, or null to leave unchanged / clear. */
    @field:Json(name = "due_hint") val dueHint: String? = null,

    /** New `due_is_approximate` flag, or null to leave unchanged. */
    @field:Json(name = "due_is_approximate") val dueIsApproximate: Boolean? = null,

    /** Canonicalized counterparty identifier, or null to leave unchanged / clear. */
    @field:Json(name = "person_ref") val personRef: String? = null,

    /** `"give"` | `"take"`, or null to leave unchanged. */
    @field:Json(name = "direction") val direction: String? = null,

    /** Legacy EDIT-003 single-field update — kept for backward compatibility. */
    @field:Json(name = "action_state") val actionState: String? = null,

    /** True to raise dispute (EDIT-005), false to clear, or null to leave unchanged. */
    @field:Json(name = "quote_disputed") val quoteDisputed: Boolean? = null,

    /** Timestamp paired with [quoteDisputed]; null when the flag is unchanged or cleared. */
    @field:Json(name = "quote_disputed_at") val quoteDisputedAt: Instant? = null,

    /**
     * Soft-delete marker (EDIT-006). Non-null tombstones the row on the server
     * side via the same UPDATE path — the server never hard-deletes.
     */
    @field:Json(name = "deleted_at") val deletedAt: Instant? = null,

    /** Audit column — mirror of the local Room `last_edited_at` write. */
    @field:Json(name = "last_edited_at") val lastEditedAt: Instant? = null,

    /** Audit column — mirror of the local Room `last_edited_by` write. */
    @field:Json(name = "last_edited_by") val lastEditedBy: String? = null,
)
