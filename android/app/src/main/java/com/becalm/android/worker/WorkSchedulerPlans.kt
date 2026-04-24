package com.becalm.android.worker

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.becalm.android.core.util.Logger

internal sealed interface WorkSchedulerPlan {
    fun execute(workManager: WorkManager, logger: Logger)
}

internal data class UniqueOneTimeWorkPlan(
    val uniqueKey: String,
    val policy: ExistingWorkPolicy,
    val request: OneTimeWorkRequest,
    val logMessage: String,
) : WorkSchedulerPlan {
    override fun execute(workManager: WorkManager, logger: Logger) {
        workManager.enqueueUniqueWork(uniqueKey, policy, request)
        logger.d(WORK_SCHEDULER_TAG, logMessage)
    }
}

internal data class UniquePeriodicWorkPlan(
    val uniqueKey: String,
    val policy: ExistingPeriodicWorkPolicy,
    val request: PeriodicWorkRequest,
    val logMessage: String,
) : WorkSchedulerPlan {
    override fun execute(workManager: WorkManager, logger: Logger) {
        workManager.enqueueUniquePeriodicWork(uniqueKey, policy, request)
        logger.d(WORK_SCHEDULER_TAG, logMessage)
    }
}

internal data class CancelUniqueWorkPlan(
    val uniqueKey: String,
    val logMessage: String,
) : WorkSchedulerPlan {
    override fun execute(workManager: WorkManager, logger: Logger) {
        workManager.cancelUniqueWork(uniqueKey)
        logger.d(WORK_SCHEDULER_TAG, logMessage)
    }
}

internal class WorkSchedulerPlanRunner(
    private val workManager: WorkManager,
    private val logger: Logger,
) {
    fun run(plan: WorkSchedulerPlan) {
        plan.execute(workManager, logger)
    }
}

internal const val WORK_SCHEDULER_TAG: String = "WorkScheduler"
