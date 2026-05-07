package com.becalm.android.unit.ui.sources

import app.cash.turbine.test
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.PersonEnrichmentSummary
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.AuthState
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.sources.ContactsPermissionChecker
import com.becalm.android.ui.sources.ContactsSourceDetailEffect
import com.becalm.android.ui.sources.ContactsSourceDetailViewModel
import com.becalm.android.ui.sources.SourcesListNavigation
import com.becalm.android.ui.sources.SourcesListViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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
class SourcesListViewModelSpecTest {

    private val testDispatcher = StandardTestDispatcher()
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val sourceStatusRepository: SourceStatusRepository = mockk()
    private val personEnrichmentRepository: PersonEnrichmentRepository = mockk()
    private val contactsPermissionChecker = FakeContactsPermissionChecker()
    private val logger: Logger = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { authRepository.observeAuthState() } returns flowOf(AuthState.Authenticated(session()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `SMG-001 list exposes one row per product source plus contacts pseudo source`() = runTest {
        val now = Instant.parse("2026-04-18T09:00:00Z")
        val seeded = listOf(
            SourceStatus(SourceType.VOICE, SourceConnectionStatus.CONNECTED, now, null),
            SourceStatus(SourceType.GMAIL, SourceConnectionStatus.CONNECTED, now, null),
            SourceStatus(SourceType.OUTLOOK_MAIL, SourceConnectionStatus.ERROR, now, "token expired"),
            SourceStatus(SourceType.NAVER_IMAP, SourceConnectionStatus.NEVER_CONNECTED, now, null),
            SourceStatus(SourceType.DAUM_IMAP, SourceConnectionStatus.CONNECTED, now, null),
            SourceStatus(SourceType.GOOGLE_CALENDAR, SourceConnectionStatus.CONNECTED, now, null),
            SourceStatus(SourceType.OUTLOOK_CALENDAR, SourceConnectionStatus.NEVER_CONNECTED, now, null),
        )
        every { sourceStatusRepository.observeAll() } returns flowOf(seeded)
        every { personEnrichmentRepository.observeSummary() } returns flowOf(PersonEnrichmentSummary(0, null))
        contactsPermissionChecker.setGranted(true)

        val viewModel = buildSourcesListViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            if (emission.items.isEmpty()) emission = awaitItem()

            assertEquals(8, emission.items.size)
            val rowsByType = emission.items.associateBy { it.sourceType }
            assertTrue(rowsByType.containsKey("contacts"))
            assertTrue(rowsByType.containsKey(SourceType.VOICE))
            assertTrue(rowsByType.containsKey(SourceType.GMAIL))
            assertTrue(rowsByType.containsKey(SourceType.OUTLOOK_MAIL))
            assertTrue(rowsByType.containsKey(SourceType.NAVER_IMAP))
            assertTrue(rowsByType.containsKey(SourceType.DAUM_IMAP))
            assertTrue(rowsByType.containsKey(SourceType.GOOGLE_CALENDAR))
            assertTrue(rowsByType.containsKey(SourceType.OUTLOOK_CALENDAR))
            assertEquals(SourceSyncStatus.Connected, rowsByType.getValue(SourceType.VOICE).status)
            assertEquals(SourceSyncStatus.Connected, rowsByType.getValue(SourceType.GMAIL).status)
            assertEquals(SourceSyncStatus.Error, rowsByType.getValue(SourceType.OUTLOOK_MAIL).status)
            assertEquals(SourceSyncStatus.Disconnected, rowsByType.getValue(SourceType.NAVER_IMAP).status)
            assertEquals(SourceSyncStatus.Connected, rowsByType.getValue(SourceType.DAUM_IMAP).status)
            assertEquals(SourceSyncStatus.Disconnected, rowsByType.getValue(SourceType.OUTLOOK_CALENDAR).status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ENR-008 contacts pseudo source row reflects permission and enrichment metadata`() = runTest {
        val lastSync = Instant.parse("2026-04-19T03:30:00Z")
        every { sourceStatusRepository.observeAll() } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeSummary() } returns flowOf(PersonEnrichmentSummary(2, lastSync))
        contactsPermissionChecker.setGranted(true)

        val viewModel = buildSourcesListViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            if (emission.items.isEmpty()) emission = awaitItem()

            val contactsRow = emission.items.first { it.sourceType == "contacts" }
            assertEquals(SourceSyncStatus.Connected, contactsRow.status)
            assertEquals(lastSync, contactsRow.lastSyncAt)
            assertEquals(2, contactsRow.enrichedCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ENR-008 contacts pseudo source row is disconnected when permission is denied`() = runTest {
        every { sourceStatusRepository.observeAll() } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeSummary() } returns flowOf(
            PersonEnrichmentSummary(1, Instant.parse("2026-04-18T03:30:00Z")),
        )
        contactsPermissionChecker.setGranted(false)

        val viewModel = buildSourcesListViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            if (emission.items.isEmpty()) emission = awaitItem()

            val contactsRow = emission.items.first { it.sourceType == "contacts" }
            assertEquals(SourceSyncStatus.Disconnected, contactsRow.status)
            assertEquals(1, contactsRow.enrichedCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ENR-008 selecting contacts routes to contacts detail when permission is granted`() = runTest {
        every { sourceStatusRepository.observeAll() } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeSummary() } returns flowOf(PersonEnrichmentSummary(0, null))
        contactsPermissionChecker.setGranted(true)

        val viewModel = buildSourcesListViewModel()

        viewModel.navigation.test {
            viewModel.onSourceSelected("contacts")

            assertEquals(SourcesListNavigation.ContactsDetail, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ENR-008 selecting contacts routes to permission screen when permission is denied`() = runTest {
        every { sourceStatusRepository.observeAll() } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeSummary() } returns flowOf(PersonEnrichmentSummary(0, null))
        contactsPermissionChecker.setGranted(false)

        val viewModel = buildSourcesListViewModel()

        viewModel.navigation.test {
            viewModel.onSourceSelected("contacts")

            assertEquals(SourcesListNavigation.ContactsPermission, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `non-contacts source selection routes to source detail`() = runTest {
        every { sourceStatusRepository.observeAll() } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeSummary() } returns flowOf(PersonEnrichmentSummary(0, null))

        val viewModel = buildSourcesListViewModel()

        viewModel.navigation.test {
            viewModel.onSourceSelected(SourceType.GMAIL)

            assertEquals(
                SourcesListNavigation.SourceDetail(SourceType.GMAIL),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `AUTH-005 unauthenticated sources list does not touch user scoped repositories`() = runTest {
        every { authRepository.observeAuthState() } returns flowOf(AuthState.Unauthenticated)

        val viewModel = buildSourcesListViewModel()

        viewModel.state.test {
            assertEquals(emptyList<com.becalm.android.ui.sources.SourceStatusRow>(), awaitItem().items)
            verify(exactly = 0) { sourceStatusRepository.observeAll() }
            verify(exactly = 0) { personEnrichmentRepository.observeSummary() }
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun buildSourcesListViewModel(): SourcesListViewModel = SourcesListViewModel(
        authRepository = authRepository,
        sourceStatusRepository = sourceStatusRepository,
        personEnrichmentRepository = personEnrichmentRepository,
        contactsPermissionChecker = contactsPermissionChecker,
        logger = logger,
    )

    private fun session(): SupabaseSession = SupabaseSession(
        accessToken = "access",
        refreshToken = "refresh",
        userId = "user-1",
        email = "user@example.com",
        expiresAt = Instant.parse("2026-05-04T00:00:00Z"),
    )

    private fun enrichment(
        counterpartyRef: String,
        lastSyncedAt: Instant,
    ): PersonEnrichmentEntity = PersonEnrichmentEntity(
        personRef = counterpartyRef,
        displayName = "Kim",
        nickname = null,
        company = null,
        title = null,
        sourceContactId = "contact-$counterpartyRef",
        lastSyncedAt = lastSyncedAt,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsSourceDetailContractSpecTest {

    private val testDispatcher = StandardTestDispatcher()
    private val personEnrichmentRepository: PersonEnrichmentRepository = mockk()
    private val contactsPermissionChecker = FakeContactsPermissionChecker()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `ENR-008 contacts detail state exposes enrichment status and revoke action when granted`() = runTest {
        val lastSync = Instant.parse("2026-04-20T01:00:00Z")
        every { personEnrichmentRepository.observeSummary() } returns flowOf(PersonEnrichmentSummary(2, lastSync))
        contactsPermissionChecker.setGranted(true)

        val viewModel = ContactsSourceDetailViewModel(
            personEnrichmentRepository = personEnrichmentRepository,
            contactsPermissionChecker = contactsPermissionChecker,
        )

        viewModel.state.test {
            var state = awaitItem()
            if (state.connectionState.isBlank()) state = awaitItem()

            assertEquals("CONNECTED", state.connectionState)
            assertEquals(2, state.enrichedCount)
            assertEquals(lastSync, state.lastSyncAt)
            assertTrue(state.showPermissionRevokeButton)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ENR-008 contacts permission action opens permission settings when already granted`() = runTest {
        every { personEnrichmentRepository.observeSummary() } returns flowOf(PersonEnrichmentSummary(0, null))
        contactsPermissionChecker.setGranted(true)

        val viewModel = ContactsSourceDetailViewModel(
            personEnrichmentRepository = personEnrichmentRepository,
            contactsPermissionChecker = contactsPermissionChecker,
        )

        viewModel.effects.test {
            viewModel.onPermissionAction()

            assertEquals(ContactsSourceDetailEffect.OpenPermissionSettings, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ENR-008 contacts permission action opens permission request flow when not granted`() = runTest {
        every { personEnrichmentRepository.observeSummary() } returns flowOf(PersonEnrichmentSummary(0, null))
        contactsPermissionChecker.setGranted(false)

        val viewModel = ContactsSourceDetailViewModel(
            personEnrichmentRepository = personEnrichmentRepository,
            contactsPermissionChecker = contactsPermissionChecker,
        )

        viewModel.effects.test {
            viewModel.onPermissionAction()

            assertEquals(ContactsSourceDetailEffect.OpenContactsPermissionScreen, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ENR-008 contacts detail hides revoke action when permission is not granted`() = runTest {
        every { personEnrichmentRepository.observeSummary() } returns flowOf(PersonEnrichmentSummary(0, null))
        contactsPermissionChecker.setGranted(false)

        val viewModel = ContactsSourceDetailViewModel(
            personEnrichmentRepository = personEnrichmentRepository,
            contactsPermissionChecker = contactsPermissionChecker,
        )

        viewModel.state.test {
            var state = awaitItem()
            if (state.connectionState.isBlank()) state = awaitItem()

            assertEquals("DISCONNECTED", state.connectionState)
            assertEquals(0, state.enrichedCount)
            assertNull(state.lastSyncAt)
            assertFalse(state.showPermissionRevokeButton)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun enrichment(
        counterpartyRef: String,
        lastSyncedAt: Instant,
    ): PersonEnrichmentEntity = PersonEnrichmentEntity(
        personRef = counterpartyRef,
        displayName = "Kim",
        nickname = null,
        company = null,
        title = null,
        sourceContactId = "contact-$counterpartyRef",
        lastSyncedAt = lastSyncedAt,
    )
}

private class FakeContactsPermissionChecker(
    initialGranted: Boolean = false,
) : ContactsPermissionChecker {
    private val grantState = MutableStateFlow(initialGranted)

    override fun isGranted(): Boolean = grantState.value

    override fun observeGrantState() = grantState

    fun setGranted(granted: Boolean) {
        grantState.value = granted
    }
}
