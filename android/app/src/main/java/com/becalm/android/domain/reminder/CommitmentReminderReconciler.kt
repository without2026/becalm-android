package com.becalm.android.domain.reminder

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentScheduleStatus
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

public interface CommitmentReminderReconcilePort {
    public suspend fun reconcileRows(rows: List<CommitmentEntity>)
}

@Singleton
public class CommitmentReminderReconciler @Inject constructor(
    private val commitmentDao: CommitmentDao,
    private val userPrefsStore: UserPrefsStore,
    private val reminderScheduler: ReminderScheduler,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CommitmentReminderReconcilePort {
    public suspend fun reconcileUser(userId: String, limit: Int = DEFAULT_LIMIT) {
        if (userId.isBlank()) return
        withContext(ioDispatcher) {
            reconcileRows(
                rows = commitmentDao.findReminderCandidatesForUser(
                    userId = userId,
                    now = Clock.System.now(),
                    limit = limit,
                ),
            )
        }
    }

    override suspend fun reconcileRows(rows: List<CommitmentEntity>) {
        if (rows.isEmpty()) return
        val disabledIds = userPrefsStore.observeDisabledCommitmentReminderIds().firstOrNull().orEmpty()
        rows
            .asSequence()
            .filter(::isReminderEligible)
            .filterNot { it.id in disabledIds }
            .forEach { row ->
                reminderScheduler.schedule(row.id, row.dueAt)
            }
        logger.d(TAG, "reminder reconcile rows=${rows.size}")
    }

    public companion object {
        public fun isReminderEligible(row: CommitmentEntity): Boolean {
            if (row.deletedAt != null) return false
            if (row.dueAt == null || row.dueIsApproximate) return false
            if (row.actionState in TERMINAL_ACTION_STATES) return false
            return when (row.itemType) {
                CommitmentItemType.ACTION -> true
                CommitmentItemType.SCHEDULE -> row.scheduleStatus !in EXCLUDED_SCHEDULE_STATUSES
                else -> false
            }
        }

        private const val DEFAULT_LIMIT: Int = 500
        private const val TAG: String = "ReminderReconciler"
        private val TERMINAL_ACTION_STATES: Set<String> = setOf("completed", "cancelled")
        private val EXCLUDED_SCHEDULE_STATUSES: Set<String> = setOf(
            CommitmentScheduleStatus.TENTATIVE,
            CommitmentScheduleStatus.CANCELLED,
            CommitmentScheduleStatus.POSTPONED,
        )
    }
}

public object NoopCommitmentReminderReconcilePort : CommitmentReminderReconcilePort {
    override suspend fun reconcileRows(rows: List<CommitmentEntity>) = Unit
}

@Module
@InstallIn(SingletonComponent::class)
public abstract class CommitmentReminderReconcileModule {
    @Binds
    @Singleton
    public abstract fun bindCommitmentReminderReconcilePort(
        impl: CommitmentReminderReconciler,
    ): CommitmentReminderReconcilePort
}
