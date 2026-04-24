package com.becalm.android.ui.commitments

import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.remote.dto.SourceType
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Shared presentation formatting policy for commitment detail surfaces.
 *
 * Centralises the short KST time format and user-facing copy used by both the
 * detail ViewModel and the sheet so copy changes do not drift between layers.
 */
internal object CommitmentDetailFormatter {
    private val KST_ZONE: TimeZone = TimeZone.of("Asia/Seoul")

    fun buildSourcePresentation(entity: CommitmentEntity): CommitmentSourcePresentation {
        val isManual = entity.sourceType == SourceType.MANUAL
        val label = if (isManual) {
            "사용자 직접 추가 ${formatIsoKst(entity.createdAt)} KST"
        } else {
            "${entity.sourceEventTitle ?: entity.sourceType}:${formatShortKst(entity.sourceEventOccurredAt)}"
        }
        return CommitmentSourcePresentation(
            isManual = isManual,
            sourceTitle = if (isManual) null else entity.sourceEventTitle,
            sourceOccurredAt = if (isManual) entity.createdAt else entity.sourceEventOccurredAt,
            sourceLabel = label,
        )
    }

    fun buildHistoryPresentation(entity: CommitmentEntity): CommitmentHistoryPresentation =
        CommitmentHistoryPresentation(
            lastEditedAt = entity.lastEditedAt,
            lastEditedLabel = entity.lastEditedAt?.let {
                "마지막 수정: ${formatShortKst(it)} (본인)"
            },
            disputeRaisedAt = entity.quoteDisputedAt,
            disputedLabel = entity.quoteDisputedAt?.let {
                "⚠️ 이의 제기됨 — ${formatShortKst(it)}"
            },
            showSupersedeLink = entity.supersedesCommitmentId != null,
        )

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
