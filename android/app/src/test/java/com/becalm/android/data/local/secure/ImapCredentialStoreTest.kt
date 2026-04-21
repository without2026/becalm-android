package com.becalm.android.data.local.secure

import android.app.Application
import com.becalm.android.data.remote.dto.SourceType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric unit tests for the per-provider [ImapCredentialStore] schema.
 *
 * These tests pin the ING-011 parallel-execution invariant as a contract: concurrent
 * writes from the Naver and Daum workers must not see each other. [EncryptedSharedPreferences]
 * works under Robolectric from AndroidX Security 1.1.x, so no instrumented harness is needed.
 *
 * Spec refs: `.spec/data-ingestion.spec.yml:155` (parallel-execution invariant),
 * plan `docs/plans/repo-imap-per-provider-credentials.md`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImapCredentialStoreTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var store: ImapCredentialStore
    private lateinit var context: Application

    private val naverCreds = ImapCredentials(
        username = "alice@naver.com",
        appPassword = "naver-app-pw",
        host = "imap.naver.com",
        port = 993,
    )

    private val daumCreds = ImapCredentials(
        username = "bob@daum.net",
        appPassword = "daum-app-pw",
        host = "imap.daum.net",
        port = 993,
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Robolectric gives each test its own data dir, so an explicit wipe is not required.
        store = ImapCredentialStore(context = context, ioDispatcher = dispatcher)
    }

    @After
    fun tearDown() {
        // Scrub the backing file so a test that succeeds partially cannot leak state
        // into a sibling test through Robolectric's shared application context.
        context.deleteSharedPreferences(ImapCredentialStore.FILE_NAME)
    }

    @Test
    fun `save NAVER does not overwrite DAUM slot`() = runTest(dispatcher) {
        store.save(SourceType.DAUM_IMAP, daumCreds)
        store.save(SourceType.NAVER_IMAP, naverCreds)

        val loadedDaum = store.load(SourceType.DAUM_IMAP)
        assertNotNull(loadedDaum)
        assertEquals(daumCreds, loadedDaum)
    }

    @Test
    fun `save DAUM does not overwrite NAVER slot`() = runTest(dispatcher) {
        store.save(SourceType.NAVER_IMAP, naverCreds)
        store.save(SourceType.DAUM_IMAP, daumCreds)

        val loadedNaver = store.load(SourceType.NAVER_IMAP)
        assertNotNull(loadedNaver)
        assertEquals(naverCreds, loadedNaver)
    }

    @Test
    fun `clear NAVER leaves DAUM intact`() = runTest(dispatcher) {
        store.save(SourceType.NAVER_IMAP, naverCreds)
        store.save(SourceType.DAUM_IMAP, daumCreds)

        store.clear(SourceType.NAVER_IMAP)

        assertNull(store.load(SourceType.NAVER_IMAP))
        assertEquals(daumCreds, store.load(SourceType.DAUM_IMAP))
    }

    @Test
    fun `clearAll removes both providers`() = runTest(dispatcher) {
        store.save(SourceType.NAVER_IMAP, naverCreds)
        store.save(SourceType.DAUM_IMAP, daumCreds)

        store.clearAll()

        assertNull(store.load(SourceType.NAVER_IMAP))
        assertNull(store.load(SourceType.DAUM_IMAP))
    }

    @Test
    fun `clearAll wipes legacy pre-wave-2 imap keys so migrator cannot revive them`() =
        runTest(dispatcher) {
            // Sign-out wipe must cover the pre-refactor single-tuple layout too.
            // Otherwise an upgraded device with unmigrated legacy keys could have them
            // survive logout and be promoted into a namespaced slot on the next user's
            // session via [ImapCredentialStoreMigrator] — a cross-account credential leak.
            val legacyPrefs = buildStorePrefs(
                context,
                ImapCredentialStore.FILE_NAME,
                ImapCredentialStore.MASTER_KEY_ALIAS,
                "ImapCredentialStoreTest",
            )
            legacyPrefs.edit()
                .putString("imap_username", "legacy@naver.com")
                .putString("imap_app_password", "legacy-pw")
                .putString("imap_host", "imap.naver.com")
                .putInt("imap_port", 993)
                .commit()
            // Also seed a namespaced slot; both layers must be wiped.
            store.save(SourceType.DAUM_IMAP, daumCreds)

            store.clearAll()

            val postWipe = buildStorePrefs(
                context,
                ImapCredentialStore.FILE_NAME,
                ImapCredentialStore.MASTER_KEY_ALIAS,
                "ImapCredentialStoreTest",
            )
            assertNull(postWipe.getString("imap_username", null))
            assertNull(postWipe.getString("imap_app_password", null))
            assertNull(postWipe.getString("imap_host", null))
            // Legacy int keys: `contains` is the correct absence check since getInt
            // returns a default (0) for missing keys regardless of whether the slot
            // previously held a value.
            assertTrue("legacy imap_port key must be removed", !postWipe.contains("imap_port"))
            assertNull(store.load(SourceType.DAUM_IMAP))
        }

    @Test
    fun `save with invalid sourceType throws IllegalArgumentException`() = runTest(dispatcher) {
        try {
            store.save("gmail", naverCreds)
            fail("expected IllegalArgumentException for unknown sourceType")
        } catch (e: IllegalArgumentException) {
            // message must help operators find the offending wiring quickly.
            assertEquals("unknown IMAP sourceType: gmail", e.message)
        }
    }

    @Test
    fun `load NAVER returns null when empty`() = runTest(dispatcher) {
        assertNull(store.load(SourceType.NAVER_IMAP))
    }

    @Test
    fun `load with invalid sourceType throws IllegalArgumentException`() = runTest(dispatcher) {
        try {
            store.load("outlook_mail")
            fail("expected IllegalArgumentException for unknown sourceType")
        } catch (e: IllegalArgumentException) {
            assertEquals("unknown IMAP sourceType: outlook_mail", e.message)
        }
    }

    @Test
    fun `clear with invalid sourceType throws IllegalArgumentException`() = runTest(dispatcher) {
        try {
            store.clear("voice")
            fail("expected IllegalArgumentException for unknown sourceType")
        } catch (e: IllegalArgumentException) {
            assertEquals("unknown IMAP sourceType: voice", e.message)
        }
    }

    @Test
    fun `overwriting the same NAVER slot returns the new value`() = runTest(dispatcher) {
        store.save(SourceType.NAVER_IMAP, naverCreds)

        val updated = naverCreds.copy(appPassword = "naver-rotated")
        store.save(SourceType.NAVER_IMAP, updated)

        assertEquals(updated, store.load(SourceType.NAVER_IMAP))
    }

    // ─── ING-011 parallel-execution invariant ─────────────────────────────────

    /**
     * Concurrent-workers regression: the real production sequence is that
     * `ImapNaverWorker` and `ImapDaumWorker` run in parallel under WorkManager, each
     * calling `ImapCredentialStore.load(<their own sourceType>)`. This test drives
     * that exact interleaving via `coroutineScope { launch { … } launch { … } }` and
     * asserts each branch reads only its own namespace. If the store ever regressed
     * to the single-tuple layout, both loads would return the same credential and
     * this would catch it.
     *
     * Plan: `docs/plans/repo-imap-per-provider-credentials.md` §6 — "Integration
     * test — ImapConcurrentWorkersTest — concurrent run of ImapNaverWorker and
     * ImapDaumWorker reads only their own namespace".
     */
    @Test
    fun `concurrent load of NAVER and DAUM returns each provider's own credentials`() =
        runTest(dispatcher) {
            store.save(SourceType.NAVER_IMAP, naverCreds)
            store.save(SourceType.DAUM_IMAP, daumCreds)

            val results = mutableMapOf<String, ImapCredentials?>()
            kotlinx.coroutines.coroutineScope {
                repeat(20) {
                    launch {
                        results[SourceType.NAVER_IMAP] = store.load(SourceType.NAVER_IMAP)
                    }
                    launch {
                        results[SourceType.DAUM_IMAP] = store.load(SourceType.DAUM_IMAP)
                    }
                }
            }

            assertEquals(naverCreds, results[SourceType.NAVER_IMAP])
            assertEquals(daumCreds, results[SourceType.DAUM_IMAP])
            // Negative check: each provider's saved host must not be visible from the
            // sibling's namespace — the primary data-poisoning vector the refactor closes.
            assertEquals("imap.naver.com", results[SourceType.NAVER_IMAP]?.host)
            assertEquals("imap.daum.net", results[SourceType.DAUM_IMAP]?.host)
        }
}
