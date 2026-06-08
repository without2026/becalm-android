package com.becalm.android.data.repository

import androidx.room.withTransaction
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.BeCalmDatabaseProvider
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.ManualMemoryOutboxDao
import com.becalm.android.data.local.db.entity.ManualMemoryOutboxEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.ManualMemoryCreateRequestDto
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Clock

public enum class ManualMemoryOutboxSyncOutcome {
    SYNCED,
    RETRY_NEEDED,
    TERMINAL_FAILURE,
}

@Singleton
public class ManualMemoryOutboxSyncEngine @Inject constructor(
    private val databaseProvider: BeCalmDatabaseProvider,
    private val outboxDao: ManualMemoryOutboxDao,
    private val commitmentDao: CommitmentDao,
    private val api: RailwayApi,
    private val logger: Logger,
) {
    public suspend fun sync(row: ManualMemoryOutboxEntity): ManualMemoryOutboxSyncOutcome {
        val response = try {
            api.createManualMemory(request = row.toRequest())
        } catch (error: IOException) {
            markRetryable(row, error.message ?: "network error")
            return ManualMemoryOutboxSyncOutcome.RETRY_NEEDED
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            markRetryable(row, error.message ?: error::class.java.simpleName)
            return ManualMemoryOutboxSyncOutcome.RETRY_NEEDED
        }

        if (!response.isSuccessful) {
            val error = "HTTP ${response.code()}"
            return if (response.code().isRetryableManualMemoryStatus()) {
                markRetryable(row, error)
                ManualMemoryOutboxSyncOutcome.RETRY_NEEDED
            } else {
                markTerminal(row, error)
                ManualMemoryOutboxSyncOutcome.TERMINAL_FAILURE
            }
        }

        val body = response.body()
        if (body == null) {
            markRetryable(row, "empty response")
            return ManualMemoryOutboxSyncOutcome.RETRY_NEEDED
        }
        if (body.personId != row.personId || body.commitmentId != row.commitmentId || body.sourceRef != row.sourceRef) {
            logger.w(TAG, "manual memory retry returned mismatched ids client_memory_id=${row.clientMemoryId}")
            markTerminal(row, "response_mismatch")
            return ManualMemoryOutboxSyncOutcome.TERMINAL_FAILURE
        }

        databaseProvider.current().withTransaction {
            commitmentDao.markSynced(listOf(row.commitmentId))
            outboxDao.deleteByKey(userId = row.userId, clientMemoryId = row.clientMemoryId)
        }
        return ManualMemoryOutboxSyncOutcome.SYNCED
    }

    private suspend fun markRetryable(row: ManualMemoryOutboxEntity, reason: String) {
        outboxDao.markRetryableFailure(
            userId = row.userId,
            clientMemoryId = row.clientMemoryId,
            lastError = reason,
            updatedAt = Clock.System.now(),
        )
    }

    private suspend fun markTerminal(row: ManualMemoryOutboxEntity, reason: String) {
        outboxDao.markFailed(
            userId = row.userId,
            clientMemoryId = row.clientMemoryId,
            lastError = reason,
            updatedAt = Clock.System.now(),
        )
    }

    private fun ManualMemoryOutboxEntity.toRequest(): ManualMemoryCreateRequestDto =
        ManualMemoryCreateRequestDto(
            clientMemoryId = clientMemoryId,
            personId = personId,
            commitmentId = commitmentId,
            personDisplayName = personDisplayName,
            originChannel = originChannel,
            memoryKind = memoryKind,
            title = title,
            occurredAt = occurredAt,
            dueAt = dueAt,
            dueHint = dueHint,
        )

    private fun Int.isRetryableManualMemoryStatus(): Boolean =
        this == 401 || this == 408 || this == 429 || this in 500..599

    private companion object {
        private const val TAG = "ManualMemoryOutboxSync"
    }
}
