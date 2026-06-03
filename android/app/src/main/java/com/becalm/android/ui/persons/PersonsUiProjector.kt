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
        displayName = sanitizeDisplayName(projection.displayName),
        nickname = projection.nickname,
        companyName = projection.companyName,
        jobTitle = projection.jobTitle,
        lastInteractionAt = projection.lastInteractionAt,
        interactionCount = projection.eventCount,
        pendingCommitmentCount = projection.pendingCommitmentCount,
        channelSources = projection.channelSources,
        lastInteractionSnippet = projection.topAction?.title,
        topAction = projection.topAction,
    )

    private fun toPersonMatchChoiceRow(projection: PersonListProjection): PersonMatchChoiceRow {
        val displayName = listOfNotNull(
            sanitizeDisplayName(projection.displayName),
            sanitizeDisplayName(projection.nickname),
        ).firstOrNull()
            ?: UNKNOWN_PERSON_DISPLAY_NAME
        val detail = listOfNotNull(
            projection.jobTitle,
            projection.companyName,
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

    private fun isInternalPersonAnchor(value: String): Boolean =
        value.startsWith("qa-person-") ||
            value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))

    private fun sanitizeDisplayName(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (value.contains("@")) return null
        if (value.startsWith("+")) return null
        return if (value.all { it.isDigit() || it == '-' || it == ' ' }) {
            null
        } else {
            value
        }
    }

    private const val UNKNOWN_PERSON_DISPLAY_NAME = "아직 이름을 모르는 연락처"
}
