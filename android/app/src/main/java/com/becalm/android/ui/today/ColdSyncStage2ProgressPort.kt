package com.becalm.android.ui.today

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.WorkScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.Instant

/**
 * Stage 2 banner / progress-sheet snapshot for COLD-005.
 *
 * The default implementation is intentionally conservative: until a richer worker progress
 * store lands, the product can still expose banner visibility, resumable/deferred state,
 * and a stable percent field that tests can fake.
 */
public data class ColdSyncStage2ProgressState(
    val bannerVisible: Boolean,
    val progressPercent: Int,
    val emailBackfillProcessed: Int,
    val emailBackfillTotal: Int,
    val voiceScanProcessed: Int,
    val voiceScanTotal: Int,
    val canDefer: Boolean,
    val deferred: Boolean,
    val completedAt: Instant?,
)

/** Owner seam for the Stage 2 banner / sheet contract. */
public interface ColdSyncStage2ProgressPort {
    public fun observeState(): Flow<ColdSyncStage2ProgressState>
    public suspend fun deferStage2(now: Instant): BecalmResult<ColdSyncStage2ProgressState>
}

@Singleton
public class DefaultColdSyncStage2ProgressPort @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    private val sourceStatusRepository: SourceStatusRepository,
    private val rawIngestionRepository: RawIngestionRepository,
    private val workScheduler: WorkScheduler,
) : ColdSyncStage2ProgressPort {

    override fun observeState(): Flow<ColdSyncStage2ProgressState> =
        combine(
            observeStage2Meta(),
            observeStage2Counts(),
        ) { meta, counts ->
            val completedInstant = meta.stage2CompletedAt?.let(Instant::fromEpochMilliseconds)
            val bannerVisible = meta.stage1CompletedAt != null && meta.stage2CompletedAt == null
            val progressPercent = if (meta.stage2CompletedAt != null) 100 else ((meta.completedSourceCount / 5f) * 100).toInt()
            ColdSyncStage2ProgressState(
                bannerVisible = bannerVisible,
                progressPercent = progressPercent,
                emailBackfillProcessed = counts.emailCount,
                emailBackfillTotal = counts.emailCount,
                voiceScanProcessed = counts.voiceCount,
                voiceScanTotal = counts.voiceCount,
                canDefer = bannerVisible && !meta.stage2Deferred,
                deferred = meta.stage2Deferred,
                completedAt = completedInstant,
            )
        }

    override suspend fun deferStage2(now: Instant): BecalmResult<ColdSyncStage2ProgressState> =
        runCatching {
            workScheduler.cancelColdSyncStage2()
            userPrefsStore.setColdSyncStage2Deferred(true)
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
            )
        }.fold(
            onSuccess = { BecalmResult.Success(it) },
            onFailure = {
                BecalmResult.Failure(
                    BecalmError.Unknown(
                        IllegalStateException(it.message ?: "cold sync stage2 defer failed", it),
                    ),
                )
            },
        )

    private fun observeEmailBackfillCount(): Flow<Int> =
        observeStage2CountForSources(STAGE2_EMAIL_SOURCES)

    private fun observeVoiceScanCount(): Flow<Int> =
        observeStage2CountForSources(STAGE2_VOICE_SOURCES)

    private fun observeStage2CountForSources(sourceTypes: List<String>): Flow<Int> =
        userPrefsStore.observeCurrentUserId().flatMapLatest { userId ->
            if (userId.isNullOrBlank()) {
                flowOf(0)
            } else {
                rawIngestionRepository.observeCountForSourceTypesSince(
                    userId = userId,
                    sourceTypes = sourceTypes,
                    since = stage2LookbackStart(),
                )
            }
        }

    private fun stage2LookbackStart(): Instant =
        Instant.fromEpochMilliseconds(
            kotlinx.datetime.Clock.System.now().toEpochMilliseconds() -
                DefaultColdSyncRuntimeCoordinator.STAGE2_LOOKBACK_DAYS * 86_400_000L,
        )

    private fun observeStage2Meta(): Flow<Stage2Meta> =
        combine(
            userPrefsStore.observeColdSyncStage1CompletedAt(),
            userPrefsStore.observeColdSyncStage2CompletedAt(),
            userPrefsStore.observeColdSyncStage2Deferred(),
            sourceStatusRepository.observeSources(),
        ) { stage1CompletedAt, stage2CompletedAt, stage2Deferred, sourceStatuses ->
            Stage2Meta(
                stage1CompletedAt = stage1CompletedAt,
                stage2CompletedAt = stage2CompletedAt,
                stage2Deferred = stage2Deferred,
                completedSourceCount = STAGE2_EMAIL_SOURCES.count { source ->
                    sourceStatuses[source]?.status in TERMINAL_STATUSES
                } + if (sourceStatuses[SourceType.VOICE]?.status in TERMINAL_STATUSES) 1 else 0,
            )
        }

    private fun observeStage2Counts(): Flow<Stage2Counts> =
        combine(
            observeEmailBackfillCount(),
            observeVoiceScanCount(),
        ) { emailCount, voiceCount ->
            Stage2Counts(emailCount = emailCount, voiceCount = voiceCount)
        }
}

@Module
@InstallIn(SingletonComponent::class)
public abstract class ColdSyncStage2ProgressModule {
    @Binds
    @Singleton
    public abstract fun bindColdSyncStage2ProgressPort(
        impl: DefaultColdSyncStage2ProgressPort,
    ): ColdSyncStage2ProgressPort
}

private val STAGE2_EMAIL_SOURCES: List<String> = listOf(
    SourceType.GMAIL,
    SourceType.OUTLOOK_MAIL,
    SourceType.NAVER_IMAP,
    SourceType.DAUM_IMAP,
)

private val STAGE2_VOICE_SOURCES: List<String> = listOf(
    SourceType.VOICE,
    SourceType.CALL_RECORDING,
)

private val TERMINAL_STATUSES: Set<SourceConnectionStatus> = setOf(
    SourceConnectionStatus.CONNECTED,
    SourceConnectionStatus.ERROR,
)

private data class Stage2Meta(
    val stage1CompletedAt: Long?,
    val stage2CompletedAt: Long?,
    val stage2Deferred: Boolean,
    val completedSourceCount: Int,
)

private data class Stage2Counts(
    val emailCount: Int,
    val voiceCount: Int,
)
