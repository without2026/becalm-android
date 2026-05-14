package com.becalm.android.productanalytics

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
public class ProductAnalyticsFlushWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: ProductAnalyticsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        try {
            if (repository.flushBatch()) Result.success() else Result.retry()
        } catch (_: Throwable) {
            Result.retry()
        }
}
