package com.becalm.android.worker.ingestion

import android.content.Context
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.FakeClock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.dto.CalendarSyncResponse
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.SourceStatusRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [GoogleCalendarWorker] (ING-007).
 *
 * The worker orchestrates a two-step flow:
 *   1. [CalendarEventRepository.triggerServerSync] — POST to Railway, which fetches via the
 *      user's server-side OAuth and upserts canonicalised rows.
 *   2. [CalendarEventRepository.refreshSince] — pull those rows into Room, resuming from the
 *      stored cursor (`since = null`).
 *
 * Because Android never calls Google Calendar directly, delta-token semantics live entirely
 * in the repository layer — the worker tests exercise how transport / auth errors at each
 * step map onto [Result].
 *
 * Test cases:
 * 1. Happy path — triggerServerSync + refreshSince both succeed → [Result.success] and
 *    [SourceStatusRepository.recordSyncSuccess] called.
 * 2. Empty delta — refresh returns zero rows; worker still advances state via recordSyncSuccess.
 * 3. refreshSince NotFound (HTTP 410) — bounded re-scan: worker still treats the attempt as
 *    recoverable and returns [Result.failure] (documents production behaviour; cursor reset
 *    happens inside [CalendarEventRepository.refreshSince], not here).
 * 4. No session — [Result.failure]; no network call.
 * 5. RateLimited at triggerServerSync → [Result.retry] with no recordSyncError.
 * 6. Network error at triggerServerSync → [Result.retry].
 *
 * Spec refs: ING-007, big-tech-rubric § I (errors), § J (tests).
 */
@RunWith(RobolectricTestRunner::class)
class GoogleCalendarWorkerTest {

    private val context: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk()
    private val calendarEventRepository: CalendarEventRepository = mockk()
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val clock = FakeClock(nowInstant = Instant.fromEpochMilliseconds(1_700_000_000_000))
    private val logger: Logger = mockk(relaxed = true)

    private lateinit var worker: GoogleCalendarWorker

    private val fakeUserId = "user-uuid-gcal-1"
    private val fakeSession = SupabaseSession(
        accessToken = "access",
        refreshToken = "refresh",
        userId = fakeUserId,
        email = "alice@example.com",
        expiresAt = Instant.fromEpochMilliseconds(Long.MAX_VALUE),
    )

    @Before
    fun setUp() {
        worker = GoogleCalendarWorker(
            appContext = context,
            workerParams = workerParams,
            authRepository = authRepository,
            calendarEventRepository = calendarEventRepository,
            sourceStatusRepository = sourceStatusRepository,
            clock = clock,
            logger = logger,
        )
        every { workerParams.runAttemptCount } returns 0
        coEvery { authRepository.currentSession() } returns fakeSession
    }

    // ── T1: Happy path — both phases succeed ─────────────────────────────────

    @Test
    fun `doWork returns success when server sync and refresh both succeed`() = runTest {
        coEvery { calendarEventRepository.triggerServerSync() } returns
            BecalmResult.Success(CalendarSyncResponse(synced = 3))
        coEvery {
            calendarEventRepository.refreshSince(userId = fakeUserId, since = null)
        } returns BecalmResult.Success(
            CalendarEventRepository.RefreshStats(
                fetched = 3,
                upserted = 3,
                hasMore = false,
                nextCursor = null,
            ),
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify { calendarEventRepository.triggerServerSync() }
        coVerify { calendarEventRepository.refreshSince(fakeUserId, null) }
        coVerify { sourceStatusRepository.recordSyncSuccess(SourceType.GOOGLE_CALENDAR, any()) }
    }

    // ── T2: Empty delta — zero rows, cursor still advances via success ───────

    @Test
    fun `doWork returns success and records sync success on empty delta`() = runTest {
        coEvery { calendarEventRepository.triggerServerSync() } returns
            BecalmResult.Success(CalendarSyncResponse(synced = 0))
        coEvery {
            calendarEventRepository.refreshSince(userId = fakeUserId, since = null)
        } returns BecalmResult.Success(
            CalendarEventRepository.RefreshStats(
                fetched = 0,
                upserted = 0,
                hasMore = false,
                nextCursor = null,
            ),
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) {
            sourceStatusRepository.recordSyncSuccess(SourceType.GOOGLE_CALENDAR, any())
        }
    }

    // ── T3: refreshSince NotFound → failure + recordSyncError ────────────────

    @Test
    fun `doWork returns failure when refreshSince returns NotFound`() = runTest {
        // The worker's NotFound branch maps to Result.failure() because the stored cursor is
        // invalid and retrying with the same cursor cannot recover — refreshSince itself
        // resets the cursor before returning, so the next scheduled run will do a full scan.
        coEvery { calendarEventRepository.triggerServerSync() } returns
            BecalmResult.Success(CalendarSyncResponse(synced = 0))
        coEvery {
            calendarEventRepository.refreshSince(userId = fakeUserId, since = null)
        } returns BecalmResult.Failure(BecalmError.NotFound("calendar_events cursor"))

        val result = worker.doWork()

        assertEquals(Result.failure(), result)
        coVerify {
            sourceStatusRepository.recordSyncError(
                SourceType.GOOGLE_CALENDAR,
                any(),
                any(),
            )
        }
    }

    // ── T4: No session — fail-closed, no network call ────────────────────────

    @Test
    fun `doWork returns failure when no authenticated session`() = runTest {
        coEvery { authRepository.currentSession() } returns null

        val result = worker.doWork()

        assertEquals(Result.failure(), result)
        coVerify(exactly = 0) { calendarEventRepository.triggerServerSync() }
        coVerify(exactly = 0) {
            calendarEventRepository.refreshSince(any(), any())
        }
    }

    // ── T5: RateLimited at triggerServerSync → retry, no recordSyncError ─────

    @Test
    fun `doWork returns retry on RateLimited from triggerServerSync`() = runTest {
        coEvery { calendarEventRepository.triggerServerSync() } returns
            BecalmResult.Failure(BecalmError.RateLimited(retryAfterSeconds = 30L))

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
        // Rate-limited is not an error — the source wasn't broken, just throttled. Do NOT
        // record recordSyncError: a throttled dashboard chip would mis-represent health.
        coVerify(exactly = 0) {
            sourceStatusRepository.recordSyncError(SourceType.GOOGLE_CALENDAR, any(), any())
        }
        coVerify(exactly = 0) {
            calendarEventRepository.refreshSince(any(), any())
        }
    }

    // ── T6: Network error at triggerServerSync → retry ───────────────────────

    @Test
    fun `doWork returns retry on Network error from triggerServerSync`() = runTest {
        coEvery { calendarEventRepository.triggerServerSync() } returns
            BecalmResult.Failure(BecalmError.Network(0, "offline"))

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
        // Transient network failures are recoverable via exponential back-off.
        coVerify(exactly = 0) {
            calendarEventRepository.refreshSince(any(), any())
        }
    }
}
