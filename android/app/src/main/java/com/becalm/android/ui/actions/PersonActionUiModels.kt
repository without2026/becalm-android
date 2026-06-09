package com.becalm.android.ui.actions

import com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity
import com.becalm.android.data.repository.PersonActionDraftEvidenceRef
import com.becalm.android.data.repository.PersonActionProviderWriteRequest
import kotlinx.datetime.Instant

public data class PersonActionEvidenceUi(
    val kind: String?,
    val id: String?,
    val sourceRef: String?,
    val occurredAt: Instant?,
    val label: String?,
    val quote: String?,
)

public data class PersonActionItemUi(
    val id: String,
    val personId: String?,
    val personDisplayName: String?,
    val actionKind: String,
    val title: String,
    val primaryVerb: String,
    val shortReason: String,
    val commitmentId: String?,
    val calendarEventId: String?,
    val sourceEventId: String?,
    val sourceType: String?,
    val sourceRef: String?,
    val dueAt: Instant?,
    val dueHint: String?,
    val urgencyScore: Double,
    val confidence: Double,
    val reasonCodes: List<String>,
    val evidence: PersonActionEvidenceUi?,
    val providerWrite: PersonActionProviderWriteUi? = null,
)

public data class PersonActionProviderWriteUi(
    val kind: String,
    val state: String,
    val provider: String?,
    val sourceConnectionId: String?,
    val scheduleEventLinkId: String?,
) {
    public val isReady: Boolean
        get() = state == "ready" &&
            provider?.isNotBlank() == true &&
            sourceConnectionId?.isNotBlank() == true

    public fun toRequest(): PersonActionProviderWriteRequest? {
        if (!isReady) return null
        return PersonActionProviderWriteRequest(
            kind = kind,
            provider = provider,
            sourceConnectionId = sourceConnectionId,
            scheduleEventLinkId = scheduleEventLinkId,
        )
    }
}

public fun PersonActionItemCacheEntity.toPersonActionItemUi(): PersonActionItemUi =
    PersonActionItemUi(
        id = id,
        personId = personId,
        personDisplayName = personDisplayName,
        actionKind = actionKind,
        title = title.toDisplayActionTitle(actionKind = actionKind, primaryVerb = primaryVerb),
        primaryVerb = primaryVerb.toDisplayPrimaryVerb(actionKind = actionKind),
        shortReason = shortReason,
        commitmentId = commitmentId,
        calendarEventId = calendarEventId,
        sourceEventId = sourceEventId,
        sourceType = sourceType,
        sourceRef = sourceRef,
        dueAt = dueAt,
        dueHint = dueHint,
        urgencyScore = urgencyScore,
        confidence = confidence,
        reasonCodes = reasonCodesCsv.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() },
        evidence = if (
            primaryEvidenceKind == null &&
            primaryEvidenceId == null &&
            primaryEvidenceLabel == null &&
            primaryEvidenceQuote == null
        ) {
            null
        } else {
            PersonActionEvidenceUi(
                kind = primaryEvidenceKind,
                id = primaryEvidenceId,
                sourceRef = primaryEvidenceSourceRef,
                occurredAt = primaryEvidenceOccurredAt,
                label = primaryEvidenceLabel,
                quote = primaryEvidenceQuote,
            )
        },
        providerWrite = providerWriteState?.let { state ->
            PersonActionProviderWriteUi(
                kind = providerWriteKind ?: "add_to_calendar",
                state = state,
                provider = providerWriteProvider,
                sourceConnectionId = providerWriteSourceConnectionId,
                scheduleEventLinkId = providerWriteScheduleEventLinkId,
            )
        },
    )

private fun String.toDisplayActionTitle(actionKind: String, primaryVerb: String): String {
    val normalizedTitle = trim()
    val normalizedVerb = primaryVerb.trim()
    if (actionKind == "add_to_calendar" && normalizedVerb == "후보 확인") {
        return normalizedTitle
            .removeSuffix(" $normalizedVerb")
            .trim()
            .ifBlank { normalizedTitle }
    }
    return this
}

private fun String.toDisplayPrimaryVerb(actionKind: String): String =
    if (actionKind == "add_to_calendar" && trim() == "후보 확인") {
        "캘린더에 추가"
    } else {
        this
    }

public fun PersonActionItemUi.supportedDraftKind(): String? =
    actionKind.takeIf { it in DRAFT_ACTION_KINDS }

public fun PersonActionItemUi.draftEvidenceRefs(): List<PersonActionDraftEvidenceRef> =
    listOfNotNull(
        evidence?.takeIf { !it.kind.isNullOrBlank() && !it.id.isNullOrBlank() }?.let { evidence ->
            PersonActionDraftEvidenceRef(
                kind = requireNotNull(evidence.kind),
                evidenceId = requireNotNull(evidence.id),
            )
        },
    )

private val DRAFT_ACTION_KINDS: Set<String> = setOf(
    "reply",
    "follow_up",
    "reconnect_person",
)
