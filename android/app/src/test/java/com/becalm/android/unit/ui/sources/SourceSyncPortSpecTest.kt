package com.becalm.android.unit.ui.sources

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.CalendarSyncResponse
import com.becalm.android.data.remote.dto.MailSyncResponse
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourcePersonCandidateRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.ui.sources.DefaultSourceSyncPort
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class SourceSyncPortSpecTest {

    private val authRepository: AuthRepository = mockk()
    private val api: RailwayApi = mockk()
    private val calendarEventRepository: CalendarEventRepository = mockk(relaxed = true)
    private val commitmentRepository: CommitmentRepository = mockk()
    private val rawIngestionRepository: RawIngestionRepository = mockk()
    private val sourcePersonCandidateRepository: SourcePersonCandidateRepository = mockk()
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    private val subject = DefaultSourceSyncPort(
        authRepository = authRepository,
        apiProvider = Provider { api },
        calendarEventRepository = calendarEventRepository,
        commitmentRepository = commitmentRepository,
        rawIngestionRepository = rawIngestionRepository,
        sourcePersonCandidateRepository = sourcePersonCandidateRepository,
        sourceStatusRepository = sourceStatusRepository,
        workScheduler = workScheduler,
        logger = logger,
    )

    @Test
    fun `manual gmail sync refreshes commitments after backend sync succeeds`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        coEvery { api.syncMailSource(provider = SourceType.GMAIL) } returns Response.success(
            MailSyncResponse(synced = 1),
        )
        coEvery { rawIngestionRepository.refreshSince(userId = "user-1", sourceType = SourceType.GMAIL, since = null) } returns
            BecalmResult.Success(
                RawIngestionRepository.RefreshStats(
                    fetched = 1,
                    upserted = 1,
                    hasMore = false,
                    nextCursor = "raw-cursor-1",
                ),
            )
        coEvery { sourcePersonCandidateRepository.refreshSince(userId = "user-1", sourceType = SourceType.GMAIL, since = null) } returns
            BecalmResult.Success(
                SourcePersonCandidateRepository.RefreshStats(
                    fetched = 1,
                    upserted = 1,
                    hasMore = false,
                    nextCursor = "candidate-cursor-1",
                ),
            )
        coEvery { commitmentRepository.refreshSince(userId = "user-1", since = null) } returns
            BecalmResult.Success(
                CommitmentRepository.RefreshStats(
                    fetched = 1,
                    upserted = 1,
                    hasMore = false,
                    nextCursor = "cursor-1",
                ),
            )
        coEvery { sourceStatusRepository.refreshFromServer() } returns BecalmResult.Success(Unit)

        val result = subject.requestManualSync(SourceType.GMAIL)

        assertTrue(result is BecalmResult.Success)
        coVerify(exactly = 1) { api.syncMailSource(provider = SourceType.GMAIL) }
        coVerify(exactly = 1) { rawIngestionRepository.refreshSince(userId = "user-1", sourceType = SourceType.GMAIL, since = null) }
        coVerify(exactly = 1) { sourcePersonCandidateRepository.refreshSince(userId = "user-1", sourceType = SourceType.GMAIL, since = null) }
        coVerify(exactly = 1) { commitmentRepository.refreshSince(userId = "user-1", since = null) }
        coVerify(exactly = 1) { sourceStatusRepository.refreshFromServer() }
        coVerify(exactly = 1) { workScheduler.enqueuePersonInteractionIndex() }
    }

    @Test
    fun `manual calendar sync refreshes calendar events and generated schedule commitments`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        coEvery { calendarEventRepository.triggerServerSync() } returns BecalmResult.Success(
            CalendarSyncResponse(synced = 1),
        )
        coEvery { calendarEventRepository.refreshSince(userId = "user-1", since = null) } returns
            BecalmResult.Success(
                CalendarEventRepository.RefreshStats(
                    fetched = 1,
                    upserted = 1,
                    hasMore = false,
                    nextCursor = "calendar-cursor-1",
                ),
            )
        coEvery { commitmentRepository.refreshSince(userId = "user-1", since = null) } returns
            BecalmResult.Success(
                CommitmentRepository.RefreshStats(
                    fetched = 1,
                    upserted = 1,
                    hasMore = false,
                    nextCursor = "commitment-cursor-1",
                ),
            )
        coEvery { sourceStatusRepository.refreshFromServer() } returns BecalmResult.Success(Unit)

        val result = subject.requestManualSync(SourceType.GOOGLE_CALENDAR)

        assertTrue(result is BecalmResult.Success)
        coVerify(exactly = 1) { calendarEventRepository.triggerServerSync() }
        coVerify(exactly = 1) { calendarEventRepository.refreshSince(userId = "user-1", since = null) }
        coVerify(exactly = 1) { commitmentRepository.refreshSince(userId = "user-1", since = null) }
        coVerify(exactly = 1) { sourceStatusRepository.refreshFromServer() }
        coVerify(exactly = 1) { workScheduler.enqueuePersonInteractionIndex() }
    }

    private fun session(): SupabaseSession = SupabaseSession(
        accessToken = "access",
        refreshToken = "refresh",
        userId = "user-1",
        email = "user@example.com",
        expiresAt = Instant.parse("2026-04-28T12:00:00Z"),
    )
}
