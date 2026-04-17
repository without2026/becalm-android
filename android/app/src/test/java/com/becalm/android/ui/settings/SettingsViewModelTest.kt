package com.becalm.android.ui.settings

import app.cash.turbine.test
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    private lateinit var viewModel: SettingsViewModel

    private val fakeSession = SupabaseSession(
        accessToken = "access",
        refreshToken = "refresh",
        userId = "user-1",
        email = "test@becalm.app",
        expiresAt = Clock.System.now() + 1.hours,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Default: signed in with a known email.
        coEvery { authRepository.currentSession() } returns fakeSession

        // Default: locale tag "ko", notifications enabled.
        every { userPrefsStore.observeLocaleTag() } returns flowOf("ko")
        every { userPrefsStore.observeNotificationsEnabled() } returns flowOf(true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): SettingsViewModel = SettingsViewModel(
        userPrefsStore = userPrefsStore,
        authRepository = authRepository,
        logger = logger,
    )

    // ── Test 1: load settings ─────────────────────────────────────────────────

    /**
     * On init, SettingsViewModel populates userEmail and language from session and
     * UserPrefsStore respectively, then clears the loading flag.
     */
    @Test
    fun `loadSettings populates email and language and clears loading`() = runTest {
        viewModel = buildViewModel()

        viewModel.uiState.test {
            awaitItem() // loading=true initial

            val settled = awaitItem()
            assertFalse(settled.loading)
            assertEquals("test@becalm.app", settled.userEmail)
            assertEquals("ko", settled.language)
            assertNull(settled.error)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Test 2: toggle notifications calls userPrefsStore ─────────────────────

    /**
     * onToggleNotifications(true/false) calls userPrefsStore.setNotificationsEnabled
     * and reflects the change in uiState. Covers R8/H-6.
     */
    @Test
    fun `onToggleNotifications calls userPrefsStore setNotificationsEnabled and updates state`() = runTest {
        viewModel = buildViewModel()

        viewModel.uiState.test {
            awaitItem() // loading=true
            val settled = awaitItem()
            assertTrue(settled.notificationsEnabled) // default true

            viewModel.onToggleNotifications(false)
            val afterDisable = awaitItem()
            assertFalse(afterDisable.notificationsEnabled)

            viewModel.onToggleNotifications(true)
            val afterEnable = awaitItem()
            assertTrue(afterEnable.notificationsEnabled)

            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { userPrefsStore.setNotificationsEnabled(false) }
        coVerify(exactly = 1) { userPrefsStore.setNotificationsEnabled(true) }
    }

    // ── Test 3: onWipeLocalData delegates to authRepository.signOut ───────────

    /**
     * onWipeLocalData calls authRepository.signOut (PIPA wipe, R8 spec).
     * On success loading clears and error is null.
     * On failure error is surfaced.
     */
    @Test
    fun `onWipeLocalData calls authRepository signOut and surfaces result`() = runTest {
        viewModel = buildViewModel()

        // Success path
        coEvery { authRepository.signOut() } returns BecalmResult.Success(Unit)

        viewModel.uiState.test {
            awaitItem() // loading=true
            skipItems(1) // settled

            viewModel.onWipeLocalData()
            val loading = awaitItem()
            assertTrue(loading.loading)

            val afterWipe = awaitItem()
            assertFalse(afterWipe.loading)
            assertNull(afterWipe.error)

            coVerify(atLeast = 1) { authRepository.signOut() }

            // Failure path
            coEvery {
                authRepository.signOut()
            } returns BecalmResult.Failure(BecalmError.Io("disk full"))

            viewModel.onWipeLocalData()
            skipItems(1) // loading=true

            val afterFailure = awaitItem()
            assertFalse(afterFailure.loading)
            assertNotNull(afterFailure.error)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Test 4: onErrorDismissed clears error (R8/H-6) ────────────────────────

    @Test
    fun `onErrorDismissed clears error`() = runTest {
        // Simulate a failure to inject an error state.
        coEvery { authRepository.signOut() } returns BecalmResult.Failure(BecalmError.Io("fail"))

        viewModel = buildViewModel()

        viewModel.uiState.test {
            awaitItem() // loading=true
            skipItems(1) // settled

            viewModel.onWipeLocalData()
            skipItems(1) // loading=true
            val withError = awaitItem()
            assertNotNull(withError.error)

            viewModel.onErrorDismissed()
            val afterDismiss = awaitItem()
            assertNull(afterDismiss.error)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
