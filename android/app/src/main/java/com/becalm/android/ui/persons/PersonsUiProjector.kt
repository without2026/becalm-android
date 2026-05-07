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
        searchableContacts: List<PersonListProjection> = emptyList(),
        unassigned: List<UnassignedEventSummary>,
        offlineStatus: PersonsOfflineStatus,
        pageSize: Int,
    ): PersonsUiState {
        val sourceRows = if (query.isBlank()) {
            peoplePage.rows
        } else {
            (peoplePage.rows + searchableContacts).distinctBy(PersonListProjection::personId)
        }
        val rows = sourceRows
            .filter { projection ->
                query.isBlank() ||
                    projection.displayName?.contains(query, ignoreCase = true) == true ||
                    projection.nickname?.contains(query, ignoreCase = true) == true ||
                    projection.personId.contains(query, ignoreCase = true)
            }
            .map(PersonsUiProjector::toPersonRow)
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
        personId = projection.personId,
        displayName = projection.displayName,
        nickname = projection.nickname,
        companyName = projection.companyName,
        jobTitle = projection.jobTitle,
        lastInteractionAt = projection.lastInteractionAt,
        interactionCount = projection.eventCount,
        pendingCommitmentCount = projection.pendingCommitmentCount,
        channelSources = projection.channelSources,
        lastInteractionSnippet = null,
    )
}
