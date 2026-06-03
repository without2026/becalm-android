package com.becalm.android.ui.actions

import com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity
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
)

public fun PersonActionItemCacheEntity.toPersonActionItemUi(): PersonActionItemUi =
    PersonActionItemUi(
        id = id,
        personId = personId,
        personDisplayName = personDisplayName,
        actionKind = actionKind,
        title = title,
        primaryVerb = primaryVerb,
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
    )
