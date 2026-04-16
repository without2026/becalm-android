package com.becalm.android.worker.ingestion

import android.content.Context
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.remote.msgraph.CalendarViewDeltaPage
import com.becalm.android.data.remote.msgraph.GraphCalendarEvent
import com.becalm.android.data.remote.msgraph.MsGraphClient
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.SourceStatusRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [OutlookCalendarWorker] (SP-27b).
 *
 * Verifies:
 * 1. userId fail-closed: [Result.failure] when no authenticated session.
 * 2. Cursor advance: worker reads initial null cursor, stores nextLink after first page,
 *    stores deltaLink after the final page.
 * 3. Unauthorized error from client → [Result.failure].
 * 4. RateLimited error from client → [Result.retry].
 * 5. NotFound (HTTP 410) clears the cursor and returns [Result.retry].
 */
@RunWith(RobolectricTestRunner::class)
class OutlookCalendarWorkerTest {

    private val context: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val msGraphClient: MsGraphClient = mockk()
    private val calendarEventRepository: CalendarEventRepository = mockk(relaxed = true)
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val syncCursorStore: SyncCursorStore = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk()
    private val logger: Logger = mockk(relaxed = true)

    private lateinit var worker: OutlookCalendarWorker

    private val fakeSession = SupabaseSession(
        userId = "user-123",
        accessToken = "access-token",
        refreshToken = "refresh-token",
        expiresAt = Instant.fromEpochMilliseconds(Long.MAX_VALUE),
    )

    private val fakeEvent = GraphCalendarEvent(
        id = "event-abc",
        subject = "Weekly sync",
        start = Instant.fromEpochMilliseconds(1_700_000_000_000),
        end = Instant.fromEpochMilliseconds(1_700_003_600_000),
        location = null,
        attendeesRaw = null,
    )

    @Before
    fun setUp() {
        worker = OutlookCalendarWorker(
            appContext = context,
            workerParams = workerParams,
            msGraphClient = msGraphClient,
            calendarEventRepository = calendarEventRepository,
            sourceStatusRepository = sourceStatusRepository,
            syncCursorStore = syncCursorStore,
            authRepository = authRepository,
            logger = logger,
        )
        every { workerParams.runAttemptCount } returns 0
    }

    // ── SP-27b-T1: userId fail-closed ─────────────────────────────────────────

    @Test
    fun `doWork returns failure when no authenticated session`() = runTest {
        coEvery { authRepository.currentSession() } returns null

        val result = worker.doWork()

        assertEquals(Result.failure(), result)
        coVerify(exactly = 0) { msGraphClient.calendarViewDelta(any()) }
    }

    @Test
    fun `doWork returns failure when userId is blank`() = runTest {
        coEvery { authRepository.currentSession() } returns fakeSession.copy(userId = "  ")

        val result = worker.doWork()

        assertEquals(Result.failure(), result)
        coVerify(exactly = 0) { msGraphClient.calendarViewDelta(any()) }
    }

    // ── SP-27b-T2: cursor advance — null → nextLink → deltaLink ──────────────

    @Test
    fun `doWork advances cursor from null through nextLink to deltaLink`() = runTest {
        coEvery { authRepository.currentSession() } returns fakeSession
        every { syncCursorStore.observeCursor(OutlookCalendarWorker.CURSOR_KEY) } returns flowOf(null)

        val page1 = CalendarViewDeltaPage(
            value = listOf(fakeEvent),
            nextLink = "https://graph.microsoft.com/v1.0/me/calendarView/delta?skiptoken=page2",
            deltaLink = null,
        )
        val page2 = CalendarViewDeltaPage(
            value = emptyList(),
            nextLink = null,
            deltaLink = "https://graph.microsoft.com/v1.0/me/calendarView/delta?deltatoken=final",
        )

        coEvery { msGraphClient.calendarViewDelta(null) } returns BecalmResult.Success(page1)
        coEvery { msGraphClient.calendarViewDelta(page1.nextLink) } returns BecalmResult.Success(page2)

        val result = worker.doWork()

        assertEquals(Result.success(), result)

        // nextLink must be stored after page 1 so a crash can resume from here
        coVerify { syncCursorStore.setCursor(OutlookCalendarWorker.CURSOR_KEY, page1.nextLink!!) }
        // deltaLink must be stored after the last page as the cursor for the next sync
        coVerify { syncCursorStore.setCursor(OutlookCalendarWorker.CURSOR_KEY, page2.deltaLink!!) }
    }

    @Test
    fun `doWork stores existing deltaLink as first cursor on subsequent sync`() = runTest {
        val storedDelta = "https://graph.microsoft.com/v1.0/me/calendarView/delta?deltatoken=stored"
        coEvery { authRepository.currentSession() } returns fakeSession
        every { syncCursorStore.observeCursor(OutlookCalendarWorker.CURSOR_KEY) } returns flowOf(storedDelta)

        val page = CalendarViewDeltaPage(
            value = emptyList(),
            nextLink = null,
            deltaLink = "https://graph.microsoft.com/v1.0/me/calendarView/delta?deltatoken=new",
        )
        coEvery { msGraphClient.calendarViewDelta(storedDelta) } returns BecalmResult.Success(page)

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify { msGraphClient.calendarViewDelta(storedDelta) }
    }

    // ── SP-27b-T3: Unauthorized → hard failure ────────────────────────────────

    @Test
    fun `doWork returns failure on Unauthorized error`() = runTest {
        coEvery { authRepository.currentSession() } returns fakeSession
        every { syncCursorStore.observeCursor(OutlookCalendarWorker.CURSOR_KEY) } returns flowOf(null)
        coEvery { msGraphClient.calendarViewDelta(null) } returns
            BecalmResult.Failure(BecalmError.Unauthorized)

        val result = worker.doWork()

        assertEquals(Result.failure(), result)
    }

    // ── SP-27b-T4: RateLimited → retry ───────────────────────────────────────

    @Test
    fun `doWork returns retry on RateLimited error`() = runTest {
        coEvery { authRepository.currentSession() } returns fakeSession
        every { syncCursorStore.observeCursor(OutlookCalendarWorker.CURSOR_KEY) } returns flowOf(null)
        coEvery { msGraphClient.calendarViewDelta(null) } returns
            BecalmResult.Failure(BecalmError.RateLimited(retryAfterSeconds = 30L))

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
    }

    // ── SP-27b-T5: NotFound (HTTP 410) clears cursor and returns retry ────────

    @Test
    fun `doWork clears cursor and returns retry on NotFound (HTTP 410)`() = runTest {
        val storedCursor = "https://graph.microsoft.com/v1.0/me/calendarView/delta?deltatoken=expired"
        coEvery { authRepository.currentSession() } returns fakeSession
        every { syncCursorStore.observeCursor(OutlookCalendarWorker.CURSOR_KEY) } returns flowOf(storedCursor)
        coEvery { msGraphClient.calendarViewDelta(storedCursor) } returns
            BecalmResult.Failure(BecalmError.NotFound("MS Graph delta token expired — full re-sync required"))

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
        coVerify { syncCursorStore.clearCursor(OutlookCalendarWorker.CURSOR_KEY) }
    }
}
