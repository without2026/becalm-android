package com.becalm.android.ui.sources

import androidx.annotation.StringRes
import com.becalm.android.R
import com.becalm.android.data.local.db.dao.PersonEnrichmentSummary
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.ProcessingPhase
import com.becalm.android.data.repository.ProcessingSourceState
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.components.localizedProcessingStatusMessage
import com.becalm.android.ui.components.sourceStatusRecommendedCtaRes
import com.becalm.android.ui.components.sourceStatusRecoveryCopyRes
import com.becalm.android.ui.components.sourceSyncStatusFor

internal object SourcesListProjector {
    fun buildState(
        statuses: List<SourceStatus>,
        processingStates: List<ProcessingSourceState> = emptyList(),
        enrichmentSummary: PersonEnrichmentSummary,
        permissionGranted: Boolean,
    ): SourcesListUiState {
        val processingBySource = processingStates
            .filterNot { state -> state.phase == ProcessingPhase.IDLE }
            .associateBy { state -> state.sourceType }
        val mappedStatuses = statuses
            .filterNot { status -> status.sourceType == SourceType.MESSAGE_SCREENSHOT }
            .map { status ->
                val uiStatus = sourceSyncStatusFor(status.status)
                val processing = processingBySource[status.sourceType]
                SourceStatusRow(
                    sourceType = status.sourceType,
                    status = uiStatus,
                    lastSyncAt = status.lastSyncedAt,
                    hasError = status.errorMessage != null,
                    help = sourceStatusRecoveryCopyRes(uiStatus)?.let(UiMessage::resource),
                    recommendedActionLabelRes = sourceStatusRecommendedCtaRes(uiStatus),
                    processingLabelRes = processing?.phase?.sourceProcessingPhaseLabelRes(),
                    processingMessage = processing?.message.toProcessingStatusMessage(),
                    processingNeedsAction = processing?.phase.isActionNeeded(),
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

@StringRes
internal fun ProcessingPhase.sourceProcessingPhaseLabelRes(): Int = when (this) {
    ProcessingPhase.IDLE -> R.string.processing_phase_idle
    ProcessingPhase.SCANNING -> R.string.processing_phase_scanning
    ProcessingPhase.NEW_ITEMS -> R.string.processing_phase_new_items
    ProcessingPhase.AWAITING_CONFIRMATION -> R.string.processing_phase_audio_confirmation
    ProcessingPhase.GEMINI -> R.string.processing_phase_memory
    ProcessingPhase.UPLOADING -> R.string.processing_phase_uploading
    ProcessingPhase.NO_NEW_ITEMS -> R.string.processing_phase_no_new_items
    ProcessingPhase.SYNCED -> R.string.processing_phase_synced
    ProcessingPhase.BLOCKED,
    ProcessingPhase.ERROR,
    -> R.string.processing_phase_attention_needed
}

internal fun ProcessingPhase?.isActionNeeded(): Boolean =
    this == ProcessingPhase.AWAITING_CONFIRMATION ||
        this == ProcessingPhase.BLOCKED ||
        this == ProcessingPhase.ERROR

internal fun String?.toProcessingStatusMessage(): UiMessage? =
    localizedProcessingStatusMessage(this)

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
