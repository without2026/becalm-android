package com.becalm.android.ui.commitments

import com.becalm.android.R
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.remote.dto.SourceType
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Shared presentation formatting policy for commitment detail surfaces.
 *
 * Centralises the short KST time format and string-resource selection used by
 * detail/edit projections. Compose resolves the final copy via stringResource
 * so locale changes are applied at render time.
 */
internal object CommitmentDetailFormatter {
    private val KST_ZONE: TimeZone = TimeZone.of("Asia/Seoul")

    fun buildSourcePresentation(entity: CommitmentEntity): CommitmentSourcePresentation {
        val isManual = entity.sourceType == SourceType.MANUAL
        val occurredAt = if (isManual) entity.createdAt else entity.sourceEventOccurredAt
        val label = if (isManual) {
            buildManualSourceText(entity.createdAt)
        } else {
            buildEventSourceText(entity)
        }
        return CommitmentSourcePresentation(
            isManual = isManual,
            sourceType = entity.sourceType,
            sourceTitle = if (isManual) null else entity.sourceEventTitle,
            sourceOccurredAt = occurredAt,
            sourceLabel = label,
        )
    }

    fun buildHistoryPresentation(entity: CommitmentEntity): CommitmentHistoryPresentation =
        CommitmentHistoryPresentation(
            lastEditedAt = entity.lastEditedAt,
            disputeRaisedAt = entity.quoteDisputedAt,
            showSupersedeLink = entity.supersedesCommitmentId != null,
        )

    fun buildCompactSourceLabel(entity: CommitmentEntity): CommitmentText =
        if (entity.sourceType == SourceType.MANUAL) {
            buildManualSourceText(entity.createdAt)
        } else {
            buildEventSourceText(entity)
        }

    private fun buildManualSourceText(createdAt: Instant): CommitmentText =
        CommitmentText(
            resId = R.string.commitment_detail_manual_source_fmt,
            args = listOf(formatIsoKst(createdAt)),
        )

    private fun buildEventSourceText(entity: CommitmentEntity): CommitmentText {
        val formattedTime = formatShortKst(entity.sourceEventOccurredAt)
        val title = entity.sourceEventTitle?.takeIf { it.isNotBlank() }
        return if (title == null) {
            CommitmentText(
                resId = R.string.commitment_detail_llm_source_original_fmt,
                args = listOf(formattedTime),
            )
        } else {
            CommitmentText(
                resId = R.string.commitment_detail_llm_source_fmt,
                args = listOf(title, formattedTime),
            )
        }
    }

    fun formatShortKst(instant: Instant): String {
        val ldt = instant.toLocalDateTime(KST_ZONE)
        val hour = ldt.hour.toString().padStart(2, '0')
        val minute = ldt.minute.toString().padStart(2, '0')
        return "${ldt.monthNumber}/${ldt.dayOfMonth} $hour:$minute"
    }

    private fun formatIsoKst(instant: Instant): String {
        val ldt = instant.toLocalDateTime(KST_ZONE)
        val hour = ldt.hour.toString().padStart(2, '0')
        val minute = ldt.minute.toString().padStart(2, '0')
        val month = ldt.monthNumber.toString().padStart(2, '0')
        val day = ldt.dayOfMonth.toString().padStart(2, '0')
        return "${ldt.year}-$month-$day $hour:$minute"
    }
}
