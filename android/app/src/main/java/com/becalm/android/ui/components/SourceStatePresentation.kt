package com.becalm.android.ui.components

import androidx.annotation.StringRes
import com.becalm.android.R
import com.becalm.android.data.repository.SourceConnectionStatus

/**
 * Represents the health of a data source's last synchronization.
 *
 * - [Connected]: source synced successfully and is fresh.
 * - [Syncing]: source is actively syncing.
 * - [Stale]: source has not synced recently; attention may be needed.
 * - [Error]: sync failed; user action required.
 * - [Disconnected]: source has not been connected.
 * - [Unknown]: status cannot be determined.
 */
public enum class SourceSyncStatus {
    Connected,
    Syncing,
    Stale,
    Error,
    Disconnected,
    Unknown,
}

internal data class SourceStatePresentation(
    @StringRes val labelRes: Int,
    val tone: StatusTone,
    @StringRes val recommendedCtaRes: Int?,
    @StringRes val recoveryCopyRes: Int? = null,
    val actionRequired: Boolean,
    val terminal: Boolean,
)

internal fun sourceSyncStatusFor(status: SourceConnectionStatus?): SourceSyncStatus =
    when (status) {
        SourceConnectionStatus.CONNECTED -> SourceSyncStatus.Connected
        SourceConnectionStatus.SYNCING -> SourceSyncStatus.Syncing
        SourceConnectionStatus.ERROR -> SourceSyncStatus.Error
        SourceConnectionStatus.NEVER_CONNECTED -> SourceSyncStatus.Disconnected
        null -> SourceSyncStatus.Unknown
    }

@StringRes
internal fun sourceStatusLabelRes(status: SourceSyncStatus): Int =
    sourceStatePresentationFor(status).labelRes

internal fun sourceStatePresentationFor(status: SourceSyncStatus): SourceStatePresentation =
    when (status) {
        SourceSyncStatus.Connected -> SourceStatePresentation(
            labelRes = R.string.sources_status_connected,
            tone = StatusTone.Success,
            recommendedCtaRes = null,
            recoveryCopyRes = R.string.sources_status_help_connected,
            actionRequired = false,
            terminal = true,
        )
        SourceSyncStatus.Syncing -> SourceStatePresentation(
            labelRes = R.string.sources_status_syncing,
            tone = StatusTone.Progress,
            recommendedCtaRes = null,
            recoveryCopyRes = R.string.sources_status_help_syncing,
            actionRequired = false,
            terminal = false,
        )
        SourceSyncStatus.Stale -> SourceStatePresentation(
            labelRes = R.string.sources_status_stale,
            tone = StatusTone.Attention,
            recommendedCtaRes = R.string.action_sync_now,
            recoveryCopyRes = R.string.sources_status_help_stale,
            actionRequired = true,
            terminal = false,
        )
        SourceSyncStatus.Error -> SourceStatePresentation(
            labelRes = R.string.sources_status_error,
            tone = StatusTone.Error,
            recommendedCtaRes = R.string.action_reconnect,
            recoveryCopyRes = R.string.sources_status_help_error,
            actionRequired = true,
            terminal = false,
        )
        SourceSyncStatus.Disconnected -> SourceStatePresentation(
            labelRes = R.string.sources_status_disconnected,
            tone = StatusTone.Muted,
            recommendedCtaRes = R.string.action_connect,
            recoveryCopyRes = R.string.sources_status_help_disconnected,
            actionRequired = false,
            terminal = true,
        )
        SourceSyncStatus.Unknown -> SourceStatePresentation(
            labelRes = R.string.sources_status_unknown,
            tone = StatusTone.Neutral,
            recommendedCtaRes = null,
            recoveryCopyRes = R.string.sources_status_help_unknown,
            actionRequired = false,
            terminal = false,
        )
    }

internal fun sourceStatusToneFor(status: SourceSyncStatus): StatusTone =
    sourceStatePresentationFor(status).tone

@StringRes
internal fun sourceStatusRecoveryCopyRes(status: SourceSyncStatus): Int? =
    sourceStatePresentationFor(status).recoveryCopyRes

@StringRes
internal fun sourceStatusRecommendedCtaRes(status: SourceSyncStatus): Int? =
    sourceStatePresentationFor(status).recommendedCtaRes
