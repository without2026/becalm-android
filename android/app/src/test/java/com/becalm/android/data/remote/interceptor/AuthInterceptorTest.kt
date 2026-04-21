package com.becalm.android.data.remote.interceptor

import com.becalm.android.core.di.DefaultAuthTokenProvider
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.remote.supabase.SupabaseAuthClient
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.Instant
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration tests for [AuthInterceptor] + [DefaultAuthTokenProvider] against a
 * [MockWebServer] — specifically the Round 6A.4 thundering-herd behaviour on HTTP 401.
 *
 * The interceptor remains synchronous (OkHttp contract) and uses
 * `runBlocking { tokenProvider.refresh() }`. Dedup lives inside
 * [DefaultAuthTokenProvider.refresh] via a mutex + in-flight Deferred. These tests prove
 * that when N requests all hit 401 simultaneously, [SupabaseAuthClient.refresh] is invoked
 * exactly once and every caller observes the same result.
 */
class AuthInterceptorTest {

    private lateinit var server: MockWebServer
    private val logger = RecordingLogger()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sampleSession(accessToken: String) = SupabaseSession(
        accessToken = accessToken,
        refreshToken = "refresh-1",
        userId = "u-1",
        email = "a@b",
        expiresAt = Instant.fromEpochMilliseconds(0L),
    )

    private class FakeSessionStore(initial: SupabaseSession?) : SupabaseSessionStore {
        @Volatile private var current: SupabaseSession? = initial
        private val changes = kotlinx.coroutines.flow.MutableSharedFlow<SupabaseSession?>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
        override suspend fun save(session: SupabaseSession) {
            current = session
            changes.tryEmit(session)
        }
        override suspend fun load(): SupabaseSession? = current
        override suspend fun clear() {
            current = null
            changes.tryEmit(null)
        }
        override fun observe(): kotlinx.coroutines.flow.SharedFlow<SupabaseSession?> =
            changes.asSharedFlow()
    }

    private class CountingAuthClient(
        private val result: BecalmResult<SupabaseSession>,
        private val entryGate: Mutex? = null,
    ) : SupabaseAuthClient {
        val refreshCount: AtomicInteger = AtomicInteger(0)
        private val notUsed: BecalmResult<SupabaseSession> =
            BecalmResult.Failure(BecalmError.Unknown(IllegalStateException("unused")))
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
    fun `10 concurrent 401 responses trigger exactly 1 upstream refresh call`() {
        // First 10 responses are 401; next 10 are 200 (served after the successful refresh
        // completes and the interceptor retries each request).
        repeat(10) { server.enqueue(MockResponse().setResponseCode(401).setBody("expired")) }
        repeat(10) { server.enqueue(MockResponse().setResponseCode(200).setBody("ok")) }

        // Gate the upstream auth-client refresh so it suspends long enough for all 10
        // interceptor threads to pile up on the single in-flight Deferred.
        val gate = Mutex(locked = true)
        val sessionStore = FakeSessionStore(sampleSession("stale-token"))
        val authClient = CountingAuthClient(
            result = BecalmResult.Success(sampleSession("fresh-token")),
            entryGate = gate,
        )
        val provider = DefaultAuthTokenProvider(
            authClient = authClient,
            sessionStore = sessionStore,
            ioDispatcher = Dispatchers.IO,
            logger = logger,
        )

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(provider, server.hostName))
            .build()

        // Release the gate after a brief pause to ensure all 10 threads have entered
        // runBlocking { refresh() } and registered their await() on the single Deferred.
        val releaser = Executors.newSingleThreadScheduledExecutor()
        releaser.schedule({ runBlocking { gate.unlock() } }, 300, TimeUnit.MILLISECONDS)

        val executor = Executors.newFixedThreadPool(10)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(10)
        val responseCodes = mutableListOf<Int>()
        val responseCodesLock = Any()

        repeat(10) {
            executor.submit {
                startLatch.await()
                val request = Request.Builder().url(server.url("/v1/ping")).build()
                client.newCall(request).execute().use { resp ->
                    synchronized(responseCodesLock) { responseCodes.add(resp.code) }
                }
                doneLatch.countDown()
            }
        }
        startLatch.countDown()
        assertEquals(
            "All 10 calls must complete within 10s",
            true,
            doneLatch.await(10, TimeUnit.SECONDS),
        )
        executor.shutdown()
        releaser.shutdown()

        assertEquals(
            "Thundering herd of 10 simultaneous 401s must produce exactly 1 upstream refresh",
            1,
            authClient.refreshCount.get(),
        )
        assertEquals(10, responseCodes.size)
        responseCodes.forEach { assertEquals(200, it) }
    }

    @Test
    fun `refresh returning null propagates 401 to every waiter without silent retry`() {
        repeat(10) { server.enqueue(MockResponse().setResponseCode(401).setBody("expired")) }
        // NO 200 responses enqueued — if the interceptor silently retried, MockWebServer
        // would have no response to serve and the call would hang.

        val gate = Mutex(locked = true)
        val sessionStore = FakeSessionStore(sampleSession("stale-token"))
        val authClient = CountingAuthClient(
            result = BecalmResult.Failure(BecalmError.Unauthorized),
            entryGate = gate,
        )
        val provider = DefaultAuthTokenProvider(
            authClient = authClient,
            sessionStore = sessionStore,
            ioDispatcher = Dispatchers.IO,
            logger = logger,
        )

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(provider, server.hostName))
            .build()

        val releaser = Executors.newSingleThreadScheduledExecutor()
        releaser.schedule({ runBlocking { gate.unlock() } }, 300, TimeUnit.MILLISECONDS)

        val executor = Executors.newFixedThreadPool(10)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(10)
        val responseCodes = mutableListOf<Int>()
        val responseCodesLock = Any()

        repeat(10) {
            executor.submit {
                startLatch.await()
                val request = Request.Builder().url(server.url("/v1/ping")).build()
                client.newCall(request).execute().use { resp ->
                    synchronized(responseCodesLock) { responseCodes.add(resp.code) }
                }
                doneLatch.countDown()
            }
        }
        startLatch.countDown()
        assertEquals(true, doneLatch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        releaser.shutdown()

        assertEquals(
            "Failed refresh must still coalesce to exactly 1 upstream refresh",
            1,
            authClient.refreshCount.get(),
        )
        assertEquals(10, responseCodes.size)
        responseCodes.forEach {
            assertEquals("Every request must surface the original 401", 401, it)
        }
    }
}
