package com.becalm.android.ui.sources

import com.becalm.android.data.local.db.dao.PersonEnrichmentSummary
import com.becalm.android.data.repository.SourceStatus

internal object SourcesListProjector {
    fun buildState(
        statuses: List<SourceStatus>,
        enrichmentSummary: PersonEnrichmentSummary,
        permissionGranted: Boolean,
    ): SourcesListUiState {
        val mappedStatuses = statuses.map { status ->
            SourceStatusRow(
                sourceType = status.sourceType,
                status = status.status.name,
                lastSyncAt = status.lastSyncedAt,
                lastError = status.errorMessage,
            )
        }
        val contactsRow = SourceStatusRow(
            sourceType = CONTACTS_SOURCE_TYPE,
            status = if (permissionGranted) "CONNECTED" else "DISCONNECTED",
            lastSyncAt = enrichmentSummary.lastSyncedAt,
            lastError = null,
            enrichedCount = enrichmentSummary.count,
        )
        return SourcesListUiState(items = listOf(contactsRow) + mappedStatuses)
    }
}

internal object SourcesListNavigationResolver {
    fun resolve(
        sourceType: String,
        contactsPermissionGranted: Boolean,
    ): SourcesListNavigation =
        if (sourceType == CONTACTS_SOURCE_TYPE) {
            if (contactsPermissionGranted) {
                SourcesListNavigation.ContactsDetail
            } else {
                SourcesListNavigation.ContactsPermission
            }
        } else {
            SourcesListNavigation.SourceDetail(sourceType)
        }
}

internal const val CONTACTS_SOURCE_TYPE: String = "contacts"
