package com.becalm.android.ui.today

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.repository.UserProfileRepository
import com.becalm.android.worker.ForegroundWorkScheduler
import com.becalm.android.worker.WorkScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant

/**
 * Internal cold-sync owner for Stage 1 / Stage 2 fan-out and local profile bootstrap.
 *
 * The goal is to keep ColdSync screen / workers free of raw scheduling details while making
 * the startup contract testable at the unit and local-integration layers.
 */
public interface ColdSyncRuntimeCoordinator {
    public fun observeUserProfileReady(): Flow<Boolean>
    public suspend fun startStage1(now: Instant): BecalmResult<Unit>
    public suspend fun startStage2(now: Instant): BecalmResult<Unit>
}

@Singleton
public class DefaultColdSyncRuntimeCoordinator @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    private val userProfileRepository: UserProfileRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val foregroundWorkScheduler: ForegroundWorkScheduler,
    private val workScheduler: WorkScheduler,
    private val logger: Logger,
) : ColdSyncRuntimeCoordinator {

    override fun observeUserProfileReady(): Flow<Boolean> =
        userPrefsStore.observeCurrentUserId().flatMapLatest { userId ->
            if (userId.isNullOrBlank()) {
                flowOf(false)
            } else {
                userProfileRepository.observe(userId).map { it != null }
            }
        }

    override suspend fun startStage1(now: Instant): BecalmResult<Unit> =
        runCatching {
            val userId = requireCurrentUserId()
            bootstrapUserProfile(userId)

            STAGE1_SOURCE_TYPES.forEach { sourceStatusRepository.recordSyncStart(it) }
            foregroundWorkScheduler.enqueueGCalOneShotNow(STAGE1_LOOKBACK_DAYS)
            foregroundWorkScheduler.enqueueOutlookCalOneShotNow(STAGE1_LOOKBACK_DAYS)
            foregroundWorkScheduler.enqueueGmailOneShotNow(STAGE1_LOOKBACK_DAYS)
            foregroundWorkScheduler.enqueueOutlookMailOneShotNow(STAGE1_LOOKBACK_DAYS)
            foregroundWorkScheduler.enqueueImapNaverOneShotNow(STAGE1_LOOKBACK_DAYS)
            foregroundWorkScheduler.enqueueImapDaumOneShotNow(STAGE1_LOOKBACK_DAYS)
            workScheduler.enqueueUpload(attempt = 0)
            logger.d(TAG, "startStage1 complete at=$now userIdHash=${userId.hashCode()}")
        }.fold(
            onSuccess = { BecalmResult.Success(Unit) },
            onFailure = { BecalmResult.Failure(BecalmError.Unknown(it)) },
        )

    override suspend fun startStage2(now: Instant): BecalmResult<Unit> =
        runCatching {
            requireCurrentUserId()
            STAGE2_SOURCE_TYPES.forEach { sourceStatusRepository.recordSyncStart(it) }
            foregroundWorkScheduler.enqueueGmailOneShotNow(STAGE2_LOOKBACK_DAYS)
            foregroundWorkScheduler.enqueueOutlookMailOneShotNow(STAGE2_LOOKBACK_DAYS)
            foregroundWorkScheduler.enqueueImapNaverOneShotNow(STAGE2_LOOKBACK_DAYS)
            foregroundWorkScheduler.enqueueImapDaumOneShotNow(STAGE2_LOOKBACK_DAYS)
            foregroundWorkScheduler.enqueueMediaStoreOneShotNow(STAGE2_LOOKBACK_DAYS)
            logger.d(TAG, "startStage2 complete at=$now")
        }.fold(
            onSuccess = { BecalmResult.Success(Unit) },
            onFailure = { BecalmResult.Failure(BecalmError.Unknown(it)) },
        )

    private suspend fun requireCurrentUserId(): String {
        val userId = userPrefsStore.observeCurrentUserId().first()
        require(!userId.isNullOrBlank()) { "no current user for cold sync" }
        return userId
    }

    private suspend fun bootstrapUserProfile(userId: String) {
        val preferredLocale = userPrefsStore.observeLocaleTag().first()
            ?.substringBefore('-')
            ?.ifBlank { null }
            ?: "ko"
        userProfileRepository.bootstrapIfMissing(
            userId = userId,
            timezone = "Asia/Seoul",
            preferredLocale = preferredLocale,
        )
    }

    public companion object {
        public const val USER_PROFILE_SOURCE_ID: String = "user_profile"
        public const val STAGE1_LOOKBACK_DAYS: Int = 7
        public const val STAGE2_LOOKBACK_DAYS: Int = 30
        public val STAGE1_SOURCE_TYPES: List<String> = listOf(
            SourceType.GOOGLE_CALENDAR,
            SourceType.OUTLOOK_CALENDAR,
            SourceType.GMAIL,
            SourceType.OUTLOOK_MAIL,
            SourceType.NAVER_IMAP,
            SourceType.DAUM_IMAP,
        )
        public val STAGE2_SOURCE_TYPES: List<String> = listOf(
            SourceType.GMAIL,
            SourceType.OUTLOOK_MAIL,
            SourceType.NAVER_IMAP,
            SourceType.DAUM_IMAP,
            SourceType.VOICE,
        )
        private const val TAG: String = "ColdSyncRuntime"
    }
}

@Module
@InstallIn(SingletonComponent::class)
public abstract class ColdSyncRuntimeCoordinatorModule {
    @Binds
    @Singleton
    public abstract fun bindColdSyncRuntimeCoordinator(
        impl: DefaultColdSyncRuntimeCoordinator,
    ): ColdSyncRuntimeCoordinator
}
