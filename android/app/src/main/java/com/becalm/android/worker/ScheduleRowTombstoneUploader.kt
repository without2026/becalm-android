package com.becalm.android.worker

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.repository.ScheduleRowTombstoneRepository

internal class ScheduleRowTombstoneUploader(
    private val repository: ScheduleRowTombstoneRepository,
    private val logger: Logger,
) {
    suspend fun flushTombstones(userId: String, attempt: Int): FlushOutcome {
        var totalUploaded = 0
        while (true) {
            val pending = repository.findPendingSync(userId, UploadWorker.BATCH_SIZE)
            if (pending.isEmpty()) break

            val syncedIds = mutableListOf<String>()
            for (entity in pending) {
                when (val result = repository.upload(entity)) {
                    is BecalmResult.Success -> syncedIds.add(entity.id)
                    is BecalmResult.Failure -> {
                        if (syncedIds.isNotEmpty()) repository.markSynced(syncedIds)
                        return mapErrorToOutcome(
                            logger = logger,
                            error = result.error,
                            attempt = attempt,
                            domain = "schedule_row_tombstone",
                        )
                    }
                }
            }
            if (syncedIds.isNotEmpty()) {
                repository.markSynced(syncedIds)
                totalUploaded += syncedIds.size
            }
        }
        return FlushOutcome.Success(totalUploaded)
    }
}
