package com.becalm.android.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.ManualMemoryOutboxDao
import com.becalm.android.data.repository.ManualMemoryOutboxSyncEngine
import com.becalm.android.data.repository.ManualMemoryOutboxSyncOutcome
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@HiltWorker
public class ManualMemoryOutboxWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val userPrefsStore: UserPrefsStore,
    private val outboxDaoProvider: Provider<ManualMemoryOutboxDao>,
    private val syncEngineProvider: Provider<ManualMemoryOutboxSyncEngine>,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, workerParams) {

    public constructor(
        appContext: Context,
        workerParams: WorkerParameters,
        userPrefsStore: UserPrefsStore,
        outboxDao: ManualMemoryOutboxDao,
        syncEngine: ManualMemoryOutboxSyncEngine,
        logger: Logger,
        ioDispatcher: CoroutineDispatcher,
    ) : this(
        appContext = appContext,
        workerParams = workerParams,
        userPrefsStore = userPrefsStore,
        outboxDaoProvider = Provider { outboxDao },
        syncEngineProvider = Provider { syncEngine },
        logger = logger,
        ioDispatcher = ioDispatcher,
    )

    override suspend fun doWork(): Result = withContext(ioDispatcher) {
        val userId = userPrefsStore.observeCurrentUserId().first()
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.success()
        val outboxDao = outboxDaoProvider.get()
        val pending = outboxDao.findPendingForUser(userId = userId, limit = BATCH_LIMIT + 1)
        if (pending.isEmpty()) return@withContext Result.success()

        val rows = pending.take(BATCH_LIMIT)
        var retryNeeded = pending.size > BATCH_LIMIT
        val syncEngine = syncEngineProvider.get()
        rows.forEach { row ->
            val outcome = try {
                syncEngine.sync(row)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.w(TAG, "manual memory outbox sync failed; retrying", error)
                retryNeeded = true
                return@forEach
            }
            when (outcome) {
                ManualMemoryOutboxSyncOutcome.SYNCED -> Unit
                ManualMemoryOutboxSyncOutcome.RETRY_NEEDED -> retryNeeded = true
                ManualMemoryOutboxSyncOutcome.TERMINAL_FAILURE -> logger.w(
                    TAG,
                    "manual memory outbox row failed terminally client_memory_id=${row.clientMemoryId}",
                )
            }
        }
        if (retryNeeded) Result.retry() else Result.success()
    }

    private companion object {
        private const val TAG = "ManualMemoryOutboxWorker"
        private const val BATCH_LIMIT = 50
    }
}
