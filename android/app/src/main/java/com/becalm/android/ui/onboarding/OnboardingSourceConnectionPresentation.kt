package com.becalm.android.ui.onboarding

import com.becalm.android.R
import com.becalm.android.ui.components.SourceStatePresentation
import com.becalm.android.ui.components.StatusTone

internal fun sourceConnectionPresentationFor(state: SourceConnectionState): SourceStatePresentation =
    when (state) {
        SourceConnectionState.Idle -> SourceStatePresentation(
            labelRes = R.string.onb_sources_status_ready,
            tone = StatusTone.Neutral,
            recommendedCtaRes = R.string.action_connect,
            actionRequired = false,
            terminal = false,
        )
        SourceConnectionState.ConsentRequired -> SourceStatePresentation(
            labelRes = R.string.onb_sources_status_consent,
            tone = StatusTone.Attention,
            recommendedCtaRes = R.string.onb_sources_connect_with_consent,
            actionRequired = true,
            terminal = false,
        )
        SourceConnectionState.Connecting -> SourceStatePresentation(
            labelRes = R.string.onb_sources_status_connecting,
            tone = StatusTone.Progress,
            recommendedCtaRes = null,
            actionRequired = false,
            terminal = false,
        )
        SourceConnectionState.PendingExternalAuth -> SourceStatePresentation(
            labelRes = R.string.onb_sources_status_waiting,
            tone = StatusTone.Progress,
            recommendedCtaRes = null,
            actionRequired = false,
            terminal = false,
        )
        SourceConnectionState.Connected -> SourceStatePresentation(
            labelRes = R.string.onb_sources_status_connected,
            tone = StatusTone.Success,
            recommendedCtaRes = null,
            actionRequired = false,
            terminal = true,
        )
        SourceConnectionState.Skipped -> SourceStatePresentation(
            labelRes = R.string.onb_sources_status_skipped,
            tone = StatusTone.Muted,
            recommendedCtaRes = R.string.action_connect,
            actionRequired = false,
            terminal = true,
        )
        SourceConnectionState.Failed -> SourceStatePresentation(
            labelRes = R.string.onb_sources_status_failed,
            tone = StatusTone.Error,
            recommendedCtaRes = R.string.onb_sources_retry,
            actionRequired = true,
            terminal = false,
        )
    }
