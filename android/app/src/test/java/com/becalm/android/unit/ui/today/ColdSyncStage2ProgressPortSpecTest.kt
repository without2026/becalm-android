package com.becalm.android.unit.ui.today

import app.cash.turbine.test
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.ui.today.ColdSyncStage2ProgressPort
import com.becalm.android.ui.today.ColdSyncStage2ProgressState
import com.becalm.android.ui.today.DefaultColdSyncStage2ProgressPort
import com.becalm.android.worker.WorkScheduler
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ColdSyncStage2ProgressPortSpecTest {

    private val stage1CompletedAt = MutableStateFlow<Long?>(null)
    private val stage2CompletedAt = MutableStateFlow<Long?>(null)
    private val stage2Deferred = MutableStateFlow(false)
    private val currentUserId = MutableStateFlow<String?>(null)
    private val sourceStatuses = MutableStateFlow<Map<String, SourceStatus>>(emptyMap())
    private val emailCount = MutableStateFlow(0)
    private val voiceCount = MutableStateFlow(0)
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val rawIngestionRepository: RawIngestionRepository = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)

    init {
        every { userPrefsStore.observeColdSyncStage1CompletedAt() } returns stage1CompletedAt
        every { userPrefsStore.observeColdSyncStage2CompletedAt() } returns stage2CompletedAt
        every { userPrefsStore.observeColdSyncStage2Deferred() } returns stage2Deferred
        every { userPrefsStore.observeCurrentUserId() } returns currentUserId
        every { sourceStatusRepository.observeSources() } returns sourceStatuses
        every {
            rawIngestionRepository.observeCountForSourceTypesSince(
                any(),
                match { it.contains(SourceType.GMAIL) },
                any(),
            )
        } returns emailCount
        every {
            rawIngestionRepository.observeCountForSourceTypesSince(
                any(),
                match { it.contains(SourceType.VOICE) },
                any(),
            )
        } returns voiceCount
    }

    @Test
    fun `COLD-005 banner stays hidden until stage1 completes`() = runTest {
        val port = buildPort()

        port.observeState().test {
            val state = awaitItem()

            assertFalse(state.bannerVisible)
            assertFalse(state.canDefer)
            assertFalse(state.deferred)
            assertEquals(0, state.progressPercent)
            assertEquals(0, state.emailBackfillProcessed)
            assertEquals(0, state.emailBackfillTotal)
            assertEquals(0, state.voiceScanProcessed)
            assertEquals(0, state.voiceScanTotal)
            assertNull(state.completedAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `COLD-005 banner becomes visible and deferrable after stage1 until stage2 completes`() = runTest {
        stage1CompletedAt.value = Instant.parse("2026-04-23T01:00:00Z").toEpochMilliseconds()
        currentUserId.value = "user-1"
        emailCount.value = 1102
        voiceCount.value = 188
        sourceStatuses.value = mapOf(
            SourceType.GMAIL to status(SourceType.GMAIL, SourceConnectionStatus.CONNECTED),
            SourceType.OUTLOOK_MAIL to status(SourceType.OUTLOOK_MAIL, SourceConnectionStatus.ERROR),
            SourceType.VOICE to status(SourceType.VOICE, SourceConnectionStatus.CONNECTED),
        )
        val port = buildPort()

        port.observeState().test {
            val state = awaitItem()

            assertTrue(state.bannerVisible)
            assertTrue(state.canDefer)
            assertFalse(state.deferred)
            assertEquals(60, state.progressPercent)
            assertEquals(1102, state.emailBackfillProcessed)
            assertEquals(1102, state.emailBackfillTotal)
            assertEquals(188, state.voiceScanProcessed)
            assertEquals(188, state.voiceScanTotal)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `COLD-005 completed stage2 hides banner and projects completed percent`() = runTest {
        stage1CompletedAt.value = Instant.parse("2026-04-23T01:00:00Z").toEpochMilliseconds()
        stage2CompletedAt.value = Instant.parse("2026-04-23T01:30:00Z").toEpochMilliseconds()
        val port = buildPort()

        port.observeState().test {
            val state = awaitItem()

            assertFalse(state.bannerVisible)
            assertFalse(state.canDefer)
            assertFalse(state.deferred)
            assertEquals(100, state.progressPercent)
            assertEquals(Instant.parse("2026-04-23T01:30:00Z"), state.completedAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `COLD-005 deferred stage2 keeps banner visible but disables further deferral`() = runTest {
        stage1CompletedAt.value = Instant.parse("2026-04-23T01:00:00Z").toEpochMilliseconds()
        stage2Deferred.value = true
        val port = buildPort()

        port.observeState().test {
            val state = awaitItem()

            assertTrue(state.bannerVisible)
            assertFalse(state.canDefer)
            assertTrue(state.deferred)
            assertEquals(0, state.progressPercent)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `COLD-005 deferStage2 cancels stage2 work persists deferred flag and returns deferred state`() = runTest {
        val now = Instant.parse("2026-04-23T01:15:00Z")
        val port = buildPort()

        val result = port.deferStage2(now)

        val state = result.expectSuccess()
        verify(exactly = 1) { workScheduler.cancelColdSyncStage2() }
        coVerify(exactly = 1) { userPrefsStore.setColdSyncStage2Deferred(true) }
        assertEquals(
            ColdSyncStage2ProgressState(
                bannerVisible = true,
                progressPercent = 0,
                emailBackfillProcessed = 0,
                emailBackfillTotal = 0,
                voiceScanProcessed = 0,
                voiceScanTotal = 0,
                canDefer = false,
                deferred = true,
                completedAt = null,
            ),
            state,
        )
    }

    private fun buildPort(): ColdSyncStage2ProgressPort = DefaultColdSyncStage2ProgressPort(
        userPrefsStore = userPrefsStore,
        sourceStatusRepository = sourceStatusRepository,
        rawIngestionRepository = rawIngestionRepository,
        workScheduler = workScheduler,
    )

    private fun status(
        sourceType: String,
        state: SourceConnectionStatus,
    ): SourceStatus = SourceStatus(
        sourceType = sourceType,
        status = state,
        lastSyncedAt = null,
        errorMessage = null,
    )

    private fun BecalmResult<ColdSyncStage2ProgressState>.expectSuccess(): ColdSyncStage2ProgressState = when (this) {
        is BecalmResult.Success -> value
        is BecalmResult.Failure -> throw AssertionError("Expected success, got $error")
    }
}
