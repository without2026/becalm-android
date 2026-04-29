package com.becalm.android.ui.commitments

import com.becalm.android.data.local.db.dao.CommitmentManagementRow
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.commitment.CommitmentState

internal object CommitmentManagementProjector {
    fun buildUiState(
        current: CommitmentUiState,
        rows: List<CommitmentManagementRow>,
        filter: CommitmentFilter = current.filter,
        loading: Boolean = current.loading,
    ): CommitmentUiState {
        val projectedRows = applyFilter(rows, filter)
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
            .sortedForDisplay()
            .map { row -> row.toUiRow() }
    }

    private fun List<CommitmentManagementRow>.sortedForDisplay(): List<CommitmentManagementRow> =
        sortedWith(
            compareBy<CommitmentManagementRow> { it.dueAt == null || it.dueIsApproximate }
                .thenBy { it.dueAt }
                .thenByDescending { it.sourceOccurredAt },
        )

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
