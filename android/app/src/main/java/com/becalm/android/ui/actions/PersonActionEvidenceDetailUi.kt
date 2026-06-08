package com.becalm.android.ui.actions

import com.becalm.android.data.remote.dto.PersonActionEvidenceOriginalDto

public data class PersonActionEvidenceDetailUi(
    val actionItemId: String,
    val evidenceLabel: String,
    val whyText: String,
    val originalTitle: String?,
    val originalText: String,
    val metadataText: String? = null,
    val originalIsLocal: Boolean = false,
    val originalTruncated: Boolean = false,
    val sourceType: String?,
)

public fun PersonActionEvidenceOriginalDto.toPersonActionEvidenceDetailUi(): PersonActionEvidenceDetailUi {
    val originalDetail = original
    val why = listOfNotNull(
        evidence.quote?.takeIf { it.isNotBlank() },
        originalDetail.evidenceText?.takeIf { it.isNotBlank() },
        evidence.label.takeIf { it.isNotBlank() },
    ).firstOrNull().orEmpty()
    val metadataText = listOfNotNull(
        originalDetail.quote?.takeIf { it.isNotBlank() },
        originalDetail.transcriptText?.takeIf { it.isNotBlank() },
        originalDetail.description?.takeIf { it.isNotBlank() },
        originalDetail.snippet?.takeIf { it.isNotBlank() },
        originalDetail.evidenceText?.takeIf { it.isNotBlank() },
        originalDetail.title?.takeIf { it.isNotBlank() },
    )
        .distinct()
        .joinToString("\n\n")
        .takeIf { it.isNotBlank() }
    val localOriginalText = originalDetail.localOriginalText?.takeIf { it.isNotBlank() }
    return PersonActionEvidenceDetailUi(
        actionItemId = actionItemId,
        evidenceLabel = evidence.label,
        whyText = why,
        originalTitle = originalDetail.localOriginalTitle?.takeIf { it.isNotBlank() }
            ?: originalDetail.title?.takeIf { it.isNotBlank() },
        originalText = localOriginalText ?: metadataText.orEmpty(),
        metadataText = metadataText,
        originalIsLocal = localOriginalText != null,
        originalTruncated = originalDetail.localOriginalTruncated,
        sourceType = originalDetail.sourceType,
    )
}
