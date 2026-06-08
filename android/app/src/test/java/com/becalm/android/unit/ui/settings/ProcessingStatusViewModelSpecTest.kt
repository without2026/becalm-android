package com.becalm.android.unit.ui.settings

import app.cash.turbine.test
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.db.entity.SourceConnectionEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.AudioProcessingConfirmationRepository
import com.becalm.android.data.repository.ProcessingPhase
import com.becalm.android.data.repository.ProcessingSourceState
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceConnectionRepository
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.ui.settings.ProcessingStatusEffect
import com.becalm.android.ui.settings.ProcessingStatusRecoveryActionType
import com.becalm.android.ui.settings.ProcessingStatusViewModel
import com.becalm.android.ui.sources.SourceReconnectDestination
import com.becalm.android.ui.sources.SourceSyncPort
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessingStatusViewModelSpecTest {

    private val processingStatusRepository: ProcessingStatusRepository = mockk()
    private val sourceStatusRepository: SourceStatusRepository = mockk()
    private val rawIngestionRepository: RawIngestionRepository = mockk(relaxed = true)
    private val audioProcessingConfirmationRepository: AudioProcessingConfirmationRepository = mockk(relaxed = true)
    private val sourceSyncPort: SourceSyncPort = mockk(relaxed = true)
    private val sourceConnectionRepository: SourceConnectionRepository = mockk()
    private val authRepository: AuthRepository = mockk()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { authRepository.currentSession() } returns session()
        coEvery { sourceStatusRepository.refreshFromServer() } returns BecalmResult.Success(Unit)
        every { rawIngestionRepository.observeActiveProcessingItems("user-1", any()) } returns MutableStateFlow(emptyList())
        every { sourceConnectionRepository.observeAll("user-1") } returns MutableStateFlow(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `processing rows for disconnected connection-backed sources are suppressed`() = runTest {
        val processing = MutableStateFlow(
            listOf(
                ProcessingSourceState(
                    sourceType = SourceType.GMAIL,
                    phase = ProcessingPhase.SCANNING,
                    updatedAt = Instant.fromEpochMilliseconds(2_000),
                ),
                ProcessingSourceState(
                    sourceType = SourceType.NAVER_IMAP,
                    phase = ProcessingPhase.SYNCED,
                    updatedAt = Instant.fromEpochMilliseconds(3_000),
                ),
            ),
        )
        val sourceStatuses = MutableStateFlow(
            mapOf(
                SourceType.GMAIL to status(SourceType.GMAIL, SourceConnectionStatus.NEVER_CONNECTED),
                SourceType.NAVER_IMAP to status(SourceType.NAVER_IMAP, SourceConnectionStatus.CONNECTED),
            ),
        )
        every { processingStatusRepository.observeAll() } returns processing
        every { sourceStatusRepository.observeSources() } returns sourceStatuses

        val viewModel = ProcessingStatusViewModel(
            processingStatusRepository = processingStatusRepository,
            sourceStatusRepository = sourceStatusRepository,
            rawIngestionRepository = rawIngestionRepository,
            audioProcessingConfirmationRepository = audioProcessingConfirmationRepository,
            sourceSyncPort = sourceSyncPort,
            sourceConnectionRepository = sourceConnectionRepository,
            authRepository = authRepository,
        )

        viewModel.state.test {
            val state = awaitItem()

            assertEquals(listOf(SourceType.NAVER_IMAP), state.rows.map { it.sourceType })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `message screenshot processing row does not navigate to source detail`() = runTest {
        val processing = MutableStateFlow(
            listOf(
                ProcessingSourceState(
                    sourceType = SourceType.MESSAGE_SCREENSHOT,
                    phase = ProcessingPhase.SYNCED,
                    itemCount = 1,
                    updatedAt = Instant.fromEpochMilliseconds(4_000),
                ),
            ),
        )
        every { processingStatusRepository.observeAll() } returns processing
        every { sourceStatusRepository.observeSources() } returns MutableStateFlow(emptyMap())

        val viewModel = ProcessingStatusViewModel(
            processingStatusRepository = processingStatusRepository,
            sourceStatusRepository = sourceStatusRepository,
            rawIngestionRepository = rawIngestionRepository,
            audioProcessingConfirmationRepository = audioProcessingConfirmationRepository,
            sourceSyncPort = sourceSyncPort,
            sourceConnectionRepository = sourceConnectionRepository,
            authRepository = authRepository,
        )

        viewModel.state.test {
            val row = awaitItem().rows.single()

            assertEquals(SourceType.MESSAGE_SCREENSHOT, row.sourceType)
            assertFalse(row.opensSourceDetail)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `retryable source error row exposes direct retry action`() = runTest {
        every { processingStatusRepository.observeAll() } returns MutableStateFlow(
            listOf(
                ProcessingSourceState(
                    sourceType = SourceType.GMAIL,
                    phase = ProcessingPhase.ERROR,
                    message = "Network error",
                    updatedAt = Instant.fromEpochMilliseconds(5_000),
                ),
            ),
        )
        every { sourceStatusRepository.observeSources() } returns MutableStateFlow(
            mapOf(SourceType.GMAIL to status(SourceType.GMAIL, SourceConnectionStatus.CONNECTED)),
        )

        val viewModel = ProcessingStatusViewModel(
            processingStatusRepository = processingStatusRepository,
            sourceStatusRepository = sourceStatusRepository,
            rawIngestionRepository = rawIngestionRepository,
            audioProcessingConfirmationRepository = audioProcessingConfirmationRepository,
            sourceSyncPort = sourceSyncPort,
            sourceConnectionRepository = sourceConnectionRepository,
            authRepository = authRepository,
        )

        viewModel.state.test {
            val row = awaitItem().rows.single()

            assertEquals(ProcessingStatusRecoveryActionType.RETRY_SOURCE_SYNC, row.recoveryAction?.type)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `server source error is promoted to action-needed row when local processing is idle`() = runTest {
        every { processingStatusRepository.observeAll() } returns MutableStateFlow(
            listOf(
                ProcessingSourceState(
                    sourceType = SourceType.GMAIL,
                    phase = ProcessingPhase.IDLE,
                    updatedAt = Instant.fromEpochMilliseconds(5_000),
                ),
            ),
        )
        every { sourceStatusRepository.observeSources() } returns MutableStateFlow(
            mapOf(
                SourceType.GMAIL to SourceStatus(
                    sourceType = SourceType.GMAIL,
                    status = SourceConnectionStatus.ERROR,
                    lastSyncedAt = null,
                    errorMessage = "provider_sync_failed",
                ),
            ),
        )

        val viewModel = ProcessingStatusViewModel(
            processingStatusRepository = processingStatusRepository,
            sourceStatusRepository = sourceStatusRepository,
            rawIngestionRepository = rawIngestionRepository,
            audioProcessingConfirmationRepository = audioProcessingConfirmationRepository,
            sourceSyncPort = sourceSyncPort,
            sourceConnectionRepository = sourceConnectionRepository,
            authRepository = authRepository,
        )

        viewModel.state.test {
            val row = awaitItem().rows.single()

            assertEquals(SourceType.GMAIL, row.sourceType)
            assertEquals(ProcessingPhase.ERROR, row.phase)
            assertEquals("provider_sync_failed", row.message)
            assertEquals(ProcessingStatusRecoveryActionType.RETRY_SOURCE_SYNC, row.recoveryAction?.type)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reconnect recovery effect carries failed provider connection id`() = runTest {
        every { processingStatusRepository.observeAll() } returns MutableStateFlow(
            listOf(
                ProcessingSourceState(
                    sourceType = SourceType.GMAIL,
                    phase = ProcessingPhase.ERROR,
                    message = "reauth required",
                    updatedAt = Instant.fromEpochMilliseconds(6_000),
                ),
            ),
        )
        every { sourceStatusRepository.observeSources() } returns MutableStateFlow(
            mapOf(SourceType.GMAIL to status(SourceType.GMAIL, SourceConnectionStatus.ERROR)),
        )
        every { sourceConnectionRepository.observeAll("user-1") } returns MutableStateFlow(
            listOf(
                sourceConnection(id = "conn-synced", status = "synced"),
                sourceConnection(id = "conn-failed", status = "needs_reauth"),
            ),
        )

        val viewModel = ProcessingStatusViewModel(
            processingStatusRepository = processingStatusRepository,
            sourceStatusRepository = sourceStatusRepository,
            rawIngestionRepository = rawIngestionRepository,
            audioProcessingConfirmationRepository = audioProcessingConfirmationRepository,
            sourceSyncPort = sourceSyncPort,
            sourceConnectionRepository = sourceConnectionRepository,
            authRepository = authRepository,
        )

        viewModel.effects.test {
            viewModel.onRecoveryAction(SourceType.GMAIL, ProcessingStatusRecoveryActionType.RECONNECT_SOURCE)

            assertEquals(
                ProcessingStatusEffect.OpenReconnect(
                    destination = SourceReconnectDestination.GMAIL,
                    sourceType = SourceType.GMAIL,
                    sourceConnectionId = "conn-failed",
                ),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reconnect recovery omits target id when provider has multiple accounts`() = runTest {
        every { processingStatusRepository.observeAll() } returns MutableStateFlow(
            listOf(
                ProcessingSourceState(
                    sourceType = SourceType.GMAIL,
                    phase = ProcessingPhase.ERROR,
                    message = "reauth required",
                    updatedAt = Instant.fromEpochMilliseconds(6_000),
                ),
            ),
        )
        every { sourceStatusRepository.observeSources() } returns MutableStateFlow(
            mapOf(SourceType.GMAIL to status(SourceType.GMAIL, SourceConnectionStatus.ERROR)),
        )
        every { sourceConnectionRepository.observeAll("user-1") } returns MutableStateFlow(
            listOf(
                sourceConnection(id = "conn-synced", status = "synced", accountIdentifier = "current@example.com"),
                sourceConnection(id = "conn-stale", status = "needs_reauth", accountIdentifier = "old@example.com"),
            ),
        )

        val viewModel = ProcessingStatusViewModel(
            processingStatusRepository = processingStatusRepository,
            sourceStatusRepository = sourceStatusRepository,
            rawIngestionRepository = rawIngestionRepository,
            audioProcessingConfirmationRepository = audioProcessingConfirmationRepository,
            sourceSyncPort = sourceSyncPort,
            sourceConnectionRepository = sourceConnectionRepository,
            authRepository = authRepository,
        )

        viewModel.effects.test {
            viewModel.onRecoveryAction(SourceType.GMAIL, ProcessingStatusRecoveryActionType.RECONNECT_SOURCE)

            assertEquals(
                ProcessingStatusEffect.OpenReconnect(
                    destination = SourceReconnectDestination.GMAIL,
                    sourceType = SourceType.GMAIL,
                    sourceConnectionId = null,
                ),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun status(
        sourceType: String,
        connectionStatus: SourceConnectionStatus,
    ): SourceStatus = SourceStatus(
        sourceType = sourceType,
        status = connectionStatus,
        lastSyncedAt = null,
        errorMessage = null,
    )

    private fun sourceConnection(
        id: String,
        status: String,
        accountIdentifier: String = "user@example.com",
    ): SourceConnectionEntity = SourceConnectionEntity(
        id = id,
        userId = "user-1",
        provider = "google",
        capability = "mail",
        accountIdentifier = accountIdentifier,
        accountDisplayName = "User",
        ownership = "self",
        status = status,
        linkedSelfAnchorId = null,
        lastSyncAt = Instant.fromEpochMilliseconds(7_000),
        lastError = if (status == "needs_reauth") "token expired" else null,
    )

    private fun session(): SupabaseSession = SupabaseSession(
        accessToken = "access",
        refreshToken = "refresh",
        userId = "user-1",
        email = "user@example.com",
        expiresAt = Instant.fromEpochMilliseconds(60_000),
    )
}
