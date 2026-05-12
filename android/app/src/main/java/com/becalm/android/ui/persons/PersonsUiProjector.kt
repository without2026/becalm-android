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
        val matchChoices = (peoplePage.rows + searchableContacts)
            .distinctBy(PersonListProjection::personId)
            .map(PersonsUiProjector::toPersonMatchChoiceRow)
        return PersonsUiState(
            query = query,
            people = rows,
            personSections = buildPersonSections(rows),
            matchChoices = matchChoices,
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

    private fun toPersonMatchChoiceRow(projection: PersonListProjection): PersonMatchChoiceRow {
        val displayName = projection.displayName
            ?: projection.nickname
            ?: projection.personId
        val detail = listOfNotNull(
            projection.nickname
                ?.takeUnless { it == displayName },
            projection.jobTitle,
            projection.companyName,
            projection.personId
                .takeUnless { it == displayName }
                ?.takeIf(::isDisplayablePersonAnchor),
        )
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .filterNot(::isInternalPersonAnchor)
            .firstOrNull()
        return PersonMatchChoiceRow(
            anchor = projection.personId,
            displayName = displayName,
            detail = detail,
            hasInteractions = projection.eventCount > 0,
            kind = if (projection.eventCount > 0) {
                PersonMatchChoiceKind.EXISTING_PERSON
            } else {
                PersonMatchChoiceKind.CONTACT
            },
        )
    }

    private fun isDisplayablePersonAnchor(anchor: String): Boolean =
        anchor.contains("@") ||
            anchor.startsWith("+") ||
            anchor.any(Char::isDigit) &&
            anchor.none { it == '-' }

    private fun isInternalPersonAnchor(value: String): Boolean =
        value.startsWith("qa-person-") ||
            value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
}
