package com.becalm.android.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression test for AUTH-008 (`.spec/auth.spec.yml:73`): DataStore values for
 * onboarding completion, per-provider PIPA consent, and `<provider>_connected` must
 * be isolated per signed-in user so a different account on the same device never
 * inherits the prior user's session state.
 *
 * Scenario:
 *  1. User A signs in, completes onboarding, grants email PIPA consent, connects
 *     Gmail / IMAP, grants voice consent.
 *  2. User A signs out (routine invalidateSession path — current_user_id cleared,
 *     user-scoped keys preserved on disk per AUTH-005).
 *  3. User B signs in (current_user_id set to B's UUID).
 *  4. Every user-scoped flag reads as its default for B even though the same
 *     DataStore file still holds A's persisted values.
 *
 * Uses real [PreferenceDataStoreFactory] against a [TemporaryFolder]-scoped file so
 * the namespace derivation is exercised end-to-end rather than mocked away.
 * [runBlocking] drives the test on an actual dispatcher — the default `runTest`
 * scheduler deadlocks against DataStore's internal IO dispatcher.
 */
public class UserPrefsStoreAuth008Test {

    @get:Rule
    public val tempFolder: TemporaryFolder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: UserPrefsStore

    private val userA = "user-a-uuid"
    private val userB = "user-b-uuid"

    @Before
    public fun setUp() {
        val file = File(tempFolder.newFolder(), "becalm_user_prefs.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        store = UserPrefsStoreImpl(dataStore)
    }

    @After
    public fun tearDown() {
        scope.cancel()
    }

    @Test
    public fun userScopedFlags_isolateOnboardingBetweenAccounts(): Unit = runBlocking {
        store.setCurrentUserId(userA)
        store.setOnboardingCompleted(true)
        assertTrue(
            "A's own session must observe onboarding_completed=true",
            store.observeOnboardingCompleted().first(),
        )

        store.setCurrentUserId(null) // routine sign-out
        store.setCurrentUserId(userB)
        assertFalse(
            "B must not inherit A's onboarding_completed flag",
            store.observeOnboardingCompleted().first(),
        )

        // And A can re-sign-in and see their preserved value — AUTH-005.
        store.setCurrentUserId(userA)
        assertTrue(
            "A's onboarding_completed must be preserved for re-sign-in",
            store.observeOnboardingCompleted().first(),
        )
    }

    @Test
    public fun userScopedFlags_isolateEmailConsentBetweenAccounts(): Unit = runBlocking {
        store.setCurrentUserId(userA)
        store.setEmailPipaConsent(EmailPipaProvider.GMAIL, granted = true)
        store.setEmailPipaConsent(EmailPipaProvider.NAVER_IMAP, granted = true)

        store.setCurrentUserId(null)
        store.setCurrentUserId(userB)

        for (provider in EmailPipaProvider.entries) {
            assertFalse(
                "B must not inherit A's ${provider.storageKey} PIPA consent",
                store.observeEmailPipaConsent(provider).first(),
            )
        }
    }

    @Test
    public fun userScopedFlags_isolateSourceConnectedBetweenAccounts(): Unit = runBlocking {
        store.setCurrentUserId(userA)
        store.setEmailSourceConnected(EmailPipaProvider.GMAIL, connected = true)
        store.setEmailSourceConnected(EmailPipaProvider.DAUM_IMAP, connected = true)

        store.setCurrentUserId(null)
        store.setCurrentUserId(userB)

        for (provider in EmailPipaProvider.entries) {
            assertFalse(
                "B must not inherit A's ${provider.storageKey} connected flag",
                store.observeEmailSourceConnected(provider).first(),
            )
        }
    }

    @Test
    public fun userScopedFlags_isolateVoicePipaConsentBetweenAccounts(): Unit = runBlocking {
        store.setCurrentUserId(userA)
        store.setThirdPartyProvisionConsent(granted = true)

        store.setCurrentUserId(null)
        store.setCurrentUserId(userB)

        assertFalse(
            "B must not inherit A's voice pipa_third_party_consent flag",
            store.observeThirdPartyProvisionConsent().first(),
        )
    }
}
