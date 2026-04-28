package com.becalm.android.ui.persons

internal object PersonsUiProjector {

    fun unauthenticatedState(
        query: String,
        offlineStatus: PersonsOfflineStatus,
        pageSize: Int,
    ): PersonsUiState = PersonsUiState(
        query = query,
        people = emptyList(),
        unassignedEvents = emptyList(),
        showOfflineBadge = offlineStatus.isOffline && offlineStatus.lastSyncAt != null,
        offlineLastSyncAt = offlineStatus.lastSyncAt,
        sortOrder = PersonsSortOrder.MOST_RECENT_EVENT_DESC,
        pageSize = pageSize,
        hasMorePages = false,
        nextCursor = null,
        loading = false,
    )

    fun authenticatedState(
        query: String,
        peoplePage: PersonsListPageProjection,
        unassigned: List<UnassignedEventSummary>,
        offlineStatus: PersonsOfflineStatus,
        pageSize: Int,
    ): PersonsUiState {
        val rows = peoplePage.rows
            .map(PersonsUiProjector::toPersonRow)
            .filter { row ->
                query.isBlank() ||
                    row.displayName?.contains(query, ignoreCase = true) == true ||
                    row.nickname?.contains(query, ignoreCase = true) == true ||
                    row.displayLabel.contains(query, ignoreCase = true) ||
                    row.personRef.contains(query, ignoreCase = true)
            }
        return PersonsUiState(
            query = query,
            people = rows,
            personSections = buildPersonSections(rows),
            unassignedEvents = unassigned,
            showOfflineBadge = offlineStatus.isOffline && offlineStatus.lastSyncAt != null,
            offlineLastSyncAt = offlineStatus.lastSyncAt,
            sortOrder = peoplePage.sortOrder,
            pageSize = pageSize,
            hasMorePages = peoplePage.hasMorePages,
            nextCursor = peoplePage.nextCursor,
            loading = false,
        )
    }

    private fun toPersonRow(projection: PersonListProjection): PersonRow = PersonRow(
        personRef = projection.personRef,
        displayName = projection.displayName,
        nickname = projection.nickname,
        companyName = projection.companyName,
        jobTitle = projection.jobTitle,
        lastInteractionAt = projection.lastInteractionAt,
        interactionCount = projection.eventCount,
        pendingCommitmentCount = projection.pendingCommitmentCount,
        channelSources = projection.channelSources,
        lastInteractionSnippet = projection.lastInteractionSnippet,
    )
}
