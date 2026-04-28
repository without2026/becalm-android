package com.becalm.android.integration.local.data.auth

import app.cash.turbine.test
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.auth.ProcessRestarter
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.PipaActionLogEntry
import com.becalm.android.data.local.datastore.SyncCursorStoreImpl
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.BeCalmDatabaseProvider
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.dao.PersonEnrichmentSummary
import com.becalm.android.data.local.secure.OAuthCredentialStore
import com.becalm.android.data.remote.interceptor.AuthTokenProvider
import com.becalm.android.data.remote.supabase.SupabaseAuthClient
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.AuthState
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.AuthRepositoryImpl
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.worker.ContentObserverBootstrap
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import javax.inject.Provider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AuthRepositoryLocalIntegrationTest {

    private val userPrefsStore = UserPrefsStoreImpl(
        dataStore = LocalIntegrationSupport.prefsDataStore("auth-local"),
    )
    private val logger = RecordingLogger()
    private val databaseProvider = BeCalmDatabaseProvider(
        context = LocalIntegrationSupport.appContext(),
        userPrefsStore = userPrefsStore,
        logger = logger,
    )
    private val authClient = mockk<SupabaseAuthClient>()
    private val sessionStore = InMemorySessionStore()
    private val tokenProvider = mockk<AuthTokenProvider>(relaxed = true)
    private val deviceKeyStore = mockk<com.becalm.android.data.local.secure.DeviceKeyStore>(relaxed = true)
    private val syncCursorStore = SyncCursorStoreImpl(
        dataStore = LocalIntegrationSupport.prefsDataStore("auth-local-cursors"),
    )
    private val workScheduler = mockk<WorkScheduler>(relaxed = true)
    private val contentObserverBootstrap = mockk<ContentObserverBootstrap>(relaxed = true)
    private val enrichmentRepository: PersonEnrichmentRepository = object : PersonEnrichmentRepository {
        private fun delegate() = com.becalm.android.data.repository.PersonEnrichmentRepositoryImpl(
            dao = databaseProvider.current().personEnrichmentDao(),
            logger = logger,
        )

        override fun observeAll(): Flow<List<PersonEnrichmentEntity>> = delegate().observeAll()

        override fun observeEnrichmentMap(): Flow<Map<String, PersonEnrichmentEntity>> =
            delegate().observeEnrichmentMap()

        override fun observeSummary(): Flow<PersonEnrichmentSummary> =
            delegate().observeSummary()

        override fun observeByPersonRef(personRef: String): Flow<PersonEnrichmentEntity?> =
            delegate().observeByPersonRef(personRef)

        override suspend fun findByPersonRef(personRef: String): PersonEnrichmentEntity? =
            delegate().findByPersonRef(personRef)

        override suspend fun upsert(entity: PersonEnrichmentEntity) = delegate().upsert(entity)

        override suspend fun upsertAll(entities: List<PersonEnrichmentEntity>) =
            delegate().upsertAll(entities)

        override suspend fun deleteAll() = delegate().deleteAll()
    }
    private val imapCredentialStore = mockk<com.becalm.android.data.local.secure.ImapCredentialStore>(relaxed = true)
    private val oauthCredentialStore = mockk<OAuthCredentialStore>(relaxed = true)
    private val processRestarter = mockk<ProcessRestarter>()
    private val repository: AuthRepository = AuthRepositoryImpl(
        authClientProvider = Provider { authClient },
        sessionStore = sessionStore,
        tokenProvider = tokenProvider,
        deviceKeyStore = deviceKeyStore,
        syncCursorStore = syncCursorStore,
        userPrefsStore = userPrefsStore,
        databaseProvider = databaseProvider,
        workScheduler = workScheduler,
        contentObserverBootstrap = contentObserverBootstrap,
        personEnrichmentRepository = enrichmentRepository,
        imapCredentialStore = imapCredentialStore,
        oauthCredentialStore = oauthCredentialStore,
        processRestarter = processRestarter,
        ioDispatcher = Dispatchers.IO,
        logger = logger,
    )

    @After
    fun tearDown() {
        databaseProvider.currentUserIdHash()?.let { hash ->
            databaseProvider.close()
            LocalIntegrationSupport.appContext().deleteDatabase(BeCalmDatabase.databaseFilename(hash))
        }
    }

    @Test
    fun `AUTH-008 sign in writes current user id and opens user-scoped database`() = runTest {
        val session = LocalIntegrationSupport.authenticatedSession(userId = USER_ID)
        every { processRestarter.restart() } answers { throw AssertionError("restart not expected") }
        coEvery { authClient.signInWithEmail("user@example.com", "pw") } returns BecalmResult.Success(session)

        repository.observeAuthState().test {
            assertTrue(awaitItem() is AuthState.Unauthenticated)
            val result = repository.signInWithEmail("user@example.com", "pw")
            assertTrue(result is BecalmResult.Success)
            assertEquals(USER_ID, userPrefsStore.observeCurrentUserId().first())
            assertEquals(BeCalmDatabase.deriveUserIdHash(USER_ID), databaseProvider.currentUserIdHash())
            val authenticated = awaitItem()
            assertTrue(authenticated is AuthState.Authenticated)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `AUTH-001A sign up writes current user id and opens user-scoped database`() = runTest {
        val session = LocalIntegrationSupport.authenticatedSession(userId = USER_ID, email = "new@example.com")
        every { processRestarter.restart() } answers { throw AssertionError("restart not expected") }
        coEvery { authClient.signUpWithEmail("new@example.com", "pw") } returns BecalmResult.Success(session)

        repository.observeAuthState().test {
            assertTrue(awaitItem() is AuthState.Unauthenticated)
            val result = repository.signUpWithEmail("new@example.com", "pw")
            assertTrue(result is BecalmResult.Success)
            assertEquals(USER_ID, userPrefsStore.observeCurrentUserId().first())
            assertEquals(BeCalmDatabase.deriveUserIdHash(USER_ID), databaseProvider.currentUserIdHash())
            val authenticated = awaitItem()
            assertTrue(authenticated is AuthState.Authenticated)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `AUTH-005 routine invalidateSession preserves room rows while clearing session mirror`() = runTest {
        val session = LocalIntegrationSupport.authenticatedSession(userId = USER_ID)
        sessionStore.save(session)
        userPrefsStore.setCurrentUserId(USER_ID)
        databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash(USER_ID))
        databaseProvider.current().commitmentDao().insert(
            commitment(id = "local-row", userId = USER_ID),
        )
        coEvery { authClient.signOut(session.accessToken) } returns BecalmResult.Success(Unit)

        val result = repository.invalidateSession()

        assertTrue(result is BecalmResult.Success)
        assertNull(userPrefsStore.observeCurrentUserId().first())
        assertNull(sessionStore.load())
        assertEquals(1, databaseProvider.current().commitmentDao().observeAllForUser(USER_ID).first().size)
        coVerify { authClient.signOut(session.accessToken) }
    }

    @Test
    fun `AUTH-005 signOut fully wipes local room prefs cursors and enrichment rows`() = runTest {
        val session = LocalIntegrationSupport.authenticatedSession(userId = USER_ID)
        val hash = BeCalmDatabase.deriveUserIdHash(USER_ID)
        sessionStore.save(session)
        userPrefsStore.setCurrentUserId(USER_ID)
        userPrefsStore.setOnboardingCompleted(true)
        userPrefsStore.setProcessingPaused(true)
        userPrefsStore.setPauseStartedAt(1_777_000_000_000)
        userPrefsStore.appendPipaActionLog(
            PipaActionLogEntry(
                action = "processing_pause",
                timestampIso = "2026-04-23T00:00:00Z",
            ),
        )
        userPrefsStore.setEmailSourceConnected(EmailPipaProvider.GMAIL, true)
        syncCursorStore.setGmailHistoryId(123L)
        databaseProvider.ensureOpenFor(hash)
        databaseProvider.current().commitmentDao().insert(
            commitment(id = "wipe-row", userId = USER_ID),
        )
        enrichmentRepository.upsert(
            PersonEnrichmentEntity(
                personRef = "+821012345678",
                displayName = "홍길동",
                lastSyncedAt = Instant.parse("2026-04-23T02:00:00Z"),
            ),
        )
        coEvery { authClient.signOut(session.accessToken) } returns BecalmResult.Success(Unit)

        val result = repository.signOut()

        if (result !is BecalmResult.Success) {
            error("signOut result=$result logs=${logger.entries}")
        }
        assertNull(userPrefsStore.observeCurrentUserId().first())
        assertFalse(userPrefsStore.observeOnboardingCompleted().first())
        assertFalse(userPrefsStore.observeProcessingPaused().first())
        assertNull(userPrefsStore.observePauseStartedAt().first())
        assertTrue(userPrefsStore.observePipaActionLog().first().isEmpty())
        assertFalse(userPrefsStore.observeEmailSourceConnected(EmailPipaProvider.GMAIL).first())
        assertNull(syncCursorStore.observeGmailHistoryId().first())
        assertNull(sessionStore.load())
        assertNull(databaseProvider.currentUserIdHash())

        databaseProvider.ensureOpenFor(hash)
        assertEquals(0, databaseProvider.current().commitmentDao().observeAllForUser(USER_ID).first().size)
        assertEquals(0, enrichmentRepository.observeAll().first().size)
        coVerify { authClient.signOut(session.accessToken) }
    }

    private fun commitment(id: String, userId: String): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = userId,
        direction = "give",
        counterpartyRaw = "raw",
        personRef = "person",
        title = "local title",
        description = null,
        quote = "quote",
        sourceEventTitle = "source",
        sourceEventOccurredAt = Instant.parse("2026-04-23T01:00:00Z"),
        dueAt = null,
        dueHint = null,
        sourceType = "manual",
        sourceRef = null,
        confidence = 1.0,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "pending",
        createdAt = Instant.parse("2026-04-23T01:00:00Z"),
        updatedAt = Instant.parse("2026-04-23T01:00:00Z"),
    )

    private class InMemorySessionStore : SupabaseSessionStore {
        private val flow = MutableSharedFlow<SupabaseSession?>(extraBufferCapacity = 1)
        private var current: SupabaseSession? = null

        override suspend fun save(session: SupabaseSession) {
            current = session
            flow.emit(session)
        }

        override suspend fun load(): SupabaseSession? = current

        override suspend fun clear() {
            current = null
            flow.emit(null)
        }

        override fun observe() = flow
    }

    private companion object {
        private const val USER_ID = "user-1"
    }
}
