package com.becalm.android.unit.ui.settings

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.PipaActionLogEntry
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.PersonEnrichmentDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceArchiveDeleteResult
import com.becalm.android.data.repository.SourceArchiveSummary
import com.becalm.android.data.repository.SourceArtifactRepository
import com.becalm.android.ui.settings.PrivacyDataExporter
import com.becalm.android.ui.settings.PrivacyExportPayload
import com.becalm.android.ui.settings.PrivacyManagementEffect
import com.becalm.android.ui.settings.PrivacyManagementViewModel
import com.becalm.android.ui.settings.WithdrawConsentTarget
import com.becalm.android.worker.AppRuntimeSyncCoordinator
import com.becalm.android.worker.ForegroundCatchUpScheduler
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyManagementViewModelSpecTest {

    private val dispatcher = StandardTestDispatcher()
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val rawRepo: RawIngestionRepository = mockk(relaxed = true)
    private val rawDao: RawIngestionEventDao = mockk(relaxed = true)
    private val commitmentDao: CommitmentDao = mockk(relaxed = true)
    private val enrichmentDao: PersonEnrichmentDao = mockk(relaxed = true)
    private val exporter: PrivacyDataExporter = mockk(relaxed = true)
    private val sourceArtifactRepository: SourceArtifactRepository = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val appRuntimeSyncCoordinator: AppRuntimeSyncCoordinator = mockk(relaxed = true)
    private val foregroundCatchUpScheduler: ForegroundCatchUpScheduler = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { authRepository.currentSession() } returns fakeSession
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        every { userPrefsStore.observeEmailSourceConnected(EmailPipaProvider.GMAIL) } returns flowOf(true)
        every { userPrefsStore.observeEmailSourceConnected(EmailPipaProvider.OUTLOOK_MAIL) } returns flowOf(false)
        every { userPrefsStore.observeEmailSourceConnected(EmailPipaProvider.NAVER_IMAP) } returns flowOf(false)
        every { userPrefsStore.observeEmailSourceConnected(EmailPipaProvider.DAUM_IMAP) } returns flowOf(false)
        every { userPrefsStore.observeSourceEnabled("google_calendar") } returns flowOf(true)
        every { userPrefsStore.observeSourceEnabled("outlook_calendar") } returns flowOf(false)
        every { userPrefsStore.observeProcessingPaused() } returns flowOf(false)
        every { userPrefsStore.observePauseStartedAt() } returns flowOf(null)
        every { userPrefsStore.observePipaActionLog() } returns flowOf(emptyList<PipaActionLogEntry>())
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        coEvery { commitmentDao.countForUser("user-1") } returns 3
        coEvery { rawDao.countEmailRowsForUser("user-1") } returns 5
        coEvery { enrichmentDao.countAll() } returns 2
        coEvery { sourceArtifactRepository.summary("user-1") } returns SourceArchiveSummary(count = 0, totalBytes = 0L)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `PIPA-002 export emits create-document effect`() = runTest {
        val payload = PrivacyExportPayload("becalm_export.zip", byteArrayOf(1, 2, 3))
        coEvery { exporter.export("user-1", any()) } returns payload

        val viewModel = buildViewModel()
        advanceUntilIdle()
        val effectDeferred = async { viewModel.effects.first() }
        viewModel.onExportRequested()
        advanceUntilIdle()

        val effect = effectDeferred.await()
        assertTrue(effect is PrivacyManagementEffect.CreateExportDocument)
        effect as PrivacyManagementEffect.CreateExportDocument
        assertEquals("becalm_export.zip", effect.fileName)
        assertEquals(3, effect.bytes.size)
    }

    @Test
    fun `PIPA-004 resume clears pause and triggers catch-up`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onSetProcessingPaused(false)
        advanceUntilIdle()

        coVerify { userPrefsStore.setProcessingPaused(false) }
        coVerify { userPrefsStore.setPauseStartedAt(null) }
        coVerify { userPrefsStore.appendPipaActionLog(match { it.action == "processing_resume" }) }
        verify { appRuntimeSyncCoordinator.refresh() }
        verify { foregroundCatchUpScheduler.triggerCatchUp() }
    }

    @Test
    fun `PIPA-003 withdrawing voice consent parks rows and cancels queued uploads`() = runTest {
        coEvery { rawRepo.parkAndCancelPendingVoice("user-1") } returns BecalmResult.Success(listOf("raw-1", "raw-2"))

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.onWithdrawConsent(WithdrawConsentTarget.VOICE)
        advanceUntilIdle()

        coVerify { userPrefsStore.setThirdPartyProvisionConsent(false) }
        coVerify { rawRepo.parkAndCancelPendingVoice("user-1") }
        verify { workScheduler.cancelVoiceUpload("raw-1") }
        verify { workScheduler.cancelVoiceUpload("raw-2") }
        coVerify { userPrefsStore.appendPipaActionLog(match { it.action == "consent_withdraw" && it.details["consent_type"] == "pipa_third_party" }) }
    }

    @Test
    fun `PIPA-005 local account delete confirms then signs out`() = runTest {
        coEvery { authRepository.signOut() } returns BecalmResult.Success(Unit)

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.onConfirmAccountDeletion("user@example.com", "삭제")
        advanceUntilIdle()

        coVerify { userPrefsStore.appendPipaActionLog(match { it.action == "account_delete_initiated" }) }
        coVerify { authRepository.signOut() }
        assertTrue(viewModel.uiState.value.signedOut)
    }

    @Test
    fun `source archive delete before removes local originals and emits count`() = runTest {
        coEvery { sourceArtifactRepository.summary("user-1") } returnsMany listOf(
            SourceArchiveSummary(count = 2, totalBytes = 4096L),
            SourceArchiveSummary(count = 1, totalBytes = 1024L),
        )
        coEvery {
            sourceArtifactRepository.deleteBefore("user-1", any())
        } returns SourceArchiveDeleteResult(deletedCount = 1, failedCount = 0)

        val viewModel = buildViewModel()
        advanceUntilIdle()
        val effectDeferred = async { viewModel.effects.first() }
        viewModel.onDeleteSourceArchiveBefore("2026-04-01")
        advanceUntilIdle()

        val effect = effectDeferred.await()
        assertEquals(1, (effect as PrivacyManagementEffect.SourceArchiveDeleted).deletedCount)
        assertEquals(1, viewModel.uiState.value.sourceArchiveCount)
        assertEquals(1024L, viewModel.uiState.value.sourceArchiveBytes)
        coVerify { sourceArtifactRepository.deleteBefore("user-1", any()) }
        coVerify {
            userPrefsStore.appendPipaActionLog(
                match { it.action == "source_archive_delete_before" && it.details["deleted_count"] == "1" },
            )
        }
    }

    private fun buildViewModel(): PrivacyManagementViewModel = PrivacyManagementViewModel(
        userPrefsStore = userPrefsStore,
        authRepository = authRepository,
        rawIngestionRepository = rawRepo,
        rawIngestionEventDao = rawDao,
        commitmentDao = commitmentDao,
        personEnrichmentDao = enrichmentDao,
        privacyDataExporter = exporter,
        sourceArtifactRepository = sourceArtifactRepository,
        workScheduler = workScheduler,
        appRuntimeSyncCoordinator = appRuntimeSyncCoordinator,
        foregroundCatchUpScheduler = foregroundCatchUpScheduler,
        logger = logger,
        ioDispatcher = dispatcher,
    )

    private val fakeSession = SupabaseSession(
        accessToken = "access",
        refreshToken = "refresh",
        userId = "user-1",
        email = "user@example.com",
        expiresAt = kotlinx.datetime.Instant.parse("2026-05-01T00:00:00Z"),
    )
}
