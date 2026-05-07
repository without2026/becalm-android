package com.becalm.android.unit.ui.onboarding

import com.becalm.android.R
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.ui.onboarding.CalendarOAuthProvider
import com.becalm.android.ui.onboarding.OnboardingSourceProvider
import com.becalm.android.ui.onboarding.OnboardingStep
import com.becalm.android.ui.onboarding.SourceConnectionCopy
import com.becalm.android.ui.onboarding.SourceConnectionProjector
import com.becalm.android.ui.onboarding.SourceConnectionState
import com.becalm.android.ui.onboarding.SourceConnectionsEntryPoint
import com.becalm.android.ui.onboarding.StepStatus
import com.becalm.android.ui.onboarding.sourceConnectionPresentationFor
import com.becalm.android.ui.components.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingSourceConnectionProjectorSpecTest {

    @Test
    fun `source state respects terminal onboarding steps before transient state`() {
        val connected = SourceConnectionProjector.sourceStateFor(
            provider = OnboardingSourceProvider.GMAIL,
            stepStates = mapOf(OnboardingStep.LINK_GMAIL to StepStatus.COMPLETE),
            transientStates = mapOf(OnboardingSourceProvider.GMAIL to SourceConnectionState.Failed),
            respectStepStates = true,
            defaultState = SourceConnectionState.ConsentRequired,
        )
        val skipped = SourceConnectionProjector.sourceStateFor(
            provider = OnboardingSourceProvider.OUTLOOK_MAIL,
            stepStates = mapOf(OnboardingStep.LINK_OUTLOOK_MAIL to StepStatus.SKIPPED),
            transientStates = mapOf(OnboardingSourceProvider.OUTLOOK_MAIL to SourceConnectionState.Connecting),
            respectStepStates = true,
            defaultState = SourceConnectionState.ConsentRequired,
        )

        assertEquals(SourceConnectionState.Connected, connected)
        assertEquals(SourceConnectionState.Skipped, skipped)
    }

    @Test
    fun `settings source entry ignores old step states so sources can reconnect`() {
        val state = SourceConnectionProjector.sourceStateFor(
            provider = OnboardingSourceProvider.GMAIL,
            stepStates = mapOf(OnboardingStep.LINK_GMAIL to StepStatus.SKIPPED),
            transientStates = emptyMap(),
            respectStepStates = false,
            defaultState = SourceConnectionState.ConsentRequired,
        )

        assertEquals(SourceConnectionState.ConsentRequired, state)
    }

    @Test
    fun `projected source items keep mail consent and category defaults stable`() {
        val items = SourceConnectionProjector.sourceConnectionItems(
            stepStates = mapOf(OnboardingStep.LINK_GOOGLE_CALENDAR to StepStatus.COMPLETE),
            transientStates = mapOf(OnboardingSourceProvider.OUTLOOK_CALENDAR to SourceConnectionState.Failed),
            respectStepStates = true,
            stringFor = { resId -> "res:$resId" },
        )

        assertEquals(4, items.size)
        assertEquals(SourceConnectionState.ConsentRequired, items.first { it.provider == OnboardingSourceProvider.GMAIL }.state)
        assertEquals("res:${R.string.onb_sources_mail_consent_body}", items.first { it.provider == OnboardingSourceProvider.GMAIL }.consentCopy)
        assertEquals(SourceConnectionState.Connected, items.first { it.provider == OnboardingSourceProvider.GOOGLE_CALENDAR }.state)
        assertEquals(SourceConnectionState.Failed, items.first { it.provider == OnboardingSourceProvider.OUTLOOK_CALENDAR }.state)
    }

    @Test
    fun `provider errors resolve to localized string resources without allocating copy maps`() {
        assertEquals(
            R.string.onb_gmail_error_network,
            SourceConnectionProjector.emailErrorMessageRes(EmailPipaProvider.GMAIL, "network_error"),
        )
        assertEquals(
            R.string.onb_outlook_error_permission_denied,
            SourceConnectionProjector.emailErrorMessageRes(EmailPipaProvider.OUTLOOK_MAIL, "scope_denied"),
        )
        assertEquals(
            R.string.onb_gcal_error_unavailable,
            SourceConnectionProjector.calendarErrorMessageRes(CalendarOAuthProvider.GOOGLE_CALENDAR, "oauth_not_configured"),
        )
        assertEquals(
            R.string.onb_outlook_cal_error_unknown,
            SourceConnectionProjector.calendarErrorMessageRes(CalendarOAuthProvider.OUTLOOK_CALENDAR, "unknown"),
        )
    }

    @Test
    fun `entry point copy keeps setup onboarding and settings labels distinct`() {
        assertEquals(
            R.string.onb_setup_title,
            SourceConnectionCopy.copyFor(SourceConnectionsEntryPoint.Setup).titleRes,
        )
        assertEquals(
            R.string.onb_sources_title,
            SourceConnectionCopy.copyFor(SourceConnectionsEntryPoint.Onboarding).titleRes,
        )
        assertEquals(
            R.string.settings_source_connections_title,
            SourceConnectionCopy.copyFor(SourceConnectionsEntryPoint.Settings).titleRes,
        )
        assertEquals(
            R.string.onb_sources_skip_remaining,
            SourceConnectionCopy.continueLabelRes(SourceConnectionsEntryPoint.Onboarding, hasIncomplete = true),
        )
        assertEquals(
            R.string.onb_sources_continue,
            SourceConnectionCopy.continueLabelRes(SourceConnectionsEntryPoint.Onboarding, hasIncomplete = false),
        )
        assertEquals(
            R.string.settings_source_connections_not_now,
            SourceConnectionCopy.skipLabelRes(SourceConnectionsEntryPoint.Settings),
        )
    }

    @Test
    fun `onboarding source state presentation shares status contract`() {
        assertEquals(R.string.onb_sources_status_connected, sourceConnectionPresentationFor(SourceConnectionState.Connected).labelRes)
        assertEquals(StatusTone.Success, sourceConnectionPresentationFor(SourceConnectionState.Connected).tone)
        assertEquals(true, sourceConnectionPresentationFor(SourceConnectionState.Connected).terminal)

        assertEquals(R.string.onb_sources_status_failed, sourceConnectionPresentationFor(SourceConnectionState.Failed).labelRes)
        assertEquals(StatusTone.Error, sourceConnectionPresentationFor(SourceConnectionState.Failed).tone)
        assertEquals(R.string.onb_sources_retry, sourceConnectionPresentationFor(SourceConnectionState.Failed).recommendedCtaRes)
        assertEquals(true, sourceConnectionPresentationFor(SourceConnectionState.Failed).actionRequired)

        assertEquals(R.string.onb_sources_status_consent, sourceConnectionPresentationFor(SourceConnectionState.ConsentRequired).labelRes)
        assertEquals(StatusTone.Attention, sourceConnectionPresentationFor(SourceConnectionState.ConsentRequired).tone)
    }

    @Test
    fun `onboarding source state fixtures cover idle partial failed skipped and connected permutations`() {
        val allIdle = projectedStates(
            stepStates = emptyMap(),
            transientStates = emptyMap(),
        )
        assertEquals(SourceConnectionState.ConsentRequired, allIdle[OnboardingSourceProvider.GMAIL])
        assertEquals(SourceConnectionState.ConsentRequired, allIdle[OnboardingSourceProvider.OUTLOOK_MAIL])
        assertEquals(SourceConnectionState.Idle, allIdle[OnboardingSourceProvider.GOOGLE_CALENDAR])
        assertEquals(SourceConnectionState.Idle, allIdle[OnboardingSourceProvider.OUTLOOK_CALENDAR])
        assertTrue(allIdle.hasIncompleteSources())

        val partial = projectedStates(
            stepStates = mapOf(
                OnboardingStep.LINK_GMAIL to StepStatus.COMPLETE,
                OnboardingStep.LINK_GOOGLE_CALENDAR to StepStatus.SKIPPED,
            ),
            transientStates = mapOf(
                OnboardingSourceProvider.OUTLOOK_MAIL to SourceConnectionState.PendingExternalAuth,
                OnboardingSourceProvider.OUTLOOK_CALENDAR to SourceConnectionState.Failed,
            ),
        )
        assertEquals(SourceConnectionState.Connected, partial[OnboardingSourceProvider.GMAIL])
        assertEquals(SourceConnectionState.PendingExternalAuth, partial[OnboardingSourceProvider.OUTLOOK_MAIL])
        assertEquals(SourceConnectionState.Skipped, partial[OnboardingSourceProvider.GOOGLE_CALENDAR])
        assertEquals(SourceConnectionState.Failed, partial[OnboardingSourceProvider.OUTLOOK_CALENDAR])
        assertTrue(partial.hasIncompleteSources())

        val allSkipped = projectedStates(
            stepStates = OnboardingSourceProvider.entries.associate { it.step to StepStatus.SKIPPED },
            transientStates = emptyMap(),
        )
        assertEquals(
            OnboardingSourceProvider.entries.associateWith { SourceConnectionState.Skipped },
            allSkipped,
        )
        assertEquals(
            R.string.onb_sources_continue,
            SourceConnectionCopy.continueLabelRes(
                SourceConnectionsEntryPoint.Onboarding,
                hasIncomplete = allSkipped.hasIncompleteSources(),
            ),
        )

        val allConnected = projectedStates(
            stepStates = OnboardingSourceProvider.entries.associate { it.step to StepStatus.COMPLETE },
            transientStates = emptyMap(),
        )
        assertEquals(
            OnboardingSourceProvider.entries.associateWith { SourceConnectionState.Connected },
            allConnected,
        )
        assertEquals(
            R.string.onb_sources_continue,
            SourceConnectionCopy.continueLabelRes(
                SourceConnectionsEntryPoint.Onboarding,
                hasIncomplete = allConnected.hasIncompleteSources(),
            ),
        )
    }

    private fun projectedStates(
        stepStates: Map<OnboardingStep, StepStatus>,
        transientStates: Map<OnboardingSourceProvider, SourceConnectionState>,
    ): Map<OnboardingSourceProvider, SourceConnectionState> =
        SourceConnectionProjector.sourceConnectionItems(
            stepStates = stepStates,
            transientStates = transientStates,
            respectStepStates = true,
            stringFor = { resId -> "res:$resId" },
        ).associate { item -> item.provider to item.state }

    private fun Map<OnboardingSourceProvider, SourceConnectionState>.hasIncompleteSources(): Boolean =
        values.any { state ->
            state !in setOf(SourceConnectionState.Connected, SourceConnectionState.Skipped)
        }
}
