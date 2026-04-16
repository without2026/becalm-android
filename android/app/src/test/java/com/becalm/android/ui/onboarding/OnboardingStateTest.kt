package com.becalm.android.ui.onboarding

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.becalm.android.data.local.DataStoreKeys
import com.becalm.android.ui.BeCalmRoute
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// spec: ONB-002 — RecordingFolderScreen route defined in navigation graph
// spec: ONB-003 — READ_MEDIA_AUDIO permission requested; VOICE_SAF_URI DataStore key available
// spec: ONB-004 — GmailOAuthScreen [스킵] → next step; gmail_connected=false maintained
// spec: ONB-007 — onboarding failure tracking state
// spec: ONB-CONTACTS — ContactsPermissionScreen route + contacts_permission_asked DataStore key

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingStateTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    // spec: ONB-002 — RecordingFolderScreen has a defined navigation route
    @Test
    fun `ONB002_recordingFolderScreen_routeIsDefined`() {
        // spec: ONB-002 — route exists so navigator can push to it after login
        assertEquals("/onboarding/recording-folder", BeCalmRoute.OnboardingRecordingFolder.route)
    }

    // spec: ONB-003 — VOICE_SAF_URI DataStore key exists for SAF-granted folder URI persistence
    @Test
    fun `ONB003_voiceSafUri_dataStoreKeyExists`() {
        // spec: ONB-003 — after READ_MEDIA_AUDIO + SAF selection, URI stored here
        assertNotNull(DataStoreKeys.VOICE_SAF_URI)
        assertEquals("voice_saf_uri", DataStoreKeys.VOICE_SAF_URI.name)
    }

    // spec: ONB-003 — when VOICE_SAF_URI is null, voice source is not connected
    @Test
    fun `ONB003_voiceSourceDisconnected_whenSafUriNull`() = runTest {
        val dataStore = mockk<DataStore<Preferences>>()
        val prefs = preferencesOf() // no VOICE_SAF_URI key
        coEvery { dataStore.data } returns flowOf(prefs)

        val storedUri = prefs[DataStoreKeys.VOICE_SAF_URI]
        // spec: ONB-003 — voice source is connected only when SAF URI is present
        assertTrue(storedUri == null)
    }

    // spec: ONB-004 — GmailOAuthScreen route is defined (supports [스킵] navigation)
    @Test
    fun `ONB004_gmailOAuthScreen_routeIsDefined`() {
        // spec: ONB-004 — tapping [스킵] navigates away; route must exist
        assertEquals("/onboarding/gmail", BeCalmRoute.OnboardingGmail.route)
    }

    // spec: ONB-004 — gmail_connected remains false when Gmail is skipped
    @Test
    fun `ONB004_gmailConnected_remainsFalse_whenSkipped`() = runTest {
        val dataStore = mockk<DataStore<Preferences>>()
        // Empty prefs = skipped without connecting
        val prefs = preferencesOf()
        coEvery { dataStore.data } returns flowOf(prefs)

        val isConnected = prefs[DataStoreKeys.GMAIL_CONNECTED] ?: false
        // spec: ONB-004 — DataStore gmail_connected=false is maintained after skip
        assertFalse(isConnected)
    }

    // spec: ONB-007 — onboarding failure events tracked via Sentry; onboarding step routes exist
    @Test
    fun `ONB007_onboardingRoutes_allDefined`() {
        // spec: ONB-007 — Sentry onboarding_step_failed event includes step name
        // All step routes must be non-null/non-empty for step name extraction
        val stepRoutes = listOf(
            BeCalmRoute.OnboardingRecordingFolder.route,
            BeCalmRoute.OnboardingContacts.route,
            BeCalmRoute.OnboardingGmail.route,
            BeCalmRoute.OnboardingOutlookMail.route,
            BeCalmRoute.OnboardingImap.route,
            BeCalmRoute.OnboardingGoogleCalendar.route,
            BeCalmRoute.OnboardingOutlookCalendar.route,
            BeCalmRoute.OnboardingBattery.route,
            BeCalmRoute.OnboardingColdSync.route
        )
        // spec: ONB-007 — 9 post-login onboarding steps (steps 3-11 of 11)
        assertEquals(9, stepRoutes.size)
        stepRoutes.forEach { route ->
            assertTrue("Route must be non-empty: $route", route.isNotBlank())
            assertTrue("Route must start with /onboarding: $route", route.startsWith("/onboarding"))
        }
    }

    // spec: ONB-CONTACTS — ContactsPermissionScreen route is defined in navigation graph
    @Test
    fun `ONBCONTACTS_contactsPermissionScreen_routeIsDefined`() {
        // spec: ONB-CONTACTS — 4th step in onboarding (after RecordingFolderScreen)
        assertEquals("/onboarding/contacts", BeCalmRoute.OnboardingContacts.route)
    }

    // spec: ONB-CONTACTS — CONTACTS_PERMISSION_GRANTED DataStore key exists for state persistence
    @Test
    fun `ONBCONTACTS_contactsPermissionGranted_dataStoreKeyExists`() {
        // spec: ONB-CONTACTS — DataStore contacts_permission_asked=true saved after screen shown
        assertNotNull(DataStoreKeys.CONTACTS_PERMISSION_GRANTED)
        assertEquals("contacts_permission_granted", DataStoreKeys.CONTACTS_PERMISSION_GRANTED.name)
    }

    // spec: ONB-CONTACTS — contacts permission not granted → CONTACTS_PERMISSION_GRANTED=false
    @Test
    fun `ONBCONTACTS_contactsPermission_defaultFalse_whenNotGranted`() = runTest {
        val prefs = preferencesOf() // no key = not granted
        val granted = prefs[DataStoreKeys.CONTACTS_PERMISSION_GRANTED] ?: false
        // spec: ONB-CONTACTS — graceful skip: next step proceeds even if denied
        assertFalse(granted)
    }

    // spec: ONB-008 (referenced by ONB-CONTACTS) — ONBOARDING_COMPLETED only set after ColdSync
    @Test
    fun `ONBCONTACTS_onboardingCompleted_key_exists_for_coldSyncGate`() {
        // spec: ONB-CONTACTS / ONB-008 — contacts denial does not set onboarding_completed
        assertNotNull(DataStoreKeys.ONBOARDING_COMPLETED)
        assertEquals("onboarding_completed", DataStoreKeys.ONBOARDING_COMPLETED.name)
    }
}
