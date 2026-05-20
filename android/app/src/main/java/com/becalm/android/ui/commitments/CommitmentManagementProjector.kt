package com.becalm.android.ui.commitments

import com.becalm.android.core.util.KST
import com.becalm.android.data.local.db.dao.CommitmentManagementRow
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.ScheduleEventLinkEntity
import com.becalm.android.data.local.db.entity.ScheduleEventLinkRelationType
import com.becalm.android.data.local.db.entity.ScheduleEventLinkResolutionChoice
import com.becalm.android.data.local.db.entity.ScheduleEventLinkStatus
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.commitment.CommitmentDisplayPolicy
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.ui.components.formatDayBadgeLabel
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
        val activeRows = projectedRows.filterNot(::isTerminalRow)
        val schedulePastRows = if (filter == CommitmentFilter.SCHEDULE) {
            projectedRows.filter { it.isPastSchedule(now) }
        } else {
            emptyList()
        }
        return current.copy(
            filter = filter,
            items = projectedRows,
            activeItems = activeRows,
            scheduleUpcomingItems = if (filter == CommitmentFilter.SCHEDULE) {
                projectedRows.filterNot { it.isPastSchedule(now) }
            } else {
                emptyList()
            },
            schedulePastSection = CommitmentSectionUiState(
                count = schedulePastRows.size,
                items = schedulePastRows,
                expanded = current.schedulePastSection.expanded,
                dimmed = true,
            ),
            confirmedSection = buildDueSectionState(
                rows = activeRows,
                bucket = CommitmentDueBucket.CONFIRMED,
                now = now,
                expanded = current.confirmedSection.expanded,
                dimmed = false,
            ),
            reviewSection = buildDueSectionState(
                rows = activeRows,
                bucket = CommitmentDueBucket.NEEDS_REVIEW,
                now = now,
                expanded = current.reviewSection.expanded,
                dimmed = false,
            ),
            pastSection = buildDueSectionState(
                rows = activeRows,
                bucket = CommitmentDueBucket.PAST,
                now = now,
                expanded = current.pastSection.expanded,
                dimmed = true,
            ),
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

    private fun CommitmentRow.isPastSchedule(now: Instant): Boolean {
        val due = dueAt ?: return false
        val today = now.toLocalDateTime(KST).date
        val dueDate = due.toLocalDateTime(KST).date
        return today.daysUntil(dueDate) < 0
    }

    fun applyFilter(
        rows: List<CommitmentManagementRow>,
        scheduleLinks: List<ScheduleEventLinkEntity> = emptyList(),
        filter: CommitmentFilter,
        now: Instant,
    ): List<CommitmentRow> {
        val linkedConfirmCommitmentIds = scheduleLinks
            .filter { it.isAbsorbedScheduleLink() }
            .mapNotNullTo(mutableSetOf()) { it.commitmentId }
        val rowsWithState = rows
            .filterNot { row ->
                CommitmentDisplayPolicy.shouldHideNonPersonLifecycleItem(
                    itemType = row.itemType,
                    title = row.title,
                    sourceTitle = row.sourceTitle,
                    counterpartyDisplayName = row.counterpartyDisplayName,
                )
            }
            .map { row ->
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
            .map { row -> row.toUiRow(now) }
    }

    private fun ScheduleEventLinkEntity.isAbsorbedScheduleLink(): Boolean {
        if (commitmentId == null || calendarEventId == null) return false
        if (status == ScheduleEventLinkStatus.AUTO_LINKED) {
            return relationType in setOf(ScheduleEventLinkRelationType.CONFIRMS, ScheduleEventLinkRelationType.ENRICHES)
        }
        return status == ScheduleEventLinkStatus.APPROVED &&
            resolutionChoice in setOf(
                ScheduleEventLinkResolutionChoice.SAME_SCHEDULE,
                ScheduleEventLinkResolutionChoice.CALENDAR,
                ScheduleEventLinkResolutionChoice.SOURCE,
            )
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

    private fun ProjectableCommitmentRow.toUiRow(now: Instant): CommitmentRow {
        return row.toUiRow(state, deEmphasized, now)
    }

    private fun CommitmentManagementRow.toUiRow(
        state: CommitmentState,
        deEmphasized: Boolean,
        now: Instant,
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
            dueAt = dueAt,
            dueIsApproximate = dueIsApproximate,
            counterpartyDisplayName = counterpartyDisplayName,
            sourceType = sourceType,
            sourceTitle = sourceTitle,
            sourceOccurredAt = sourceOccurredAt.takeUnless { sourceType == SourceType.MESSAGE_SCREENSHOT },
            dueHint = dueHint.takeIf { dueIsApproximate || dueAt == null },
            isManual = sourceType == SourceType.MANUAL,
            deEmphasized = deEmphasized,
            scheduleTimelineTiming = if (itemType == CommitmentItemType.SCHEDULE) {
                buildScheduleTimelineTiming(
                    dueAt = dueAt,
                    dueIsApproximate = dueIsApproximate,
                    now = now,
                )
            } else {
                null
            },
        )
    }

    private fun buildScheduleTimelineTiming(
        dueAt: Instant?,
        dueIsApproximate: Boolean,
        now: Instant,
    ): ScheduleTimelineTiming {
        val due = dueAt ?: return ScheduleTimelineTiming(
            dayLabel = null,
            timeLabel = null,
            isUntimed = true,
        )
        val today = now.toLocalDateTime(KST).date
        val dueDateTime = due.toLocalDateTime(KST)
        val dayDelta = today.daysUntil(dueDateTime.date)
        return ScheduleTimelineTiming(
            dayLabel = formatDayBadgeLabel(days = dayDelta, approximate = dueIsApproximate),
            timeLabel = if (dueIsApproximate) null else formatKstHourMinute(due),
            isUntimed = false,
        )
    }

    private fun formatKstHourMinute(instant: Instant): String {
        val ldt = instant.toLocalDateTime(KST)
        val hour = ldt.hour.toString().padStart(2, '0')
        val minute = ldt.minute.toString().padStart(2, '0')
        return "$hour:$minute"
    }

    fun buildDueSectionState(
        rows: List<CommitmentRow>,
        bucket: CommitmentDueBucket,
        now: Instant,
        expanded: Boolean,
        dimmed: Boolean,
    ): CommitmentSectionUiState {
        val sectionRows = rows.filter { it.dueBucket(now) == bucket }
        return CommitmentSectionUiState(
            count = sectionRows.size,
            items = sectionRows,
            expanded = expanded,
            dimmed = dimmed,
        )
    }

    private fun CommitmentRow.dueBucket(now: Instant): CommitmentDueBucket {
        if (dueIsApproximate || dueAt == null) return CommitmentDueBucket.NEEDS_REVIEW
        val today = now.toLocalDateTime(KST).date
        val dueDate = dueAt.toLocalDateTime(KST).date
        return if (today.daysUntil(dueDate) < -1) {
            CommitmentDueBucket.PAST
        } else {
            CommitmentDueBucket.CONFIRMED
        }
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

internal enum class CommitmentDueBucket {
    CONFIRMED,
    NEEDS_REVIEW,
    PAST,
}
