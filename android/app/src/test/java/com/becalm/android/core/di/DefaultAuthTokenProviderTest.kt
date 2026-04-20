package com.becalm.android.core.di

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.remote.supabase.SupabaseAuthClient
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [DefaultAuthTokenProvider].
 *
 * Spec ref: docs/round6-plan.md § 6A.4 — hot-path token cache + refresh dedup.
 *
 * Test cases:
 * 1. 10 concurrent cold [currentAccessToken] → exactly 1 [SupabaseSessionStore.load] call.
 * 2. After [invalidate], the next [currentAccessToken] hits disk exactly once more.
 * 3. 10 concurrent [refresh] → exactly 1 [SupabaseAuthClient.refresh] call; all waiters
 *    receive the same new token.
 * 4. [refresh] returning null → cache not poisoned; waiters see `null`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAuthTokenProviderTest {

    private val logger = RecordingLogger()

    private fun sampleSession(accessToken: String = "access-1") = SupabaseSession(
        accessToken = accessToken,
        refreshToken = "refresh-1",
        userId = "u-1",
        email = "a@b",
        expiresAt = Instant.fromEpochMilliseconds(0L),
    )

    /**
     * Counting fake that records the number of [load] invocations so tests can assert
     * the cache coalesces concurrent cold reads into a single disk hit. When [entryGate]
     * is supplied, [load] suspends on that mutex — allowing a test to force many
     * concurrent callers to observe the "cold" window simultaneously.
     */
    private class CountingSessionStore(
        private val session: SupabaseSession?,
        private val entryGate: Mutex? = null,
    ) : SupabaseSessionStore {
        val loadCount: AtomicInteger = AtomicInteger(0)
        override suspend fun save(session: SupabaseSession) = Unit
        override suspend fun load(): SupabaseSession? {
            loadCount.incrementAndGet()
            entryGate?.lock()
            try {
                return session
            } finally {
                entryGate?.unlock()
            }
        }
        override suspend fun clear() = Unit
    }

    /**
     * Counting fake that records refresh calls and optionally gates them on a mutex so
     * tests can observe the in-flight window.
     */
    private class CountingAuthClient(
        private val result: BecalmResult<SupabaseSession>,
        private val entryGate: Mutex? = null,
    ) : SupabaseAuthClient {
        val refreshCount: AtomicInteger = AtomicInteger(0)
        private val notUsed: BecalmResult<SupabaseSession> = BecalmResult.Failure(
            com.becalm.android.core.result.BecalmError.Unknown(
                IllegalStateException("not used in this test")
            )
        )
        override suspend fun signInWithEmail(email: String, password: String) = notUsed
        override suspend fun signInWithGoogleIdToken(idToken: String) = notUsed
        override suspend fun refresh(refreshToken: String): BecalmResult<SupabaseSession> {
            refreshCount.incrementAndGet()
            entryGate?.lock()
            try {
                return result
            } finally {
                entryGate?.unlock()
            }
        }
        override suspend fun signOut(accessToken: String) = BecalmResult.Success(Unit)
    }

    @Test
    fun `10 concurrent currentAccessToken calls on cold cache trigger exactly 1 disk load`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            // Gate the disk read so all 10 callers are guaranteed to pile up on the cold
            // primeMutex before the first load completes.
            val loadGate = Mutex(locked = true)
            val store = CountingSessionStore(sampleSession("access-1"), entryGate = loadGate)
            val authClient = CountingAuthClient(
                BecalmResult.Success(sampleSession("access-2"))
            )
            val provider = DefaultAuthTokenProvider(
                authClient = authClient,
                sessionStore = store,
                ioDispatcher = dispatcher,
                logger = logger,
            )

            val waiters = (1..10).map {
                async(dispatcher) { provider.primeCache() }
            }
            // All 10 callers dispatched; first one is now suspended inside sessionStore.load(),
            // the other 9 are queued on primeMutex.
            advanceUntilIdle()
            loadGate.unlock()
            advanceUntilIdle()

            val results = waiters.awaitAll()
            assertEquals(
                "All 10 concurrent cold callers must coalesce to exactly 1 sessionStore.load",
                1,
                store.loadCount.get(),
            )
            results.forEach { assertEquals("access-1", it) }
        }

    @Test
    fun `invalidate forces next currentAccessToken to hit disk again`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = CountingSessionStore(sampleSession("access-1"))
        val authClient = CountingAuthClient(BecalmResult.Success(sampleSession("access-2")))
        val provider = DefaultAuthTokenProvider(
            authClient = authClient,
            sessionStore = store,
            ioDispatcher = dispatcher,
            logger = logger,
        )

        provider.primeCache()
        advanceUntilIdle()
        assertEquals(1, store.loadCount.get())

        // Second call is cache-hit → no additional disk read.
        provider.primeCache()
        advanceUntilIdle()
        assertEquals(
            "A warmed cache must not re-read disk",
            1,
            store.loadCount.get(),
        )

        provider.invalidate()

        // Post-invalidate read → exactly one more disk load.
        provider.primeCache()
        advanceUntilIdle()
        assertEquals(
            "After invalidate the next call must hit disk exactly once",
            2,
            store.loadCount.get(),
        )
    }

    @Test
    fun `10 concurrent refresh calls trigger exactly 1 authClient refresh`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = CountingSessionStore(sampleSession("access-1"))
        val entryGate = Mutex(locked = true)
        val authClient = CountingAuthClient(
            result = BecalmResult.Success(sampleSession("access-2")),
            entryGate = entryGate,
        )
        val provider = DefaultAuthTokenProvider(
            authClient = authClient,
            sessionStore = store,
            ioDispatcher = dispatcher,
            logger = logger,
        )

        // Launch 10 concurrent refreshes. They all serialize on refreshMutex and then
        // await the single in-flight Deferred; the gate keeps that Deferred suspended
        // long enough for all 10 to observe and piggy-back on it.
        val waiters = (1..10).map {
            async(dispatcher) { provider.refresh() }
        }
        // Let the first refresh acquire the in-flight slot and call authClient.refresh().
        advanceUntilIdle()
        // All waiters should now be parked on the same Deferred. Release the gate so the
        // single in-flight refresh can complete.
        entryGate.unlock()
        advanceUntilIdle()

        val results = waiters.awaitAll()
        assertEquals(
            "Thundering herd of 10 concurrent 401s must produce exactly 1 upstream refresh",
            1,
            authClient.refreshCount.get(),
        )
        results.forEach { assertEquals("access-2", it) }
    }

    @Test
    fun `refresh returning null propagates null to all waiters and clears in-flight`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = CountingSessionStore(sampleSession("access-1"))
        val entryGate = Mutex(locked = true)
        val authClient = CountingAuthClient(
            result = BecalmResult.Failure(com.becalm.android.core.result.BecalmError.Unauthorized),
            entryGate = entryGate,
        )
        val provider = DefaultAuthTokenProvider(
            authClient = authClient,
            sessionStore = store,
            ioDispatcher = dispatcher,
            logger = logger,
        )

        val waiters = (1..5).map {
            async(dispatcher) { provider.refresh() }
        }
        // All 5 callers enter and piggy-back on the single in-flight Deferred while the
        // upstream call is suspended on the gate.
        advanceUntilIdle()
        entryGate.unlock()
        advanceUntilIdle()

        waiters.awaitAll().forEach {
            assertNull("failed refresh must return null to all waiters", it)
        }
        assertEquals(
            "Failed thundering-herd refresh must still coalesce to exactly 1 upstream call",
            1,
            authClient.refreshCount.get(),
        )

        // A subsequent refresh must be allowed to retry — inFlight is cleared on completion.
        // Lock the gate again so the second refresh also parks (so we can observe the count).
        entryGate.tryLock()
        val second = async(dispatcher) { provider.refresh() }
        advanceUntilIdle()
        entryGate.unlock()
        advanceUntilIdle()
        assertNull(second.await())
        assertEquals(
            "Post-completion inFlight must be cleared so later refreshes can run",
            2,
            authClient.refreshCount.get(),
        )
    }

    /**
     * Regression test for round6-plan § 6D.2.
     *
     * Adversarial scenario: N callers piggy-back on the same in-flight refresh Deferred.
     * If a *waiter* (not the owner that created the async) is cancelled while the shared
     * refresh is still running, its `finally` block must NOT clear the shared `inFlight`
     * slot — doing so would let the next caller spawn a second upstream refresh and break
     * the "1 upstream call per thundering herd" invariant.
     *
     * Setup: 10 concurrent refreshes gated on a mutex. Cancel 9 waiters before the gate
     * opens. Let the 10th (owner) finish. Then fire a fresh refresh. Assert only 2 total
     * upstream refreshes (1 for the herd + 1 for the post-herd call), not 3+.
     */
    @Test
    fun `cancelled waiters do not clear inFlight slot causing duplicate upstream refresh`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = CountingSessionStore(sampleSession("access-1"))
            val entryGate = Mutex(locked = true)
            val authClient = CountingAuthClient(
                result = BecalmResult.Success(sampleSession("access-2")),
                entryGate = entryGate,
            )
            val provider = DefaultAuthTokenProvider(
                authClient = authClient,
                sessionStore = store,
                ioDispatcher = dispatcher,
                logger = logger,
            )

            // Fire 10 concurrent refreshes. First one into refreshMutex becomes the owner
            // of the async { doRefresh() }; the other 9 piggy-back on the same Deferred.
            val all = (1..10).map {
                async(dispatcher) {
                    try {
                        provider.refresh()
                    } catch (e: CancellationException) {
                        null
                    }
                }
            }
            // Let all 10 enter refresh(), serialize on refreshMutex, and park on
            // deferred.await(). The upstream refresh is suspended on entryGate.
            advanceUntilIdle()
            assertEquals(
                "Exactly one upstream refresh should have started before any cancellations",
                1,
                authClient.refreshCount.get(),
            )

            // Cancel 9 waiters while the shared Deferred is still running. Pre-fix, each
            // cancelled waiter's `finally` block wiped `inFlight` because
            // `inFlight === deferred` still held — so the NEXT refresh() would spawn a
            // second async { doRefresh() }.
            all.drop(1).forEach { it.cancel() }
            advanceUntilIdle()
            assertEquals(
                "Cancelled waiters must not trigger extra upstream refreshes",
                1,
                authClient.refreshCount.get(),
            )

            // Owner completes.
            entryGate.unlock()
            advanceUntilIdle()
            assertEquals("access-2", all.first().await())

            // Post-herd refresh must be allowed to run exactly one new upstream call.
            entryGate.tryLock()
            val post = async(dispatcher) { provider.refresh() }
            advanceUntilIdle()
            entryGate.unlock()
            advanceUntilIdle()
            assertEquals("access-2", post.await())

            assertEquals(
                "Thundering herd (1) + post-herd fresh call (1) = exactly 2 upstream refreshes. " +
                    "Pre-fix this was 3+ because cancelled waiters wiped the inFlight slot.",
                2,
                authClient.refreshCount.get(),
            )
        }

    @Test
    fun `refresh updates cached token so subsequent currentAccessToken returns new value`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = CountingSessionStore(sampleSession("access-1"))
            val authClient = CountingAuthClient(
                result = BecalmResult.Success(sampleSession("access-after-refresh"))
            )
            val provider = DefaultAuthTokenProvider(
                authClient = authClient,
                sessionStore = store,
                ioDispatcher = dispatcher,
                logger = logger,
            )
            // Warm cache from disk.
            provider.primeCache()
            advanceUntilIdle()
            assertEquals("access-1", provider.currentAccessToken())

            // Refresh; doRefresh() writes directly into cachedToken on success.
            assertEquals("access-after-refresh", provider.refresh())
            advanceUntilIdle()

            // Hot-path read now returns the refreshed token without touching disk again.
            val loadsBefore = store.loadCount.get()
            assertEquals("access-after-refresh", provider.currentAccessToken())
            assertEquals(
                "Hot-path read after refresh must not hit disk",
                loadsBefore,
                store.loadCount.get(),
            )
        }
}
