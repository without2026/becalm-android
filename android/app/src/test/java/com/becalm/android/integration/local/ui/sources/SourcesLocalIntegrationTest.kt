package com.becalm.android.integration.local.ui.sources

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.SyncCursorStoreImpl
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.local.secure.ImapCredentials
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.AuthState
import com.becalm.android.data.repository.PersonEnrichmentRepositoryImpl
import com.becalm.android.data.repository.RawIngestionRepositoryImpl
import com.becalm.android.data.repository.MeetingImportRepository
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepositoryImpl
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.ui.sources.ARG_SOURCE_TYPE
import com.becalm.android.ui.sources.CONTACTS_SOURCE_TYPE
import com.becalm.android.ui.sources.ContactsPermissionChecker
import com.becalm.android.ui.sources.ContactsSourceDetailEffect
import com.becalm.android.ui.sources.ContactsSourceDetailViewModel
import com.becalm.android.ui.sources.DefaultSourceAdministrationPort
import com.becalm.android.ui.sources.SourceAdministrationPort
import com.becalm.android.ui.sources.SourceDetailViewModel
import com.becalm.android.ui.sources.SourceSyncPort
import com.becalm.android.ui.sources.SourcesListNavigation
import com.becalm.android.ui.sources.SourcesListViewModel
import com.becalm.android.worker.ingestion.ImapNaverWorker
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SourcesLocalIntegrationTest {

    private val dispatcher = StandardTestDispatcher()
    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val logger = RecordingLogger()
    private val api = mockk<RailwayApi>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val userPrefsStore = UserPrefsStoreImpl(
        dataStore = LocalIntegrationSupport.prefsDataStore("sources-user-prefs"),
    )
    private val syncCursorStore = SyncCursorStoreImpl(
        LocalIntegrationSupport.prefsDataStore("sources-cursors"),
    )
    private val imapCredentialStore = ImapCredentialStore(
        context = LocalIntegrationSupport.appContext(),
        ioDispatcher = UnconfinedTestDispatcher(),
    )
    private val rawIngestionRepository = RawIngestionRepositoryImpl(
        dao = db.rawIngestionEventDao(),
        api = api,
        logger = logger,
    )
    private val meetingImportRepository = mockk<MeetingImportRepository>(relaxed = true)
    private val enrichmentRepository = PersonEnrichmentRepositoryImpl(
        dao = db.personEnrichmentDao(),
        logger = logger,
    )
    private val sourceStatusRepository = SourceStatusRepositoryImpl(
        cursorStore = syncCursorStore,
        userPrefs = LocalIntegrationSupport.prefsDataStore("sources-status-prefs"),
        api = api,
        ioDispatcher = UnconfinedTestDispatcher(),
        logger = logger,
    )
    private val contactsPermissionChecker = FakeContactsPermissionChecker(granted = true)
    private val sourceSyncPort = RecordingSourceSyncPort()

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(dispatcher)
        userPrefsStore.setCurrentUserId(USER_ID)
        every { authRepository.observeAuthState() } returns flowOf(
            AuthState.Authenticated(
                SupabaseSession(
                    accessToken = "access",
                    refreshToken = "refresh",
                    userId = USER_ID,
                    email = "user@example.com",
                    expiresAt = Instant.parse("2026-05-04T00:00:00Z"),
                ),
            ),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `SMG-001 and ENR-008 sources list reflects local status repository plus contacts pseudo-source`() = runTest {
        sourceStatusRepository.recordSyncSuccess(SourceType.VOICE, Instant.parse("2026-04-23T00:30:00Z"))
        sourceStatusRepository.recordSyncError(
            SourceType.NAVER_IMAP,
            "reauth required",
            Instant.parse("2026-04-23T01:30:00Z"),
        )
        enrichmentRepository.upsert(
            PersonEnrichmentEntity(
                personRef = "+821012345678",
                displayName = "김철수",
                sourceContactId = "contact-1",
                lastSyncedAt = Instant.parse("2026-04-23T01:00:00Z"),
            ),
        )
        enrichmentRepository.upsert(
            PersonEnrichmentEntity(
                personRef = "a@corp.com",
                displayName = "Alice",
                sourceContactId = "contact-2",
                lastSyncedAt = Instant.parse("2026-04-23T02:00:00Z"),
            ),
        )
        enrichmentRepository.upsert(
            PersonEnrichmentEntity(
                personRef = "unmatched@example.com",
                lastSyncedAt = Instant.parse("2026-04-23T03:00:00Z"),
            ),
        )

        val viewModel = SourcesListViewModel(
            authRepository = authRepository,
            sourceStatusRepository = sourceStatusRepository,
            personEnrichmentRepository = enrichmentRepository,
            contactsPermissionChecker = contactsPermissionChecker,
            logger = logger,
        )

        viewModel.state.test {
            var state = awaitItem()
            while (state.items.size < 8 || state.items.first().enrichedCount != 2) {
                state = awaitItem()
            }

            assertEquals(CONTACTS_SOURCE_TYPE, state.items.first().sourceType)
            assertEquals("CONNECTED", state.items.first().status)
            assertEquals(2, state.items.first().enrichedCount)
            assertEquals(Instant.parse("2026-04-23T03:00:00Z"), state.items.first().lastSyncAt)

            val voice = state.items.first { it.sourceType == SourceType.VOICE }
            assertEquals("CONNECTED", voice.status)

            val naver = state.items.first { it.sourceType == SourceType.NAVER_IMAP }
            assertEquals("ERROR", naver.status)
            assertEquals("reauth required", naver.lastError)

            cancelAndIgnoreRemainingEvents()
        }

        viewModel.navigation.test {
            viewModel.onSourceSelected(CONTACTS_SOURCE_TYPE)
            assertEquals(SourcesListNavigation.ContactsDetail, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SMG-002 SMG-005 and ENR-008 source details project recent events sync metadata and contacts detail payload`() = runTest {
        val syncedAt = Instant.parse("2026-04-23T03:30:00Z")
        sourceStatusRepository.recordSyncSuccess(SourceType.GMAIL, syncedAt)
        db.rawIngestionEventDao().insertAll(
            listOf(
                rawEvent(
                    id = "gmail-1",
                    sourceType = SourceType.GMAIL,
                    timestamp = Instant.parse("2026-04-23T03:00:00Z"),
                    title = "Latest subject",
                ),
                rawEvent(
                    id = "gmail-2",
                    sourceType = SourceType.GMAIL,
                    timestamp = Instant.parse("2026-04-23T02:00:00Z"),
                    title = "Older subject",
                ),
            ),
        )
        enrichmentRepository.upsert(
            PersonEnrichmentEntity(
                personRef = "+82105557777",
                displayName = "연락처 이름",
                sourceContactId = "contact-3",
                lastSyncedAt = Instant.parse("2026-04-23T04:00:00Z"),
            ),
        )

        val sourceDetailViewModel = SourceDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf(ARG_SOURCE_TYPE to SourceType.GMAIL)),
            sourceStatusRepository = sourceStatusRepository,
            rawIngestionRepository = rawIngestionRepository,
            sourceAdministrationPort = object : SourceAdministrationPort {
                override suspend fun disconnect(sourceType: String) = error("not used")
            },
            sourceSyncPort = sourceSyncPort,
            meetingImportRepository = meetingImportRepository,
            logger = logger,
        )
        sourceDetailViewModel.setUserId(USER_ID)

        sourceDetailViewModel.state.test {
            var state = awaitItem()
            while (state.recentEvents.size < 2 || state.lastSyncAt != syncedAt) {
                state = awaitItem()
            }

            assertEquals(SourceType.GMAIL, state.sourceType)
            assertEquals(SourceConnectionStatus.CONNECTED.name, state.status)
            assertEquals(2, state.eventsSyncedCount)
            assertEquals(syncedAt, state.lastSyncAt)
            assertTrue(state.showDisconnectButton)
            assertTrue(state.showManualSyncButton)
            assertFalse(state.showReconnectButton)
            assertEquals(listOf("gmail-1", "gmail-2"), state.recentEvents.map { it.id })

            sourceDetailViewModel.onManualSync()
            advanceUntilIdle()
            assertEquals(listOf(SourceType.GMAIL), sourceSyncPort.manualSyncSources)
            cancelAndIgnoreRemainingEvents()
        }

        val contactsDetailViewModel = ContactsSourceDetailViewModel(
            personEnrichmentRepository = enrichmentRepository,
            contactsPermissionChecker = contactsPermissionChecker,
        )

        contactsDetailViewModel.state.test {
            var state = awaitItem()
            while (state.enrichedCount != 1 || state.lastSyncAt == null) {
                state = awaitItem()
            }
            assertEquals("CONNECTED", state.connectionState)
            assertEquals(1, state.enrichedCount)
            assertEquals(Instant.parse("2026-04-23T04:00:00Z"), state.lastSyncAt)
            assertTrue(state.showPermissionRevokeButton)
            cancelAndIgnoreRemainingEvents()
        }

        contactsDetailViewModel.effects.test {
            contactsDetailViewModel.onPermissionAction()
            assertEquals(ContactsSourceDetailEffect.OpenPermissionSettings, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SMG-004 disconnect success clears local source state while retaining room history`() = runTest {
        userPrefsStore.setEmailSourceConnected(EmailPipaProvider.NAVER_IMAP, true)
        syncCursorStore.setImapState(
            ImapNaverWorker.MAILBOX_NAVER_INBOX,
            com.becalm.android.data.local.datastore.ImapCursorState(uidValidity = 1, lastSeenUid = 10),
        )
        syncCursorStore.setImapState(
            ImapNaverWorker.MAILBOX_NAVER_SENT,
            com.becalm.android.data.local.datastore.ImapCursorState(uidValidity = 1, lastSeenUid = 20),
        )
        imapCredentialStore.save(
            SourceType.NAVER_IMAP,
            ImapCredentials(
                username = "naver@example.com",
                appPassword = "app-pw",
                host = "imap.naver.com",
                port = 993,
            ),
        )
        sourceStatusRepository.recordSyncSuccess(SourceType.NAVER_IMAP, Instant.parse("2026-04-23T06:00:00Z"))
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "naver-1",
                sourceType = SourceType.NAVER_IMAP,
                timestamp = Instant.parse("2026-04-23T05:30:00Z"),
                title = "네이버 메일",
            ),
        )

        val sourceDetailViewModel = SourceDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf(ARG_SOURCE_TYPE to SourceType.NAVER_IMAP)),
            sourceStatusRepository = sourceStatusRepository,
            rawIngestionRepository = rawIngestionRepository,
            sourceAdministrationPort = DefaultSourceAdministrationPort(
                sourceStatusRepository = sourceStatusRepository,
                syncCursorStore = syncCursorStore,
                userPrefsStore = userPrefsStore,
                imapCredentialStore = imapCredentialStore,
                logger = logger,
            ),
            sourceSyncPort = sourceSyncPort,
            meetingImportRepository = meetingImportRepository,
            logger = logger,
        )
        sourceDetailViewModel.setUserId(USER_ID)

        sourceDetailViewModel.state.test {
            var state = awaitItem()
            while (state.status != SourceConnectionStatus.CONNECTED.name || state.recentEvents.isEmpty()) {
                state = awaitItem()
            }

            sourceDetailViewModel.onDisconnectClick()
            sourceDetailViewModel.onDisconnectConfirm()

            while (state.disconnectOutcome == null || state.status != SourceConnectionStatus.NEVER_CONNECTED.name) {
                state = awaitItem()
            }

            assertEquals(SourceType.NAVER_IMAP, state.disconnectOutcome?.sourceType)
            assertTrue(state.disconnectOutcome?.cursorCleared == true)
            assertTrue(state.disconnectOutcome?.credentialsDeleted == true)
            assertTrue(state.disconnectOutcome?.roomDataRetained == true)
            assertFalse(userPrefsStore.observeEmailSourceConnected(EmailPipaProvider.NAVER_IMAP).first())
            assertEquals(null, syncCursorStore.observeImapState(ImapNaverWorker.MAILBOX_NAVER_INBOX).first())
            assertEquals(null, syncCursorStore.observeImapState(ImapNaverWorker.MAILBOX_NAVER_SENT).first())
            assertEquals(null, imapCredentialStore.load(SourceType.NAVER_IMAP))
            assertEquals(1, rawIngestionRepository.observeForSourceType(USER_ID, SourceType.NAVER_IMAP).first().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun rawEvent(
        id: String,
        sourceType: String,
        timestamp: Instant,
        title: String,
    ): RawIngestionEventEntity = RawIngestionEventEntity(
        id = id,
        userId = USER_ID,
        clientEventId = "client-$id",
        sourceType = sourceType,
        sourceRef = "ref-$id",
        counterpartyRef = "person-$id",
        eventTitle = title,
        eventSnippet = "snippet-$id",
        commitmentsExtractedCount = 0,
        timestamp = timestamp,
        syncStatus = "synced",
    )

    private class FakeContactsPermissionChecker(granted: Boolean) : ContactsPermissionChecker {
        private val state = MutableStateFlow(granted)
        override fun isGranted(): Boolean = state.value
        override fun observeGrantState(): Flow<Boolean> = state
    }

    private class RecordingSourceSyncPort : SourceSyncPort {
        val manualSyncSources: MutableList<String> = mutableListOf()

        override suspend fun requestManualSync(sourceType: String): BecalmResult<Unit> {
            manualSyncSources += sourceType
            return BecalmResult.Success(Unit)
        }
    }

    private companion object {
        private const val USER_ID = "user-1"
    }
}
