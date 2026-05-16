package com.becalm.android.ui.evidence

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.data.local.datastore.UserPrefsStore
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

public enum class EvidenceImportPersistentStatus {
    NONE,
    PROCESSING,
    REVIEW_REQUIRED,
}

public interface EvidenceImportStatusProjectionPort {
    public fun observeStatus(): Flow<EvidenceImportPersistentStatus>
}

@Singleton
public class RoomEvidenceImportStatusProjectionPort @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    private val rawIngestionEventDao: RawIngestionEventDao,
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
                        personIndexDao.observeEvidenceImportUnmatchedInteractionCount(userId),
                        personIndexDao.observeEvidenceImportUnresolvedSourceEventParticipantCount(userId),
                    ) { processingCount, unmatchedCount, unresolvedParticipantCount ->
                        when {
                            unmatchedCount + unresolvedParticipantCount > 0 ->
                                EvidenceImportPersistentStatus.REVIEW_REQUIRED
                            processingCount > 0 -> EvidenceImportPersistentStatus.PROCESSING
                            else -> EvidenceImportPersistentStatus.NONE
                        }
                    }
                }
            }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)
}

@Module
@InstallIn(SingletonComponent::class)
public abstract class EvidenceImportStatusProjectionModule {
    @Binds
    @Singleton
    public abstract fun bindEvidenceImportStatusProjectionPort(
        impl: RoomEvidenceImportStatusProjectionPort,
    ): EvidenceImportStatusProjectionPort
}
