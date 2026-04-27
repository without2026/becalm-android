package com.becalm.android.unit.worker

import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.BeCalmDatabaseProvider
import com.becalm.android.data.local.secure.ImapCredentialStoreMigrator
import com.becalm.android.worker.AppRuntimeSyncCoordinator
import com.becalm.android.worker.AuthenticatedRuntimeBootstrap
import com.becalm.android.worker.WorkScheduler
import io.mockk.coVerify
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AuthenticatedRuntimeBootstrapSpecTest {

    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val imapCredentialStoreMigrator: ImapCredentialStoreMigrator = mockk(relaxed = true)
    private val syncCursorStore: SyncCursorStore = mockk(relaxed = true)
    private val databaseProvider: BeCalmDatabaseProvider = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val appRuntimeSyncCoordinator: AppRuntimeSyncCoordinator = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun `AUTH-009 pre-auth startup does not touch Room or WorkManager runtime paths`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf(null)

        buildBootstrap().startIfSignedIn()

        coVerify(exactly = 0) { imapCredentialStoreMigrator.migrateIfNeeded() }
        coVerify(exactly = 0) { syncCursorStore.runOutlookMailCursorMigrationV2() }
        coVerify(exactly = 0) { syncCursorStore.runImapCursorMigrationV2() }
        verify(exactly = 0) { databaseProvider.ensureOpenFor(any()) }
        verify(exactly = 0) { workScheduler.cleanupLegacyWorkNames() }
        verify(exactly = 0) { appRuntimeSyncCoordinator.start() }
    }

    @Test
    fun `AUTH-009 signed-in startup warms database and starts runtime sync off main thread`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { databaseProvider.ensureOpenFor(any()) } just Runs

        buildBootstrap().startIfSignedIn()

        coVerify(exactly = 1) { imapCredentialStoreMigrator.migrateIfNeeded() }
        coVerify(exactly = 1) { syncCursorStore.runOutlookMailCursorMigrationV2() }
        coVerify(exactly = 1) { syncCursorStore.runImapCursorMigrationV2() }
        verify(exactly = 1) {
            databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash("user-1"))
        }
        verify(exactly = 1) { workScheduler.cleanupLegacyWorkNames() }
        verify(exactly = 1) { appRuntimeSyncCoordinator.start() }
    }

    @Test
    fun `AUTH-009 persisted user read failure defers runtime sync without opening Room`() = runTest {
        val failure = IllegalStateException("datastore unavailable")
        every { userPrefsStore.observeCurrentUserId() } returns flow { throw failure }

        buildBootstrap().startIfSignedIn()

        coVerify(exactly = 0) { imapCredentialStoreMigrator.migrateIfNeeded() }
        coVerify(exactly = 0) { syncCursorStore.runOutlookMailCursorMigrationV2() }
        coVerify(exactly = 0) { syncCursorStore.runImapCursorMigrationV2() }
        verify(exactly = 0) { databaseProvider.ensureOpenFor(any()) }
        verify(exactly = 0) { workScheduler.cleanupLegacyWorkNames() }
        verify(exactly = 0) { appRuntimeSyncCoordinator.start() }
    }

    @Test
    fun `AUTH-009 signed-in runtime bootstrap is idempotent per user`() = runTest {
        every { databaseProvider.ensureOpenFor(any()) } just Runs
        val bootstrap = buildBootstrap()

        bootstrap.startForUser("user-1")
        bootstrap.startForUser("user-1")

        coVerify(exactly = 1) { imapCredentialStoreMigrator.migrateIfNeeded() }
        coVerify(exactly = 1) { syncCursorStore.runOutlookMailCursorMigrationV2() }
        coVerify(exactly = 1) { syncCursorStore.runImapCursorMigrationV2() }
        verify(exactly = 1) {
            databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash("user-1"))
        }
        verify(exactly = 1) { workScheduler.cleanupLegacyWorkNames() }
        verify(exactly = 1) { appRuntimeSyncCoordinator.start() }
    }

    private fun buildBootstrap(): AuthenticatedRuntimeBootstrap = AuthenticatedRuntimeBootstrap(
        userPrefsStore = userPrefsStore,
        imapCredentialStoreMigrator = imapCredentialStoreMigrator,
        syncCursorStore = syncCursorStore,
        databaseProvider = databaseProvider,
        workScheduler = workScheduler,
        appRuntimeSyncCoordinator = appRuntimeSyncCoordinator,
        logger = logger,
        ioDispatcher = Dispatchers.Unconfined,
        mainDispatcher = Dispatchers.Unconfined,
    )
}
