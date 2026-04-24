package com.becalm.android.ui.commitments

import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.commitment.CommitmentState

internal object CommitmentManagementProjector {
    private const val COUNTERPARTY_DISPLAY_MAX = 30

    fun buildUiState(
        current: CommitmentUiState,
        entities: List<CommitmentEntity>,
        enrichment: Map<String, PersonEnrichmentEntity>,
        filter: CommitmentFilter = current.filter,
        loading: Boolean = current.loading,
    ): CommitmentUiState {
        val rows = applyFilter(entities, filter, enrichment)
        return current.copy(
            filter = filter,
            items = rows,
            activeItems = rows.filterNot(::isTerminalRow),
            completedSection = buildSectionState(
                rows = rows,
                targetState = CommitmentState.COMPLETED,
                expanded = current.completedSection.expanded,
            ),
            cancelledSection = buildSectionState(
                rows = rows,
                targetState = CommitmentState.CANCELLED,
                expanded = current.cancelledSection.expanded,
            ),
            loading = loading,
        )
    }

    fun applyFilter(
        entities: List<CommitmentEntity>,
        filter: CommitmentFilter,
        enrichment: Map<String, PersonEnrichmentEntity>,
    ): List<CommitmentRow> {
        val actionEntities = entities.filter { it.itemType == CommitmentItemType.ACTION }
        val filtered = when (filter) {
            CommitmentFilter.ALL -> actionEntities
            CommitmentFilter.GIVE -> actionEntities.filter { it.direction == "give" }
            CommitmentFilter.TAKE -> actionEntities.filter { it.direction == "take" }
        }
        return filtered.map { entity -> entity.toRow(enrichment) }
    }

    private fun CommitmentEntity.toRow(
        enrichment: Map<String, PersonEnrichmentEntity>,
    ): CommitmentRow {
        val state = CommitmentState.fromWire(actionState)
        return CommitmentRow(
            id = id,
            title = title,
            direction = requireNotNull(direction) { "Action commitment rows must have direction" },
            derivedStatus = state.name,
            actionState = state,
            dueAt = dueAt,
            dueIsApproximate = dueIsApproximate,
            counterpartyDisplayName = resolveCounterpartyDisplay(this, enrichment),
            dueHint = dueHint,
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

    fun resolveCounterpartyDisplay(
        commitment: CommitmentEntity,
        enrichment: Map<String, PersonEnrichmentEntity>,
    ): String? {
        val ref = commitment.personRef
        return if (ref != null) {
            val hit = enrichment[ref]
            hit?.displayName ?: hit?.nickname ?: ref
        } else {
            commitment.counterpartyRaw?.take(COUNTERPARTY_DISPLAY_MAX)
        }
    }

    fun isTerminalRow(row: CommitmentRow): Boolean =
        row.actionState == CommitmentState.COMPLETED ||
            row.actionState == CommitmentState.CANCELLED
}
