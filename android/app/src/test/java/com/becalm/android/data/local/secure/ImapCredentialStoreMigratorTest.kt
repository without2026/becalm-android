package com.becalm.android.data.local.secure

import android.app.Application
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.dto.SourceType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [ImapCredentialStoreMigrator].
 *
 * These exercise the one-shot upgrade path from the legacy single-tuple IMAP
 * credentials (pre-wave-2) to the per-provider namespaced schema. Covered invariants:
 *
 * - `imap.naver.com*` host migrates to the `naver_imap_*` namespace and drops the legacy keys.
 * - `imap.daum.net*` host migrates to the `daum_imap_*` namespace and drops the legacy keys.
 * - Idempotency: once the flag is set, a second `migrateIfNeeded()` is a true no-op.
 * - Unknown host defaults to Naver and logs an `imap_migration_unknown_host` warning.
 * - Legacy keys missing: flag is still set (idempotent no-op) so we don't retry forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImapCredentialStoreMigratorTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var context: Application
    private lateinit var userPrefsStore: UserPrefsStore
    private lateinit var logger: Logger
    private lateinit var migrator: ImapCredentialStoreMigrator

    // In-memory simulation of the DataStore flag so tests can assert both reads and writes.
    private val migratedFlagFlow = MutableStateFlow(false)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        userPrefsStore = mockk(relaxed = true)
        logger = mockk(relaxed = true)

        every { userPrefsStore.observeImapMigrated() } answers { migratedFlagFlow }
        coEvery { userPrefsStore.setImapMigrated(any()) } answers {
            migratedFlagFlow.value = firstArg()
        }

        migrator = ImapCredentialStoreMigrator(
            context = context,
            ioDispatcher = dispatcher,
            userPrefsStore = userPrefsStore,
            logger = logger,
        )
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(ImapCredentialStore.FILE_NAME)
        migratedFlagFlow.value = false
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private fun seedLegacyTuple(username: String, password: String, host: String, port: Int) {
        val prefs = buildStorePrefs(
            context,
            ImapCredentialStore.FILE_NAME,
            ImapCredentialStore.MASTER_KEY_ALIAS,
            "ImapCredentialStoreMigratorTest",
        )
        prefs.edit()
            .putString(ImapCredentialStoreMigrator.LEGACY_KEY_USERNAME, username)
            .putString(ImapCredentialStoreMigrator.LEGACY_KEY_APP_PASSWORD, password)
            .putString(ImapCredentialStoreMigrator.LEGACY_KEY_HOST, host)
            .putInt(ImapCredentialStoreMigrator.LEGACY_KEY_PORT, port)
            .apply()
    }

    private fun readString(key: String): String? {
        val prefs = buildStorePrefs(
            context,
            ImapCredentialStore.FILE_NAME,
            ImapCredentialStore.MASTER_KEY_ALIAS,
            "ImapCredentialStoreMigratorTest",
        )
        return prefs.getString(key, null)
    }

    private fun readInt(key: String): Int? {
        val prefs = buildStorePrefs(
            context,
            ImapCredentialStore.FILE_NAME,
            ImapCredentialStore.MASTER_KEY_ALIAS,
            "ImapCredentialStoreMigratorTest",
        )
        return if (prefs.contains(key)) prefs.getInt(key, 0) else null
    }

    // ─── tests ──────────────────────────────────────────────────────────────

    @Test
    fun `legacy tuple with naver host migrates to naver_imap namespace`() = runTest(dispatcher) {
        seedLegacyTuple(
            username = "alice@naver.com",
            password = "naver-pw",
            host = "imap.naver.com",
            port = 993,
        )

        migrator.migrateIfNeeded()

        // Namespaced keys present
        assertEquals("alice@naver.com", readString("${SourceType.NAVER_IMAP}_username"))
        assertEquals("naver-pw", readString("${SourceType.NAVER_IMAP}_password"))
        assertEquals("imap.naver.com", readString("${SourceType.NAVER_IMAP}_host"))
        assertEquals(993, readInt("${SourceType.NAVER_IMAP}_port"))

        // Legacy keys removed
        assertNull(readString(ImapCredentialStoreMigrator.LEGACY_KEY_USERNAME))
        assertNull(readString(ImapCredentialStoreMigrator.LEGACY_KEY_APP_PASSWORD))
        assertNull(readString(ImapCredentialStoreMigrator.LEGACY_KEY_HOST))
        assertNull(readInt(ImapCredentialStoreMigrator.LEGACY_KEY_PORT))

        // Flag set
        assertTrue(migratedFlagFlow.value)
        coVerify { userPrefsStore.setImapMigrated(true) }
    }

    @Test
    fun `legacy tuple with daum host migrates to daum_imap namespace`() = runTest(dispatcher) {
        seedLegacyTuple(
            username = "bob@daum.net",
            password = "daum-pw",
            host = "imap.daum.net",
            port = 993,
        )

        migrator.migrateIfNeeded()

        assertEquals("bob@daum.net", readString("${SourceType.DAUM_IMAP}_username"))
        assertEquals("daum-pw", readString("${SourceType.DAUM_IMAP}_password"))
        assertEquals("imap.daum.net", readString("${SourceType.DAUM_IMAP}_host"))
        assertEquals(993, readInt("${SourceType.DAUM_IMAP}_port"))

        // Naver namespace must remain untouched.
        assertNull(readString("${SourceType.NAVER_IMAP}_username"))

        // Legacy keys removed
        assertNull(readString(ImapCredentialStoreMigrator.LEGACY_KEY_USERNAME))

        assertTrue(migratedFlagFlow.value)
    }

    @Test
    fun `running twice is idempotent`() = runTest(dispatcher) {
        seedLegacyTuple(
            username = "alice@naver.com",
            password = "naver-pw",
            host = "imap.naver.com",
            port = 993,
        )

        migrator.migrateIfNeeded()

        // After the first run, seed one more fake legacy key to confirm the 2nd run does
        // NOT touch the store (flag short-circuits the body).
        val prefs = buildStorePrefs(
            context,
            ImapCredentialStore.FILE_NAME,
            ImapCredentialStore.MASTER_KEY_ALIAS,
            "ImapCredentialStoreMigratorTest",
        )
        prefs.edit()
            .putString(ImapCredentialStoreMigrator.LEGACY_KEY_USERNAME, "should-stay")
            .apply()

        migrator.migrateIfNeeded()

        // The sentinel remains — migrator did not run its body a 2nd time.
        assertEquals("should-stay", readString(ImapCredentialStoreMigrator.LEGACY_KEY_USERNAME))
        assertTrue(migratedFlagFlow.value)

        // Flag set exactly once (first run). Second call short-circuits, no extra write.
        coVerify(exactly = 1) { userPrefsStore.setImapMigrated(true) }
    }

    @Test
    fun `unknown host leaves legacy tuple in place and does not set migrated flag`() = runTest(dispatcher) {
        // Regression for the Wave-2 review finding: silently coercing unknown hosts into
        // the Naver namespace renders Daum-ish or typo'd tuples unreachable (the Daum
        // worker hard-codes its own host and would hit AUTH failure every run). The
        // correct behaviour is: log, leave legacy tuple intact, keep flag false so the
        // next app launch retries once the user corrects the host via onboarding.
        seedLegacyTuple(
            username = "mallory@example.com",
            password = "unknown-pw",
            host = "imap.unknown-provider.io",
            port = 143,
        )

        migrator.migrateIfNeeded()

        // Legacy tuple MUST remain intact for manual recovery.
        assertEquals(
            "mallory@example.com",
            readString(ImapCredentialStoreMigrator.LEGACY_KEY_USERNAME),
        )
        assertEquals(
            "imap.unknown-provider.io",
            readString(ImapCredentialStoreMigrator.LEGACY_KEY_HOST),
        )
        assertEquals(143, readInt(ImapCredentialStoreMigrator.LEGACY_KEY_PORT))

        // Namespaced keys NOT written for either provider.
        assertNull(readString("${SourceType.NAVER_IMAP}_username"))
        assertNull(readString("${SourceType.DAUM_IMAP}_username"))

        // Warning log recorded with the agreed tag substring so ops can correlate.
        verify {
            logger.w(
                ImapCredentialStoreMigrator.TAG,
                match { it.contains("imap_migration_unknown_host") },
                null,
            )
        }

        // Flag MUST remain false so the migration retries on next launch.
        assertFalse(migratedFlagFlow.value)
        coVerify(exactly = 0) { userPrefsStore.setImapMigrated(true) }
    }

    @Test
    fun `missing legacy keys still set flag for idempotent no-op`() = runTest(dispatcher) {
        // No legacy tuple seeded — simulates a fresh install after the refactor lands.

        migrator.migrateIfNeeded()

        assertTrue(migratedFlagFlow.value)
        // Nothing was written to either namespace.
        assertNull(readString("${SourceType.NAVER_IMAP}_username"))
        assertNull(readString("${SourceType.DAUM_IMAP}_username"))
    }

    @Test
    fun `already migrated flag short-circuits before touching prefs`() = runTest(dispatcher) {
        // Pre-set the flag before seeding — migrator should not even open the prefs file.
        migratedFlagFlow.value = true

        // Seed legacy data. A correct migrator must leave it alone.
        seedLegacyTuple(
            username = "stale@naver.com",
            password = "stale-pw",
            host = "imap.naver.com",
            port = 993,
        )

        migrator.migrateIfNeeded()

        // Legacy keys untouched.
        assertEquals("stale@naver.com", readString(ImapCredentialStoreMigrator.LEGACY_KEY_USERNAME))
        // Namespaced keys NOT written.
        assertNull(readString("${SourceType.NAVER_IMAP}_username"))

        // setImapMigrated was never called because we short-circuited.
        coVerify(exactly = 0) { userPrefsStore.setImapMigrated(any()) }
    }
}
