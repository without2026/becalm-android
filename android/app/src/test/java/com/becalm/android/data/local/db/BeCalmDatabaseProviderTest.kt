package com.becalm.android.data.local.db

import android.app.Application
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.UserPrefsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [BeCalmDatabaseProvider] — the application-scoped guard
 * introduced in S6-A that keys the on-disk SQLite file on the signed-in user's id hash.
 *
 * The production provider builds real SQLite files so we use `RobolectricTestRunner`
 * to get an Android context; every test tears the provider down and clears the
 * temporary app-data directory to stay independent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
public class BeCalmDatabaseProviderTest {

    private lateinit var context: Application
    private lateinit var fakeUserPrefs: FakeUserPrefsStore
    private lateinit var logger: RecordingLogger
    private lateinit var provider: BeCalmDatabaseProvider

    @Before
    public fun setUp() {
        context = RuntimeEnvironment.getApplication()
        fakeUserPrefs = FakeUserPrefsStore()
        logger = RecordingLogger()
        provider = BeCalmDatabaseProvider(context, fakeUserPrefs, logger)
    }

    @After
    public fun tearDown() {
        provider.close()
        for (name in context.databaseList()) {
            context.deleteDatabase(name)
        }
    }

    @Test
    public fun ensureOpenFor_createsUserScopedFile() {
        val hash = BeCalmDatabase.deriveUserIdHash("alice")
        provider.ensureOpenFor(hash)

        // Force Room to materialise the SQLite file on disk — `context.databaseList()`
        // only reports files after the first writable open, which for Room is the first
        // statement execution, not the builder call itself.
        provider.current().openHelper.writableDatabase

        val expected = BeCalmDatabase.databaseFilename(hash)
        assertTrue(
            "expected $expected under app-data: got ${context.databaseList().toList()}",
            context.databaseList().contains(expected),
        )
    }

    @Test
    public fun ensureOpenFor_isIdempotentForSameHash() {
        val hash = BeCalmDatabase.deriveUserIdHash("alice")
        provider.ensureOpenFor(hash)
        val first = provider.current()

        provider.ensureOpenFor(hash)
        val second = provider.current()

        assertEquals("same hash must reuse the existing instance", first, second)
    }

    @Test
    public fun ensureOpenFor_closesPriorWhenUserChanges() {
        val hashA = BeCalmDatabase.deriveUserIdHash("alice")
        val hashB = BeCalmDatabase.deriveUserIdHash("bob")

        provider.ensureOpenFor(hashA)
        val aliceDb = provider.current()
        aliceDb.openHelper.writableDatabase
        assertTrue("alice db must be open", aliceDb.isOpen)

        provider.ensureOpenFor(hashB)
        val bobDb = provider.current()
        bobDb.openHelper.writableDatabase

        assertFalse("alice db must be closed after user swap", aliceDb.isOpen)
        assertTrue("bob db must be open", bobDb.isOpen)
        assertTrue(
            "bob's file must exist on disk",
            context.databaseList().contains(BeCalmDatabase.databaseFilename(hashB)),
        )
        assertTrue(
            "user-swap warning must be emitted to ops logs",
            logger.entries.any {
                it.level == RecordingLogger.Level.W && it.message.contains("user-scope swap")
            },
        )
    }

    @Test
    public fun current_lazilyOpensFromPersistedUserId() {
        fakeUserPrefs.currentUserId.value = "charlie"

        val db = provider.current()
        assertNotNull("lazy bootstrap must materialise a database", db)
        db.openHelper.writableDatabase
        val expected = BeCalmDatabase.databaseFilename(BeCalmDatabase.deriveUserIdHash("charlie"))
        assertTrue(
            "bootstrap must open the file for the persisted user id",
            context.databaseList().contains(expected),
        )
    }

    @Test
    public fun current_throwsWhenNoUserIsSignedIn() {
        fakeUserPrefs.currentUserId.value = null
        assertThrows(IllegalStateException::class.java) {
            provider.current()
        }
    }

    @Test
    public fun close_releasesFileHandleForNextSignIn() {
        val hash = BeCalmDatabase.deriveUserIdHash("alice")
        provider.ensureOpenFor(hash)
        val first = provider.current()
        first.openHelper.writableDatabase

        provider.close()
        assertFalse("close must release the Room file handle", first.isOpen)

        provider.ensureOpenFor(hash)
        val reopened = provider.current()
        reopened.openHelper.writableDatabase
        assertTrue("re-open after close must yield a live database", reopened.isOpen)
    }

    /**
     * Minimal [UserPrefsStore] fake exposing only the `currentUserId` dependency surface
     * that [BeCalmDatabaseProvider] relies on. Every other method throws so a regression
     * that reaches outside the contract fails loudly.
     */
    private class FakeUserPrefsStore : UserPrefsStore {
        val currentUserId: MutableStateFlow<String?> = MutableStateFlow(null)

        override fun observeCurrentUserId(): Flow<String?> = currentUserId.asStateFlow()

        override suspend fun setCurrentUserId(userId: String?) {
            currentUserId.value = userId
        }

        override fun observeOnboardingCompleted(): Flow<Boolean> = unused("observeOnboardingCompleted")
        override suspend fun setOnboardingCompleted(completed: Boolean): Unit = unused("setOnboardingCompleted")
        override fun observeThemeMode(): Flow<String> = unused("observeThemeMode")
        override suspend fun setThemeMode(mode: String): Unit = unused("setThemeMode")
        override fun observeLocaleTag(): Flow<String?> = unused("observeLocaleTag")
        override suspend fun setLocaleTag(tag: String?): Unit = unused("setLocaleTag")
        override fun observeDozePromptDismissedAt(): Flow<Long?> = unused("observeDozePromptDismissedAt")
        override suspend fun setDozePromptDismissedAt(epochMs: Long?): Unit = unused("setDozePromptDismissedAt")
        override fun observeNotificationsEnabled(): Flow<Boolean> = unused("observeNotificationsEnabled")
        override suspend fun setNotificationsEnabled(enabled: Boolean): Unit = unused("setNotificationsEnabled")
        override fun observeThirdPartyProvisionConsent(): Flow<Boolean> =
            unused("observeThirdPartyProvisionConsent")
        override suspend fun setThirdPartyProvisionConsent(granted: Boolean): Unit =
            unused("setThirdPartyProvisionConsent")
        override fun observeTermsAccepted(): Flow<Boolean> = unused("observeTermsAccepted")
        override suspend fun setTermsAccepted(accepted: Boolean): Unit = unused("setTermsAccepted")
        override fun observeEnabledSources(): Flow<Set<String>> = unused("observeEnabledSources")
        override fun observeImapMigrated(): Flow<Boolean> = unused("observeImapMigrated")
        override suspend fun setImapMigrated(value: Boolean): Unit = unused("setImapMigrated")
        override suspend fun clearAll(): Unit = unused("clearAll")

        private fun <T> unused(name: String): T =
            throw UnsupportedOperationException("FakeUserPrefsStore.$name is unused in provider tests")
    }
}
