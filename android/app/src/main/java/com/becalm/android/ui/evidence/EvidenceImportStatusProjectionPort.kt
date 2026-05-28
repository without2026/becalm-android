package com.becalm.android.ui.evidence

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.MeetingSpeakerPreviewDao
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

public enum class EvidenceImportStatusPhase {
    NONE,
    PROCESSING,
    LONG_RUNNING,
    CONSENT_REQUIRED,
    REVIEW_REQUIRED,
    FAILED,
}

public data class EvidenceImportPersistentStatus(
    val phase: EvidenceImportStatusPhase,
    val processingCount: Int = 0,
    val consentRequiredCount: Int = 0,
    val reviewRequiredCount: Int = 0,
    val meetingReviewRequiredCount: Int = 0,
    val personReviewRequiredCount: Int = 0,
    val failedCount: Int = 0,
    val oldestStartedAt: Instant? = null,
) {
    public companion object {
        public val NONE: EvidenceImportPersistentStatus =
            EvidenceImportPersistentStatus(phase = EvidenceImportStatusPhase.NONE)
        public val PROCESSING: EvidenceImportPersistentStatus =
            processing(processingCount = 1)
        public val REVIEW_REQUIRED: EvidenceImportPersistentStatus =
            reviewRequired(reviewRequiredCount = 1)
        public val CONSENT_REQUIRED: EvidenceImportPersistentStatus =
            consentRequired(consentRequiredCount = 1)
        public val FAILED: EvidenceImportPersistentStatus =
            failed(failedCount = 1)

        public fun processing(
            processingCount: Int,
            oldestStartedAt: Instant? = null,
            phase: EvidenceImportStatusPhase = EvidenceImportStatusPhase.PROCESSING,
        ): EvidenceImportPersistentStatus =
            EvidenceImportPersistentStatus(
                phase = phase,
                processingCount = processingCount.coerceAtLeast(0),
                oldestStartedAt = oldestStartedAt,
            )

        public fun reviewRequired(
            reviewRequiredCount: Int,
            processingCount: Int = 0,
            consentRequiredCount: Int = 0,
            meetingReviewRequiredCount: Int = 0,
            personReviewRequiredCount: Int = reviewRequiredCount,
            oldestStartedAt: Instant? = null,
        ): EvidenceImportPersistentStatus =
            EvidenceImportPersistentStatus(
                phase = EvidenceImportStatusPhase.REVIEW_REQUIRED,
                processingCount = processingCount.coerceAtLeast(0),
                consentRequiredCount = consentRequiredCount.coerceAtLeast(0),
                reviewRequiredCount = reviewRequiredCount.coerceAtLeast(0),
                meetingReviewRequiredCount = meetingReviewRequiredCount.coerceAtLeast(0),
                personReviewRequiredCount = personReviewRequiredCount.coerceAtLeast(0),
                oldestStartedAt = oldestStartedAt,
            )

        public fun consentRequired(
            consentRequiredCount: Int,
            processingCount: Int = 0,
            oldestStartedAt: Instant? = null,
        ): EvidenceImportPersistentStatus =
            EvidenceImportPersistentStatus(
                phase = EvidenceImportStatusPhase.CONSENT_REQUIRED,
                processingCount = processingCount.coerceAtLeast(0),
                consentRequiredCount = consentRequiredCount.coerceAtLeast(0),
                oldestStartedAt = oldestStartedAt,
            )

        public fun failed(
            failedCount: Int,
            processingCount: Int = 0,
            consentRequiredCount: Int = 0,
            oldestStartedAt: Instant? = null,
        ): EvidenceImportPersistentStatus =
            EvidenceImportPersistentStatus(
                phase = EvidenceImportStatusPhase.FAILED,
                processingCount = processingCount.coerceAtLeast(0),
                consentRequiredCount = consentRequiredCount.coerceAtLeast(0),
                failedCount = failedCount.coerceAtLeast(0),
                oldestStartedAt = oldestStartedAt,
            )
    }
}

public interface EvidenceImportStatusProjectionPort {
    public fun observeStatus(): Flow<EvidenceImportPersistentStatus>
}

