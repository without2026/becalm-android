package com.becalm.android.unit.ui.sources

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.becalm.android.R
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.MeetingImportRepository
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.domain.meeting.MeetingImportFolderKind
import com.becalm.android.ui.sources.ARG_SOURCE_TYPE
import com.becalm.android.ui.sources.SourceAdministrationPort
import com.becalm.android.ui.sources.SourceDetailEffect
import com.becalm.android.ui.sources.SourceSyncPort
import com.becalm.android.ui.sources.SourceDetailViewModel
import com.becalm.android.ui.sources.SourceDisconnectOutcome
import com.becalm.android.ui.sources.SourceReconnectDestination
import com.becalm.android.ui.components.SourceSyncStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceDetailViewModelSpecTest {

    private val testDispatcher = StandardTestDispatcher()
    private val sourceStatusRepository: SourceStatusRepository = mockk()
    private val rawIngestionRepository: RawIngestionRepository = mockk(relaxed = true)
    private val sourceSyncPort: SourceSyncPort = mockk(relaxed = true)
    private val meetingImportRepository: MeetingImportRepository = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { meetingImportRepository.ensureTargetFolder(MeetingImportFolderKind.Audio) } returns
            BecalmResult.Success("content://tree/recordings/document/Recordings%2FBeCalm%20Meetings%2FAudio")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `blank source arg yields explicit error state`() = runTest {
        val viewModel = buildViewModel("")

        assertEquals(R.string.source_detail_error_missing_source_message, viewModel.state.value.error?.resId)
    }

    @Test
    fun `unknown source arg is rejected before any repository subscription`() = runTest {
        val viewModel = buildViewModel("unsupported_source")

        assertEquals(R.string.source_detail_error_invalid_source, viewModel.state.value.error?.resId)
        verify(exactly = 0) { sourceStatusRepository.observeFor(any()) }
        verify(exactly = 0) { rawIngestionRepository.observeForSourceType(any(), any(), any()) }
    }

    @Test
    fun `contacts pseudo source has no detail route and is rejected before subscription`() = runTest {
        val viewModel = buildViewModel("contacts")

        assertEquals(R.string.source_detail_error_invalid_source, viewModel.state.value.error?.resId)
        verify(exactly = 0) { sourceStatusRepository.observeFor(any()) }
        verify(exactly = 0) { rawIngestionRepository.observeForSourceType(any(), any(), any()) }
    }

    @Test
    fun `SMG-002 connected source detail exposes sync metadata and connected actions`() = runTest {
        val lastSync = Instant.parse("2026-04-20T09:00:00Z")
        every { sourceStatusRepository.observeFor(SourceType.GMAIL) } returns
            flowOf(
                SourceStatus(
                    sourceType = SourceType.GMAIL,
                    status = SourceConnectionStatus.CONNECTED,
                    lastSyncedAt = lastSync,
                    errorMessage = null,
                ),
            )
        every { rawIngestionRepository.observeForSourceType("user-1", SourceType.GMAIL, 50) } returns
            flowOf(
                listOf(
                    rawEvent(id = "gmail-1", sourceType = SourceType.GMAIL),
                    rawEvent(id = "gmail-2", sourceType = SourceType.GMAIL),
                ),
            )

        val viewModel = buildViewModel(SourceType.GMAIL)
        viewModel.setUserId("user-1")

        viewModel.state.test {
            var state = awaitItem()
            while (state.status == SourceSyncStatus.Unknown || state.eventsSyncedCount == null || state.recentEvents.isEmpty()) {
                state = awaitItem()
            }

            assertEquals(SourceType.GMAIL, state.sourceType)
            assertEquals(SourceSyncStatus.Connected, state.status)
            assertEquals(lastSync, state.lastSyncAt)
            assertEquals(2, state.eventsSyncedCount)
            assertFalse(state.hasError)
            assertFalse(state.showReconnectButton)
            assertTrue(state.showDisconnectButton)
            assertTrue(state.showManualSyncButton)
            assertFalse(state.showDisconnectConfirmDialog)
            assertNull(state.disconnectOutcome)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SMG-003 error source detail exposes reconnect action and routes outlook mail to oauth reconnect`() = runTest {
        every { sourceStatusRepository.observeFor(SourceType.OUTLOOK_MAIL) } returns
            flowOf(
                SourceStatus(
                    sourceType = SourceType.OUTLOOK_MAIL,
                    status = SourceConnectionStatus.ERROR,
                    lastSyncedAt = Instant.parse("2026-04-20T09:00:00Z"),
                    errorMessage = "token expired",
                ),
            )

        val viewModel = buildViewModel(SourceType.OUTLOOK_MAIL)

        viewModel.state.test {
            var state = awaitItem()
            while (state.status == SourceSyncStatus.Unknown) {
                state = awaitItem()
            }

            assertEquals(SourceSyncStatus.Error, state.status)
            assertTrue(state.hasError)
            assertTrue(state.showReconnectButton)
            assertFalse(state.showDisconnectButton)
            assertFalse(state.showManualSyncButton)
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.effects.test {
            viewModel.onReconnect()

            assertEquals(
                SourceDetailEffect.OpenReconnect(SourceReconnectDestination.OUTLOOK_MAIL),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SMG-003 disconnected IMAP source reconnect routes to IMAP destination`() = runTest {
        every { sourceStatusRepository.observeFor(SourceType.NAVER_IMAP) } returns
            flowOf(status(sourceType = SourceType.NAVER_IMAP, status = SourceConnectionStatus.NEVER_CONNECTED))

        val viewModel = buildViewModel(SourceType.NAVER_IMAP)

        viewModel.effects.test {
            viewModel.onReconnect()

            assertEquals(
                SourceDetailEffect.OpenReconnect(SourceReconnectDestination.IMAP),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SMG-003 disconnected voice source reconnect routes to recording folder`() = runTest {
        every { sourceStatusRepository.observeFor(SourceType.VOICE) } returns
            flowOf(status(sourceType = SourceType.VOICE, status = SourceConnectionStatus.NEVER_CONNECTED))

        val viewModel = buildViewModel(SourceType.VOICE)

        viewModel.effects.test {
            viewModel.onReconnect()

            assertEquals(
                SourceDetailEffect.OpenReconnect(SourceReconnectDestination.RECORDING_FOLDER),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SMG-003 disconnected meeting source reconnect routes to recording folder`() = runTest {
        every { sourceStatusRepository.observeFor(SourceType.MEETING) } returns
            flowOf(status(sourceType = SourceType.MEETING, status = SourceConnectionStatus.NEVER_CONNECTED))

        val viewModel = buildViewModel(SourceType.MEETING)

        viewModel.effects.test {
            viewModel.onReconnect()

            assertEquals(
                SourceDetailEffect.OpenReconnect(SourceReconnectDestination.RECORDING_FOLDER),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SMG-004 disconnect click and dismiss toggle confirm dialog`() = runTest {
        every { sourceStatusRepository.observeFor(SourceType.GMAIL) } returns
            flowOf(status(sourceType = SourceType.GMAIL, status = SourceConnectionStatus.CONNECTED))

        val viewModel = buildViewModel(SourceType.GMAIL)

        viewModel.state.test {
            var state = awaitItem()
            while (state.status == SourceSyncStatus.Unknown) {
                state = awaitItem()
            }

            assertFalse(state.showDisconnectConfirmDialog)
            viewModel.onDisconnectClick()
            assertTrue(awaitItem().showDisconnectConfirmDialog)
            viewModel.onDisconnectDismiss()
            assertFalse(awaitItem().showDisconnectConfirmDialog)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SMG-004 disconnect confirm publishes outcome contract from source administration port`() = runTest {
        every { sourceStatusRepository.observeFor(SourceType.GMAIL) } returns
            flowOf(status(sourceType = SourceType.GMAIL, status = SourceConnectionStatus.CONNECTED))
        val port = FakeSourceAdministrationPort(
            result = BecalmResult.Success(
                SourceDisconnectOutcome(
                    sourceType = SourceType.GMAIL,
                    cursorCleared = true,
                    credentialsDeleted = true,
                    roomDataRetained = true,
                ),
            ),
        )

        val viewModel = buildViewModel(
            sourceType = SourceType.GMAIL,
            sourceAdministrationPort = port,
        )

        viewModel.state.test {
            var state = awaitItem()
            while (state.status == SourceSyncStatus.Unknown) {
                state = awaitItem()
            }

            viewModel.onDisconnectClick()
            viewModel.onDisconnectConfirm()
            advanceUntilIdle()

            while (state.disconnectOutcome == null) {
                state = awaitItem()
            }

            assertEquals(SourceType.GMAIL, port.disconnectCalls.single())
            assertFalse(state.showDisconnectConfirmDialog)
            assertNull(state.actionError)
            assertEquals(
                SourceDisconnectOutcome(
                    sourceType = SourceType.GMAIL,
                    cursorCleared = true,
                    credentialsDeleted = true,
                    roomDataRetained = true,
                ),
                state.disconnectOutcome,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SMG-004 disconnect failure keeps outcome empty and exposes action error`() = runTest {
        every { sourceStatusRepository.observeFor(SourceType.GMAIL) } returns
            flowOf(status(sourceType = SourceType.GMAIL, status = SourceConnectionStatus.CONNECTED))
        val port = FakeSourceAdministrationPort(
            result = BecalmResult.Failure(BecalmError.Validation("source", "disconnect failed")),
        )

        val viewModel = buildViewModel(
            sourceType = SourceType.GMAIL,
            sourceAdministrationPort = port,
        )

        viewModel.state.test {
            var state = awaitItem()
            while (state.status == SourceSyncStatus.Unknown) {
                state = awaitItem()
            }

            viewModel.onDisconnectClick()
            viewModel.onDisconnectConfirm()
            advanceUntilIdle()

            while (state.actionError == null) {
                state = awaitItem()
            }

            assertFalse(state.showDisconnectConfirmDialog)
            assertNull(state.disconnectOutcome)
            assertEquals(R.string.source_detail_error_disconnect_failed, state.actionError?.resId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SMG-005 manual sync delegates to the active source sync owner only`() = runTest {
        every { sourceStatusRepository.observeFor(SourceType.GMAIL) } returns
            flowOf(status(sourceType = SourceType.GMAIL, status = SourceConnectionStatus.CONNECTED))
        coEvery { sourceSyncPort.requestManualSync(SourceType.GMAIL) } returns BecalmResult.Success(Unit)

        val viewModel = buildViewModel(SourceType.GMAIL)
        viewModel.onManualSync()
        advanceUntilIdle()

        coVerify(exactly = 1) { sourceSyncPort.requestManualSync(SourceType.GMAIL) }
        coVerify(exactly = 0) { sourceSyncPort.requestManualSync(SourceType.OUTLOOK_MAIL) }
        coVerify(exactly = 0) { sourceSyncPort.requestManualSync(SourceType.GOOGLE_CALENDAR) }
    }

    @Test
    fun `SMG-006 meeting source exposes audio import only`() = runTest {
        every { sourceStatusRepository.observeFor(SourceType.MEETING) } returns
            flowOf(status(sourceType = SourceType.MEETING, status = SourceConnectionStatus.CONNECTED))
        every { rawIngestionRepository.observeForSourceType("user-1", SourceType.MEETING, 50) } returns
            flowOf(emptyList())
        val viewModel = buildViewModel(SourceType.MEETING)
        viewModel.setUserId("user-1")

        viewModel.state.test {
            var state = awaitItem()
            while (state.status == SourceSyncStatus.Unknown) {
                state = awaitItem()
            }

            assertEquals(SourceType.MEETING, state.sourceType)
            assertTrue(state.showMeetingAudioAddButton)
            assertEquals(
                "content://tree/recordings/document/Recordings%2FBeCalm%20Meetings%2FAudio",
                state.meetingAudioPickerInitialUri,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SMG-005 manual sync state follows source status repository from connected to syncing to refreshed connected`() = runTest {
        val initialSyncAt = Instant.parse("2026-04-20T09:00:00Z")
        val refreshedSyncAt = Instant.parse("2026-04-20T09:05:00Z")
        val statuses = MutableStateFlow(
            SourceStatus(
                sourceType = SourceType.GMAIL,
                status = SourceConnectionStatus.CONNECTED,
                lastSyncedAt = initialSyncAt,
                errorMessage = null,
            ),
        )
        every { sourceStatusRepository.observeFor(SourceType.GMAIL) } returns statuses
        coEvery { sourceSyncPort.requestManualSync(SourceType.GMAIL) } returns BecalmResult.Success(Unit)

        val viewModel = buildViewModel(SourceType.GMAIL)

        viewModel.state.test {
            var state = awaitItem()
            while (state.status == SourceSyncStatus.Unknown) {
                state = awaitItem()
            }

            assertEquals(SourceSyncStatus.Connected, state.status)
            assertEquals(initialSyncAt, state.lastSyncAt)

            viewModel.onManualSync()
            advanceUntilIdle()
            coVerify(exactly = 1) { sourceSyncPort.requestManualSync(SourceType.GMAIL) }

            statuses.value = SourceStatus(
                sourceType = SourceType.GMAIL,
                status = SourceConnectionStatus.SYNCING,
                lastSyncedAt = initialSyncAt,
                errorMessage = null,
            )
            advanceUntilIdle()
            state = awaitItem()
            assertEquals(SourceSyncStatus.Syncing, state.status)
            assertEquals(initialSyncAt, state.lastSyncAt)

            statuses.value = SourceStatus(
                sourceType = SourceType.GMAIL,
                status = SourceConnectionStatus.CONNECTED,
                lastSyncedAt = refreshedSyncAt,
                errorMessage = null,
            )
            advanceUntilIdle()
            state = awaitItem()
            assertEquals(SourceSyncStatus.Connected, state.status)
            assertEquals(refreshedSyncAt, state.lastSyncAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `valid source uses source-scoped timeline and caps recent events at fifty`() = runTest {
        every { sourceStatusRepository.observeFor(SourceType.GMAIL) } returns
            flowOf(status(sourceType = SourceType.GMAIL, status = SourceConnectionStatus.ERROR))
        every { rawIngestionRepository.observeForSourceType("user-1", SourceType.GMAIL, 50) } returns
            flowOf(
                (1..50).map { index ->
                    rawEvent(id = "gmail-$index", sourceType = SourceType.GMAIL)
                },
            )

        val viewModel = buildViewModel(SourceType.GMAIL)
        viewModel.setUserId("user-1")
        viewModel.state.test {
            var state = awaitItem()
            while (state.status == SourceSyncStatus.Unknown || state.recentEvents.isEmpty()) {
                state = awaitItem()
            }

            assertEquals(SourceSyncStatus.Error, state.status)
            assertEquals(50, state.recentEvents.size)
            assertEquals(50, state.eventsSyncedCount)
            assertTrue(state.recentEvents.all { it.id.startsWith("gmail-") })
            assertEquals("gmail-1", state.recentEvents.first().id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `valid naver email source remains distinct from daum email in source-scoped recent events`() = runTest {
        every { sourceStatusRepository.observeFor(SourceType.NAVER_IMAP) } returns
            flowOf(status(sourceType = SourceType.NAVER_IMAP, status = SourceConnectionStatus.CONNECTED))
        every { rawIngestionRepository.observeForSourceType("user-1", SourceType.NAVER_IMAP, 50) } returns
            flowOf(
                listOf(
                    rawEvent(id = "naver-1", sourceType = SourceType.NAVER_IMAP),
                ),
            )

        val viewModel = buildViewModel(SourceType.NAVER_IMAP)
        viewModel.setUserId("user-1")

        viewModel.state.test {
            var state = awaitItem()
            while (state.status == SourceSyncStatus.Unknown || state.recentEvents.isEmpty()) {
                state = awaitItem()
            }

            assertEquals(SourceType.NAVER_IMAP, state.sourceType)
            assertEquals(1, state.recentEvents.size)
            assertEquals(1, state.eventsSyncedCount)
            assertEquals("naver-1", state.recentEvents.single().id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun buildViewModel(
        sourceType: String,
        sourceAdministrationPort: SourceAdministrationPort = FakeSourceAdministrationPort(),
        sourceSyncPort: SourceSyncPort = this.sourceSyncPort,
    ): SourceDetailViewModel = SourceDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf(ARG_SOURCE_TYPE to sourceType)),
        sourceStatusRepository = sourceStatusRepository,
        rawIngestionRepository = rawIngestionRepository,
        sourceAdministrationPort = sourceAdministrationPort,
        sourceSyncPort = sourceSyncPort,
        meetingImportRepository = meetingImportRepository,
        logger = logger,
    )

    private fun status(
        sourceType: String,
        status: SourceConnectionStatus,
    ): SourceStatus = SourceStatus(
        sourceType = sourceType,
        status = status,
        lastSyncedAt = Instant.fromEpochMilliseconds(1_000),
        errorMessage = null,
    )

    private fun rawEvent(
        id: String,
        sourceType: String,
    ): RawIngestionEventEntity = RawIngestionEventEntity(
        id = id,
        userId = "user-1",
        clientEventId = "client-$id",
        sourceType = sourceType,
        eventTitle = id,
        timestamp = Instant.fromEpochMilliseconds(1_000),
    )
}

private class FakeSourceAdministrationPort(
    var result: BecalmResult<SourceDisconnectOutcome> = BecalmResult.Success(
        SourceDisconnectOutcome(
            sourceType = SourceType.GMAIL,
            cursorCleared = true,
            credentialsDeleted = true,
            roomDataRetained = true,
        ),
    ),
) : SourceAdministrationPort {
    val disconnectCalls = mutableListOf<String>()

    override suspend fun disconnect(sourceType: String): BecalmResult<SourceDisconnectOutcome> {
        disconnectCalls += sourceType
        return result
    }
}
