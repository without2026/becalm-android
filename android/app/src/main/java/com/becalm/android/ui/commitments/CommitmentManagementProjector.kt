package com.becalm.android.ui.commitments

import com.becalm.android.core.util.KST
import com.becalm.android.data.local.db.dao.CommitmentManagementRow
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.ScheduleEventLinkEntity
import com.becalm.android.data.local.db.entity.ScheduleEventLinkRelationType
import com.becalm.android.data.local.db.entity.ScheduleEventLinkStatus
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.commitment.CommitmentDisplayPolicy
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.ui.components.isGiveDirection
import com.becalm.android.ui.components.isTakeDirection
import kotlinx.datetime.Instant
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

internal object CommitmentManagementProjector {
    fun buildUiState(
        current: CommitmentUiState,
        rows: List<CommitmentManagementRow>,
        scheduleLinks: List<ScheduleEventLinkEntity> = emptyList(),
        filter: CommitmentFilter = current.filter,
        loading: Boolean = current.loading,
        now: Instant,
    ): CommitmentUiState {
        val projectedRows = applyFilter(rows, scheduleLinks, filter, now)
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
        scheduleLinks: List<ScheduleEventLinkEntity> = emptyList(),
        filter: CommitmentFilter,
        now: Instant,
    ): List<CommitmentRow> {
        val linkedConfirmCommitmentIds = scheduleLinks
            .filter {
                it.relationType == ScheduleEventLinkRelationType.CONFIRMS &&
                    it.status in setOf(ScheduleEventLinkStatus.AUTO_LINKED, ScheduleEventLinkStatus.APPROVED)
            }
            .mapNotNullTo(mutableSetOf()) { it.commitmentId }
        val rowsWithState = rows.map { row ->
            ProjectableCommitmentRow(
                row = row,
                state = CommitmentState.fromWire(row.actionState),
                deEmphasized = row.id in linkedConfirmCommitmentIds,
            )
        }
        val filtered = when (filter) {
            CommitmentFilter.ALL -> rowsWithState.filter {
                CommitmentDisplayPolicy.isPrimaryFeedItem(it.row.itemType)
            }
            CommitmentFilter.GIVE -> rowsWithState.filter {
                it.row.itemType == CommitmentItemType.ACTION &&
                    isGiveDirection(it.row.direction) &&
                    !it.state.isClosed()
            }
            CommitmentFilter.TAKE -> rowsWithState.filter {
                it.row.itemType == CommitmentItemType.ACTION &&
                    isTakeDirection(it.row.direction) &&
                    !it.state.isClosed()
            }
            CommitmentFilter.SCHEDULE -> rowsWithState.filter { it.row.itemType == CommitmentItemType.SCHEDULE }
            CommitmentFilter.CLOSED -> rowsWithState.filter {
                it.row.itemType == CommitmentItemType.ACTION && it.state.isClosed()
            }
        }
        return filtered
            .sortedForDisplay(now)
            .map { row -> row.toUiRow() }
    }

    private data class ProjectableCommitmentRow(
        val row: CommitmentManagementRow,
        val state: CommitmentState,
        val deEmphasized: Boolean,
    )

    private fun List<ProjectableCommitmentRow>.sortedForDisplay(now: Instant): List<ProjectableCommitmentRow> =
        sortedWith(
            compareBy<ProjectableCommitmentRow> { it.row.exactDueSortGroup(now) }
                .thenBy { it.row.exactDueDistance(now) }
                .thenByDescending { item -> item.row.dueAt?.takeUnless { item.row.dueIsApproximate } }
                .thenByDescending { it.row.sourceOccurredAt },
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

    private fun ProjectableCommitmentRow.toUiRow(): CommitmentRow {
        val exactDueAt = row.dueAt?.takeUnless { row.dueIsApproximate }
        return row.toUiRow(state, exactDueAt, deEmphasized)
    }

    private fun CommitmentManagementRow.toUiRow(
        state: CommitmentState,
        exactDueAt: Instant?,
        deEmphasized: Boolean,
    ): CommitmentRow {
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
            deEmphasized = deEmphasized,
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
            row.actionState.isClosed()

    private fun CommitmentState.isClosed(): Boolean =
        this == CommitmentState.COMPLETED || this == CommitmentState.CANCELLED
}