@Singleton
public class RoomEvidenceImportStatusProjectionPort @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val meetingSpeakerPreviewDao: MeetingSpeakerPreviewDao,
    private val personIndexDao: PersonIndexDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : EvidenceImportStatusProjectionPort {

    override fun observeStatus(): Flow<EvidenceImportPersistentStatus> =
        userPrefsStore.observeCurrentUserId()
            .distinctUntilChanged()
            .flatMapLatest { userId ->
                if (userId.isNullOrBlank()) {
                    flowOf(EvidenceImportPersistentStatus.NONE)
                } else {
                    combine(
                        rawIngestionEventDao.observeEvidenceImportProcessingCount(userId),
                        rawIngestionEventDao.observeEvidenceImportAwaitingConsentCount(userId),
                        rawIngestionEventDao.observeEvidenceImportFailedItemCount(userId),
                        rawIngestionEventDao.observeEvidenceImportOldestProcessingAt(userId),
                        meetingSpeakerPreviewDao.observeProcessingCount(userId),
                        meetingSpeakerPreviewDao.observeReviewRequiredCount(userId),
                        meetingSpeakerPreviewDao.observeOldestProcessingAt(userId),
                        personIndexDao.observeEvidenceImportUnmatchedInteractionCount(userId),
                        personIndexDao.observeEvidenceImportUnresolvedSourceEventParticipantCount(userId),
                    ) { values ->
                        val rawProcessingCount = values[0] as Int
                        val rawAwaitingConsentCount = values[1] as Int
                        val rawFailedCount = values[2] as Int
                        val rawOldestStartedAt = values[3] as Instant?
                        val meetingProcessingCount = values[4] as Int
                        val meetingReviewCount = values[5] as Int
                        val meetingOldestStartedAt = values[6] as Instant?
                        val unmatchedCount = values[7] as Int
                        val unresolvedParticipantCount = values[8] as Int
                        val personReviewCount = unmatchedCount + unresolvedParticipantCount
                        val processingCount = rawProcessingCount + meetingProcessingCount
                        when {
                            meetingReviewCount + personReviewCount > 0 ->
                                EvidenceImportPersistentStatus.reviewRequired(
                                    reviewRequiredCount = meetingReviewCount + personReviewCount,
                                    processingCount = processingCount,
                                    consentRequiredCount = rawAwaitingConsentCount,
                                    meetingReviewRequiredCount = meetingReviewCount,
                                    personReviewRequiredCount = personReviewCount,
                                    oldestStartedAt = minOfNonNull(rawOldestStartedAt, meetingOldestStartedAt),
                                )
                            rawAwaitingConsentCount > 0 ->
                                EvidenceImportPersistentStatus.consentRequired(
                                    consentRequiredCount = rawAwaitingConsentCount,
                                    processingCount = processingCount,
                                    oldestStartedAt = minOfNonNull(rawOldestStartedAt, meetingOldestStartedAt),
                                )
                            rawFailedCount > 0 ->
                                EvidenceImportPersistentStatus.failed(
                                    failedCount = rawFailedCount,
                                    processingCount = processingCount,
                                    consentRequiredCount = rawAwaitingConsentCount,
                                    oldestStartedAt = minOfNonNull(rawOldestStartedAt, meetingOldestStartedAt),
                                )
                            processingCount > 0 -> {
                                val oldestStartedAt = minOfNonNull(rawOldestStartedAt, meetingOldestStartedAt)
                                EvidenceImportPersistentStatus.processing(
                                    processingCount = processingCount,
                                    oldestStartedAt = oldestStartedAt,
                                    phase = if (oldestStartedAt.isLongRunning(Clock.System.now())) {
                                        EvidenceImportStatusPhase.LONG_RUNNING
                                    } else {
                                        EvidenceImportStatusPhase.PROCESSING
                                    },
                                )
                            }
                            else -> EvidenceImportPersistentStatus.NONE
                        }
                    }
                }
            }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)
}

private const val LONG_RUNNING_THRESHOLD_SECONDS: Long = 3 * 60

private fun minOfNonNull(first: Instant?, second: Instant?): Instant? =
    when {
        first == null -> second
        second == null -> first
        first <= second -> first
        else -> second
    }

private fun Instant?.isLongRunning(now: Instant): Boolean =
    this != null && now.epochSeconds - epochSeconds >= LONG_RUNNING_THRESHOLD_SECONDS

@Module
@InstallIn(SingletonComponent::class)
public abstract class EvidenceImportStatusProjectionModule {
    @Binds
    @Singleton
    public abstract fun bindEvidenceImportStatusProjectionPort(
        impl: RoomEvidenceImportStatusProjectionPort,
    ): EvidenceImportStatusProjectionPort
}
