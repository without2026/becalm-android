package com.becalm.android.unit.ui.settings

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.SelfIdentityAnchorEntity
import com.becalm.android.data.local.db.entity.SourceConnectionEntity
import com.becalm.android.data.local.db.entity.UserProfileEntity
import com.becalm.android.data.repository.SelfIdentityRepository
import com.becalm.android.data.repository.SourceConnectionRepository
import com.becalm.android.data.repository.UserProfileRepository
import com.becalm.android.ui.settings.SettingsIdentityViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsIdentityViewModelSpecTest {

    private val testDispatcher = StandardTestDispatcher()
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val userProfileRepository: UserProfileRepository = mockk(relaxed = true)
    private val selfIdentityRepository: SelfIdentityRepository = mockk(relaxed = true)
    private val sourceConnectionRepository: SourceConnectionRepository = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-123")
        coEvery { userProfileRepository.refreshFromServer("user-123") } returns BecalmResult.Success(
            userProfile(displayName = "민홍", phone = null),
        )
        coEvery { userProfileRepository.find("user-123") } returns userProfile(displayName = "민홍", phone = null)
        coEvery { sourceConnectionRepository.refresh("user-123") } returns BecalmResult.Success(emptyList())
        every { sourceConnectionRepository.observeAll("user-123") } returns flowOf(emptyList())
        coEvery { selfIdentityRepository.refresh("user-123") } returns BecalmResult.Success(emptyList())
        every { selfIdentityRepository.observeAll("user-123") } returns flowOf(emptyList())
        coEvery { userProfileRepository.upsertLocal(any(), any(), any()) } answers {
            userProfile(displayName = secondArg(), phone = thirdArg())
        }
        coEvery {
            selfIdentityRepository.upsertLocalAnchor(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            selfAnchor(id = "local-${secondArg<String>()}-${thirdArg<String>()}", type = secondArg(), value = thirdArg())
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `saving profile also upserts phone self anchor for local matching mirror`() = runTest {
        val phoneAnchor = selfAnchor(id = "anchor-phone", type = "phone", value = "+821012345678")
        coEvery {
            userProfileRepository.updateRemote(
                userId = "user-123",
                displayName = "민홍",
                phoneE164Self = "+821012345678",
            )
        } returns BecalmResult.Success(userProfile(displayName = "민홍", phone = "+821012345678"))
        coEvery {
            selfIdentityRepository.createAnchor(
                userId = "user-123",
                anchorType = "phone",
                value = "+821012345678",
                displayValue = "+821012345678",
                source = "user_profile",
            )
        } returns BecalmResult.Success(phoneAnchor)
        every { selfIdentityRepository.observeAll("user-123") } returns flowOf(listOf(phoneAnchor))

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.onPhoneChange("+821012345678")
        viewModel.onSaveProfile()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            userProfileRepository.updateRemote("user-123", "민홍", "+821012345678")
        }
        coVerify(exactly = 1) {
            selfIdentityRepository.createAnchor("user-123", "phone", "+821012345678", "+821012345678", "user_profile")
        }
        assertEquals("+821012345678", viewModel.uiState.value.phone)
        assertEquals("phone", viewModel.uiState.value.anchors.single().type)
        assertFalse(viewModel.uiState.value.savingProfile)
        assertEquals(com.becalm.android.R.string.settings_identity_profile_saved, viewModel.uiState.value.notice?.resId)
    }

    @Test
    fun `saving profile normalizes Korean local phone before profile and anchor writes`() = runTest {
        val phoneAnchor = selfAnchor(id = "anchor-phone", type = "phone", value = "+821012345678")
        coEvery {
            userProfileRepository.updateRemote(
                userId = "user-123",
                displayName = "민홍",
                phoneE164Self = "+821012345678",
            )
        } returns BecalmResult.Success(userProfile(displayName = "민홍", phone = "+821012345678"))
        coEvery {
            selfIdentityRepository.createAnchor(
                userId = "user-123",
                anchorType = "phone",
                value = "+821012345678",
                displayValue = "+821012345678",
                source = "user_profile",
            )
        } returns BecalmResult.Success(phoneAnchor)
        every { selfIdentityRepository.observeAll("user-123") } returns flowOf(listOf(phoneAnchor))

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.onPhoneChange("01012345678")
        viewModel.onSaveProfile()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            userProfileRepository.updateRemote("user-123", "민홍", "+821012345678")
        }
        coVerify(exactly = 1) {
            selfIdentityRepository.createAnchor("user-123", "phone", "+821012345678", "+821012345678", "user_profile")
        }
        assertEquals("+821012345678", viewModel.uiState.value.phone)
    }

    @Test
    fun `saving profile only reports success after remote mirror succeeds`() = runTest {
        coEvery {
            userProfileRepository.updateRemote(
                userId = "user-123",
                displayName = "민홍",
                phoneE164Self = "+821012345678",
            )
        } returns BecalmResult.Failure(BecalmError.ServerError(503, "upstream_unavailable"))

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.onPhoneChange("+821012345678")
        viewModel.onSaveProfile()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.savingProfile)
        assertEquals(null, viewModel.uiState.value.notice)
        assertEquals(com.becalm.android.R.string.settings_identity_error_save_profile, viewModel.uiState.value.error?.resId)
    }

    @Test
    fun `adding anchor only reports success after remote mirror succeeds`() = runTest {
        val emailAnchor = selfAnchor(id = "anchor-email", type = "email", value = "me@example.com")
        coEvery {
            selfIdentityRepository.createAnchor(
                userId = "user-123",
                anchorType = "email",
                value = "me@example.com",
                displayValue = "me@example.com",
                source = "user_profile",
            )
        } returns BecalmResult.Success(emailAnchor)
        every { selfIdentityRepository.observeAll("user-123") } returns flowOf(listOf(emailAnchor))

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.onNewAnchorValueChange("me@example.com")
        viewModel.onAddAnchor()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            selfIdentityRepository.createAnchor("user-123", "email", "me@example.com", "me@example.com", "user_profile")
        }
        coVerify(exactly = 0) {
            selfIdentityRepository.upsertLocalAnchor(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        assertEquals("", viewModel.uiState.value.newAnchorValue)
        assertEquals("email", viewModel.uiState.value.anchors.single().type)
        assertFalse(viewModel.uiState.value.addingAnchor)
        assertEquals(com.becalm.android.R.string.settings_identity_anchor_added, viewModel.uiState.value.notice?.resId)
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun `adding anchor keeps input and shows error when remote mirror fails`() = runTest {
        coEvery {
            selfIdentityRepository.createAnchor(
                userId = "user-123",
                anchorType = "email",
                value = "me@example.com",
                displayValue = "me@example.com",
                source = "user_profile",
            )
        } returns BecalmResult.Failure(BecalmError.ServerError(503, "upstream_unavailable"))

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.onNewAnchorValueChange("me@example.com")
        viewModel.onAddAnchor()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            selfIdentityRepository.createAnchor("user-123", "email", "me@example.com", "me@example.com", "user_profile")
        }
        coVerify(exactly = 0) {
            selfIdentityRepository.upsertLocalAnchor(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        assertEquals("me@example.com", viewModel.uiState.value.newAnchorValue)
        assertEquals(emptyList<Any>(), viewModel.uiState.value.anchors)
        assertFalse(viewModel.uiState.value.addingAnchor)
        assertEquals(null, viewModel.uiState.value.notice)
        assertEquals(com.becalm.android.R.string.settings_identity_error_add_anchor, viewModel.uiState.value.error?.resId)
    }

    @Test
    fun `archiving anchor sends disabled status to backend`() = runTest {
        val disabledAnchor = selfAnchor(id = "anchor-alias", type = "alias", value = "jake", status = "disabled")
        coEvery {
            selfIdentityRepository.updateAnchor(id = "anchor-alias", status = "disabled")
        } returns BecalmResult.Success(disabledAnchor)
        every { selfIdentityRepository.observeAll("user-123") } returns flowOf(listOf(disabledAnchor))

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.onArchiveAnchor("anchor-alias")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            selfIdentityRepository.updateAnchor(id = "anchor-alias", status = "disabled")
        }
        assertEquals("disabled", viewModel.uiState.value.anchors.single().status)
        assertEquals(emptySet<String>(), viewModel.uiState.value.archivingAnchorIds)
    }

    @Test
    fun `archiving anchor ignores duplicate taps while request is in flight`() = runTest {
        val disabledAnchor = selfAnchor(id = "anchor-alias", type = "alias", value = "jake", status = "disabled")
        coEvery {
            selfIdentityRepository.updateAnchor(id = "anchor-alias", status = "disabled")
        } returns BecalmResult.Success(disabledAnchor)
        every { selfIdentityRepository.observeAll("user-123") } returns flowOf(listOf(disabledAnchor))

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.onArchiveAnchor("anchor-alias")
        viewModel.onArchiveAnchor("anchor-alias")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            selfIdentityRepository.updateAnchor(id = "anchor-alias", status = "disabled")
        }
    }

    @Test
    fun `archiving anchor clears in flight state on failure`() = runTest {
        coEvery {
            selfIdentityRepository.updateAnchor(id = "anchor-alias", status = "disabled")
        } returns BecalmResult.Failure(BecalmError.ServerError(503, "upstream_unavailable"))

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.onArchiveAnchor("anchor-alias")
        advanceUntilIdle()

        assertEquals(emptySet<String>(), viewModel.uiState.value.archivingAnchorIds)
        assertNotNull(viewModel.uiState.value.error)
    }

    @Test
    fun `disconnecting source connection updates local mirror row`() = runTest {
        val connected = sourceConnection(id = "conn-gmail", status = "connected")
        val disconnected = connected.copy(status = "disconnected")
        coEvery { sourceConnectionRepository.refresh("user-123") } returns BecalmResult.Success(listOf(connected))
        coEvery {
            sourceConnectionRepository.disconnectConnection("user-123", "conn-gmail")
        } returns BecalmResult.Success(disconnected)
        every { sourceConnectionRepository.observeAll("user-123") } returns flowOf(listOf(disconnected))

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.onDisconnectConnection("conn-gmail")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            sourceConnectionRepository.disconnectConnection("user-123", "conn-gmail")
        }
        assertEquals("disconnected", viewModel.uiState.value.connections.single().status)
        assertEquals(emptySet<String>(), viewModel.uiState.value.disconnectingConnectionIds)
    }

    @Test
    fun `deleting source connection asks for confirmation then removes local mirror row`() = runTest {
        val connected = sourceConnection(id = "conn-gmail", status = "connected")
        coEvery { sourceConnectionRepository.refresh("user-123") } returns BecalmResult.Success(listOf(connected))
        coEvery {
            sourceConnectionRepository.deleteConnection("user-123", "conn-gmail")
        } returns BecalmResult.Success(connected.copy(status = "disconnected"))
        every { sourceConnectionRepository.observeAll("user-123") } returns flowOf(emptyList())

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.onRequestDeleteConnection("conn-gmail")

        assertEquals("conn-gmail", viewModel.uiState.value.confirmingDeleteConnectionId)

        viewModel.onConfirmDeleteConnection()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            sourceConnectionRepository.deleteConnection("user-123", "conn-gmail")
        }
        assertEquals(0, viewModel.uiState.value.connections.size)
        assertEquals(emptySet<String>(), viewModel.uiState.value.deletingConnectionIds)
        assertEquals(null, viewModel.uiState.value.confirmingDeleteConnectionId)
    }

    private fun buildViewModel(): SettingsIdentityViewModel =
        SettingsIdentityViewModel(
            userPrefsStore = userPrefsStore,
            userProfileRepository = userProfileRepository,
            selfIdentityRepository = selfIdentityRepository,
            sourceConnectionRepository = sourceConnectionRepository,
            logger = logger,
        )

    private fun userProfile(
        displayName: String?,
        phone: String?,
    ): UserProfileEntity =
        UserProfileEntity(
            userId = "user-123",
            displayNameOverride = displayName,
            phoneE164Self = phone,
            timezone = "Asia/Seoul",
            preferredLocale = "ko",
            createdAt = Instant.parse("2026-05-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-05-01T00:00:00Z"),
        )

    private fun selfAnchor(
        id: String,
        type: String,
        value: String,
        status: String = "active",
    ): SelfIdentityAnchorEntity =
        SelfIdentityAnchorEntity(
            id = id,
            userId = "user-123",
            anchorType = type,
            normalizedValue = value,
            displayValue = value,
            source = "user_profile",
            scope = "global",
            sourceConnectionId = null,
            sourceEventId = null,
            trust = "user_confirmed",
            status = status,
            createdAt = Instant.parse("2026-05-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-05-01T00:00:00Z"),
        )

    private fun sourceConnection(
        id: String,
        status: String,
    ): SourceConnectionEntity =
        SourceConnectionEntity(
            id = id,
            userId = "user-123",
            provider = "google",
            capability = "mail",
            accountIdentifier = "work@example.com",
            accountDisplayName = "Work",
            ownership = "self",
            status = status,
            linkedSelfAnchorId = null,
            lastSyncAt = null,
            lastError = null,
        )
}
