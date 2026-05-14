package com.becalm.android.productanalytics

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
public class ProductAnalyticsFlushScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    public fun enqueueFlush() {
        workManager.enqueueUniqueWork(
            UNIQUE_FLUSH,
            ExistingWorkPolicy.REPLACE,
            oneTimeRequest(),
        )
    }

    public fun schedulePeriodicFlush() {
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_FLUSH,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequest.Builder(ProductAnalyticsFlushWorker::class.java, 15, TimeUnit.MINUTES)
                .setConstraints(CONSTRAINTS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    private fun oneTimeRequest(): OneTimeWorkRequest =
        OneTimeWorkRequest.Builder(ProductAnalyticsFlushWorker::class.java)
            .setConstraints(CONSTRAINTS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

    public companion object {
        public const val UNIQUE_FLUSH: String = "analytics.product.flush"
        public const val UNIQUE_PERIODIC_FLUSH: String = "analytics.product.flush.periodic"
        private val CONSTRAINTS: Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
