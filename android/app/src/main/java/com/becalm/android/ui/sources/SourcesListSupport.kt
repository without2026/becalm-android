package com.becalm.android.ui.sources

import com.becalm.android.data.local.db.dao.PersonEnrichmentSummary
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.components.sourceStatusRecommendedCtaRes
import com.becalm.android.ui.components.sourceStatusRecoveryCopyRes
import com.becalm.android.ui.components.sourceSyncStatusFor

internal object SourcesListProjector {
    fun buildState(
        statuses: List<SourceStatus>,
        enrichmentSummary: PersonEnrichmentSummary,
        permissionGranted: Boolean,
    ): SourcesListUiState {
        val mappedStatuses = statuses.map { status ->
            val uiStatus = sourceSyncStatusFor(status.status)
            SourceStatusRow(
                sourceType = status.sourceType,
                status = uiStatus,
                lastSyncAt = status.lastSyncedAt,
                hasError = status.errorMessage != null,
                help = sourceStatusRecoveryCopyRes(uiStatus)?.let(UiMessage::resource),
                recommendedActionLabelRes = sourceStatusRecommendedCtaRes(uiStatus),
            )
        }
        val contactsStatus = if (permissionGranted) {
            SourceSyncStatus.Connected
        } else {
            SourceSyncStatus.Disconnected
        }
        val contactsRow = SourceStatusRow(
            sourceType = CONTACTS_SOURCE_TYPE,
            status = contactsStatus,
            lastSyncAt = enrichmentSummary.lastSyncedAt,
            hasError = false,
            enrichedCount = enrichmentSummary.count,
            help = sourceStatusRecoveryCopyRes(contactsStatus)?.let(UiMessage::resource),
            recommendedActionLabelRes = sourceStatusRecommendedCtaRes(contactsStatus),
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
