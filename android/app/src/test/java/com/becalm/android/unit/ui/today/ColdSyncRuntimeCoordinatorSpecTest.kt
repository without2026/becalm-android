package com.becalm.android.unit.ui.today

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.UserProfileEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.repository.UserProfileRepository
import com.becalm.android.ui.today.DefaultColdSyncRuntimeCoordinator
import com.becalm.android.worker.ForegroundWorkScheduler
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ColdSyncRuntimeCoordinatorSpecTest {

    private val currentUserId = MutableStateFlow<String?>("user-1")
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val userProfileRepository: UserProfileRepository = mockk(relaxed = true)
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val foregroundWorkScheduler: ForegroundWorkScheduler = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    init {
        every { userPrefsStore.observeCurrentUserId() } returns currentUserId
        every { userPrefsStore.observeLocaleTag() } returns flowOf("ko-KR")
        every { userPrefsStore.observeEnabledSources() } returns flowOf(
            (DefaultColdSyncRuntimeCoordinator.STAGE1_SOURCE_TYPES +
                DefaultColdSyncRuntimeCoordinator.STAGE2_SOURCE_TYPES).toSet(),
        )
        every { userProfileRepository.observe("user-1") } returns flowOf(null)
    }

    @Test
    fun `COLD-001 startStage1 bootstraps user profile marks stage1 syncing and fans out stage1 sources`() = runTest {
        val coordinator = buildCoordinator()

        val result = coordinator.startStage1(Instant.parse("2026-04-23T00:00:00Z"))

        assertTrue(result is BecalmResult.Success<*>)
        coVerify(exactly = 1) {
            userProfileRepository.bootstrapIfMissing("user-1", "Asia/Seoul", "ko")
        }
        DefaultColdSyncRuntimeCoordinator.STAGE1_SOURCE_TYPES.forEach { sourceType ->
            coVerify(exactly = 1) { sourceStatusRepository.recordSyncStart(sourceType) }
        }
        coVerify(exactly = 0) { sourceStatusRepository.recordSyncStart(SourceType.VOICE) }
        verify(exactly = 1) { foregroundWorkScheduler.enqueueGCalOneShotNow(DefaultColdSyncRuntimeCoordinator.STAGE1_LOOKBACK_DAYS) }
        verify(exactly = 1) { foregroundWorkScheduler.enqueueOutlookCalOneShotNow(DefaultColdSyncRuntimeCoordinator.STAGE1_LOOKBACK_DAYS) }
        verify(exactly = 1) { foregroundWorkScheduler.enqueueImapNaverOneShotNow(DefaultColdSyncRuntimeCoordinator.STAGE1_LOOKBACK_DAYS) }
        verify(exactly = 1) { foregroundWorkScheduler.enqueueImapDaumOneShotNow(DefaultColdSyncRuntimeCoordinator.STAGE1_LOOKBACK_DAYS) }
        verify(exactly = 1) { workScheduler.enqueueUpload(0) }
    }

    @Test
    fun `COLD-001 startStage1 skips disabled sources`() = runTest {
        every { userPrefsStore.observeEnabledSources() } returns flowOf(setOf(SourceType.NAVER_IMAP))
        val coordinator = buildCoordinator()

        val result = coordinator.startStage1(Instant.parse("2026-04-23T00:00:00Z"))

        assertTrue(result is BecalmResult.Success<*>)
        coVerify(exactly = 1) { sourceStatusRepository.recordSyncStart(SourceType.NAVER_IMAP) }
        coVerify(exactly = 0) { sourceStatusRepository.recordSyncStart(SourceType.GOOGLE_CALENDAR) }
        coVerify(exactly = 0) { sourceStatusRepository.recordSyncStart(SourceType.OUTLOOK_CALENDAR) }
        coVerify(exactly = 0) { sourceStatusRepository.recordSyncStart(SourceType.DAUM_IMAP) }
        verify(exactly = 1) { foregroundWorkScheduler.enqueueImapNaverOneShotNow(DefaultColdSyncRuntimeCoordinator.STAGE1_LOOKBACK_DAYS) }
        verify(exactly = 0) { foregroundWorkScheduler.enqueueGCalOneShotNow(any()) }
        verify(exactly = 0) { foregroundWorkScheduler.enqueueOutlookCalOneShotNow(any()) }
        verify(exactly = 0) { foregroundWorkScheduler.enqueueImapDaumOneShotNow(any()) }
        verify(exactly = 1) { workScheduler.enqueueUpload(0) }
    }

    @Test
    fun `COLD-004 startStage2 marks stage2 syncing and fans out IMAP plus voice sources`() = runTest {
        val coordinator = buildCoordinator()

        val result = coordinator.startStage2(Instant.parse("2026-04-23T01:00:00Z"))

        assertTrue(result is BecalmResult.Success<*>)
        DefaultColdSyncRuntimeCoordinator.STAGE2_SOURCE_TYPES.forEach { sourceType ->
            coVerify(exactly = 1) { sourceStatusRepository.recordSyncStart(sourceType) }
        }
        verify(exactly = 1) { foregroundWorkScheduler.enqueueImapNaverOneShotNow(DefaultColdSyncRuntimeCoordinator.STAGE2_LOOKBACK_DAYS) }
        verify(exactly = 1) { foregroundWorkScheduler.enqueueImapDaumOneShotNow(DefaultColdSyncRuntimeCoordinator.STAGE2_LOOKBACK_DAYS) }
        verify(exactly = 1) { foregroundWorkScheduler.enqueueMediaStoreOneShotNow(DefaultColdSyncRuntimeCoordinator.STAGE2_LOOKBACK_DAYS) }
    }

    @Test
    fun `COLD-001 user profile readiness observes bootstrap row existence`() = runTest {
        every { userProfileRepository.observe("user-1") } returns flowOf(
            null,
            UserProfileEntity(
                userId = "user-1",
                displayNameOverride = null,
                phoneE164Self = null,
                timezone = "Asia/Seoul",
                preferredLocale = "ko",
                createdAt = Instant.parse("2026-04-23T00:00:00Z"),
                updatedAt = Instant.parse("2026-04-23T00:00:00Z"),
            ),
        )
        val coordinator = buildCoordinator()

        val emissions = mutableListOf<Boolean>()
        coordinator.observeUserProfileReady().take(2).toList(emissions)

        assertFalse(emissions.first())
        assertTrue(emissions.last())
    }

    private fun buildCoordinator(): DefaultColdSyncRuntimeCoordinator = DefaultColdSyncRuntimeCoordinator(
        userPrefsStore = userPrefsStore,
        userProfileRepository = userProfileRepository,
        sourceStatusRepository = sourceStatusRepository,
        foregroundWorkScheduler = foregroundWorkScheduler,
        workScheduler = workScheduler,
        logger = logger,
    )
}
