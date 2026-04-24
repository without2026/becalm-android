package com.becalm.android.unit.ui.settings

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.ui.settings.SettingsViewModel
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelSpecTest {

    private val testDispatcher = StandardTestDispatcher()
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val rawIngestionRepository: RawIngestionRepository = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { authRepository.currentSession() } returns fakeSession
        every { userPrefsStore.observeLocaleTag() } returns flowOf("ko")
        every { userPrefsStore.observeNotificationsEnabled() } returns flowOf(true)
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(false)
        every { userPrefsStore.observeProcessingPaused() } returns flowOf(false)
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init load falls back per preference and preserves signed-in email`() = runTest {
        every { userPrefsStore.observeLocaleTag() } returns flow {
            throw IllegalStateException("locale broken")
        }

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals("user@example.com", state.userEmail)
        assertEquals("", state.language)
        assertTrue(state.notificationsEnabled)
        assertFalse(state.pipaConsentEnabled)
        assertNull(state.error)
    }

    @Test
    fun `change language to system stores null and updates ui state`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onChangeLanguage("")
        advanceUntilIdle()

        coVerify(exactly = 1) { userPrefsStore.setLocaleTag(null) }
        assertEquals("", viewModel.uiState.value.language)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `VOI-004 granting consent releases awaiting voice rows and reenqueues only valid sources`() = runTest {
        coEvery { rawIngestionRepository.releaseAwaitingConsentVoiceAndReturnIds("user-1") } returns
            BecalmResult.Success(listOf("raw-1", "raw-2", "raw-3"))
        coEvery { rawIngestionRepository.findById("raw-1", "user-1") } returns
            rawEvent(id = "raw-1", sourceRef = "content://voice/1")
        coEvery { rawIngestionRepository.findById("raw-2", "user-1") } returns
            rawEvent(id = "raw-2", sourceRef = null)
        coEvery { rawIngestionRepository.findById("raw-3", "user-1") } returns null

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.onTogglePipaConsent(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { userPrefsStore.setThirdPartyProvisionConsent(true) }
        coVerify(exactly = 1) { rawIngestionRepository.releaseAwaitingConsentVoiceAndReturnIds("user-1") }
        coVerify(exactly = 0) { rawIngestionRepository.parkAndCancelPendingVoice(any()) }
        verify(exactly = 1) {
            workScheduler.enqueueVoiceUpload(rawEventId = "raw-1", audioUri = "content://voice/1")
        }
        verify(exactly = 0) {
            workScheduler.enqueueVoiceUpload(rawEventId = "raw-2", audioUri = any())
        }
        verify(exactly = 0) { workScheduler.cancelVoiceUpload(any()) }
        assertTrue(viewModel.uiState.value.pipaConsentEnabled)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `PIPA-003 and VOI-004 withdrawing consent parks pending voice rows and cancels uploads`() = runTest {
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        coEvery { rawIngestionRepository.parkAndCancelPendingVoice("user-1") } returns
            BecalmResult.Success(listOf("raw-1", "raw-2"))

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.onTogglePipaConsent(false)
        advanceUntilIdle()

        coVerify(exactly = 1) { userPrefsStore.setThirdPartyProvisionConsent(false) }
        coVerify(exactly = 1) { rawIngestionRepository.parkAndCancelPendingVoice("user-1") }
        coVerify(exactly = 0) { rawIngestionRepository.releaseAwaitingConsentVoiceAndReturnIds(any()) }
        verify(exactly = 1) { workScheduler.cancelVoiceUpload("raw-1") }
        verify(exactly = 1) { workScheduler.cancelVoiceUpload("raw-2") }
        verify(exactly = 0) { workScheduler.enqueueVoiceUpload(any(), any()) }
        assertFalse(viewModel.uiState.value.pipaConsentEnabled)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `PIPA toggle write failure reverts optimistic state and surfaces message`() = runTest {
        coEvery { userPrefsStore.setThirdPartyProvisionConsent(true) } throws
            IllegalStateException("write failed")

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.onTogglePipaConsent(true)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.pipaConsentEnabled)
        assertEquals("write failed", viewModel.uiState.value.error)
    }

    @Test
    fun `PIPA-006 sign out invalidates session without wiping local data or prefs`() = runTest {
        coEvery { authRepository.invalidateSession() } returns BecalmResult.Success(Unit)

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.onSignOut()
        advanceUntilIdle()

        coVerify(exactly = 1) { authRepository.invalidateSession() }
        coVerify(exactly = 0) { rawIngestionRepository.deleteAllForUser(any()) }
        coVerify(exactly = 0) { userPrefsStore.clearAll() }
        verify(exactly = 0) { workScheduler.cancelAll() }
        assertTrue(viewModel.uiState.value.signedOut)
        assertFalse(viewModel.uiState.value.loading)
    }

    private fun buildViewModel(): SettingsViewModel = SettingsViewModel(
        userPrefsStore = userPrefsStore,
        authRepository = authRepository,
        rawIngestionRepository = rawIngestionRepository,
        workScheduler = workScheduler,
        logger = logger,
    )

    private fun rawEvent(
        id: String,
        sourceRef: String?,
    ): RawIngestionEventEntity = RawIngestionEventEntity(
        id = id,
        userId = "user-1",
        clientEventId = "client-$id",
        sourceType = SourceType.VOICE,
        sourceRef = sourceRef,
        eventTitle = "Voice memo",
        timestamp = Instant.fromEpochMilliseconds(1_000),
    )

    private val fakeSession = SupabaseSession(
        accessToken = "access",
        refreshToken = "refresh",
        userId = "user-1",
        email = "user@example.com",
        expiresAt = Instant.fromEpochMilliseconds(9_999_999),
    )
}
