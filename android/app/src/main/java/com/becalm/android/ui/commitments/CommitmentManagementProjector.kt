package com.becalm.android.ui.commitments

import com.becalm.android.core.util.KST
import com.becalm.android.data.local.db.dao.CommitmentManagementRow
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.commitment.CommitmentState
import kotlinx.datetime.Instant
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

internal object CommitmentManagementProjector {
    fun buildUiState(
        current: CommitmentUiState,
        rows: List<CommitmentManagementRow>,
        filter: CommitmentFilter = current.filter,
        loading: Boolean = current.loading,
        now: Instant,
    ): CommitmentUiState {
        val projectedRows = applyFilter(rows, filter, now)
        return current.copy(
            filter = filter,
            items = projectedRows,
            activeItems = projectedRows.filterNot(::isTerminalRow),
            completedSection = buildSectionState(
                rows = projectedRows,
                targetState = CommitmentState.COMPLETED,
                expanded = current.completedSection.expanded,
            ),
            cancelledSection = buildSectionState(
                rows = projectedRows,
                targetState = CommitmentState.CANCELLED,
                expanded = current.cancelledSection.expanded,
            ),
            loading = loading,
        )
    }

    fun applyFilter(
        rows: List<CommitmentManagementRow>,
        filter: CommitmentFilter,
        now: Instant,
    ): List<CommitmentRow> {
        val filtered = when (filter) {
            CommitmentFilter.ALL -> rows
            CommitmentFilter.ACTION -> rows.filter { it.itemType == CommitmentItemType.ACTION }
            CommitmentFilter.GIVE -> rows.filter {
                it.itemType == CommitmentItemType.ACTION && it.direction == "give"
            }
            CommitmentFilter.TAKE -> rows.filter {
                it.itemType == CommitmentItemType.ACTION && it.direction == "take"
            }
            CommitmentFilter.SCHEDULE -> rows.filter { it.itemType == CommitmentItemType.SCHEDULE }
            CommitmentFilter.DECISION -> rows.filter { it.itemType == CommitmentItemType.DECISION }
        }
        return filtered
            .sortedForDisplay(now)
            .map { row -> row.toUiRow() }
    }

    private fun List<CommitmentManagementRow>.sortedForDisplay(now: Instant): List<CommitmentManagementRow> =
        sortedWith(
            compareBy<CommitmentManagementRow> { it.exactDueSortGroup(now) }
                .thenBy { it.exactDueDistance(now) }
                .thenByDescending { row -> row.dueAt?.takeUnless { row.dueIsApproximate } }
                .thenByDescending { it.sourceOccurredAt },
        )

    private fun CommitmentManagementRow.exactDueSortGroup(now: Instant): Int {
        val dayDelta = exactDueDayDelta(now) ?: return 3
        return when {
            dayDelta < 0 -> 0
            dayDelta == 0 -> 1
            else -> 2
        }
    }

    private fun CommitmentManagementRow.exactDueDistance(now: Instant): Int =
        exactDueDayDelta(now)?.let { kotlin.math.abs(it) } ?: Int.MAX_VALUE

    private fun CommitmentManagementRow.exactDueDayDelta(now: Instant): Int? {
        if (dueIsApproximate) return null
        val due = dueAt ?: return null
        val today = now.toLocalDateTime(KST).date
        val dueDate = due.toLocalDateTime(KST).date
        return today.daysUntil(dueDate)
    }

    private fun CommitmentManagementRow.toUiRow(): CommitmentRow {
        val state = CommitmentState.fromWire(actionState)
        val exactDueAt = dueAt?.takeUnless { dueIsApproximate }
        return CommitmentRow(
            id = id,
            itemType = itemType,
            title = title,
            direction = direction,
            scheduleStatus = scheduleStatus,
            decisionStatus = decisionStatus,
            derivedStatus = direction?.let { state.name },
            actionState = state,
            dueAt = exactDueAt,
            dueIsApproximate = false,
            counterpartyDisplayName = counterpartyDisplayName,
            sourceType = sourceType,
            sourceTitle = sourceTitle,
            sourceOccurredAt = sourceOccurredAt,
            dueHint = null,
            isManual = sourceType == SourceType.MANUAL,
        )
    }

    fun buildSectionState(
        rows: List<CommitmentRow>,
        targetState: CommitmentState,
        expanded: Boolean,
    ): CommitmentSectionUiState {
        val sectionRows = rows.filter { it.actionState == targetState }
        return CommitmentSectionUiState(
            count = sectionRows.size,
            items = sectionRows,
            expanded = expanded,
            dimmed = true,
        )
    }

    fun isTerminalRow(row: CommitmentRow): Boolean =
        row.itemType == CommitmentItemType.ACTION &&
            (row.actionState == CommitmentState.COMPLETED ||
                row.actionState == CommitmentState.CANCELLED)
}
