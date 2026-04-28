package com.becalm.android.worker.ingestion

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.imap.ImapMessage
import com.becalm.android.data.repository.EmailBodyRepository
import com.becalm.android.data.repository.RawIngestionRepository

internal data class ImapRawEventResolution(
    val rawEventIds: List<String?>,
    val insertedCount: Int,
)

internal suspend fun resolveImapRawEventIds(
    messages: List<ImapMessage>,
    userId: String,
    sourceType: String,
    provider: String,
    folderLabel: String,
    emailBodyRepository: EmailBodyRepository,
    rawIngestionRepository: RawIngestionRepository,
    toEntity: (ImapMessage) -> RawIngestionEventEntity,
): BecalmResult<ImapRawEventResolution> {
    val rawEventIds = MutableList<String?>(messages.size) { null }
    val pendingInserts = mutableListOf<Pair<Int, RawIngestionEventEntity>>()

    messages.forEachIndexed { index, message ->
        val existingBody = emailBodyRepository.findByProviderMessage(
            userId = userId,
            sourceType = sourceType,
            folder = folderLabel,
            providerMessageId = message.providerMessageId(),
        )
        if (existingBody != null) {
            rawEventIds[index] = existingBody.rawEventId
            return@forEachIndexed
        }

        val legacyRawEvent = rawIngestionRepository.findByClientEventId(
            userId = userId,
            clientEventId = message.legacyImapClientEventId(provider, folderLabel),
        )
        if (legacyRawEvent != null) {
            rawEventIds[index] = legacyRawEvent.id
            return@forEachIndexed
        }

        pendingInserts += index to toEntity(message)
    }

    if (pendingInserts.isNotEmpty()) {
        val insertResult = rawIngestionRepository.insertLocalBatch(pendingInserts.map { it.second })
        if (insertResult is BecalmResult.Failure) return insertResult

        (insertResult as BecalmResult.Success).value.forEachIndexed { offset, rawEventId ->
            rawEventIds[pendingInserts[offset].first] = rawEventId
        }
    }

    return BecalmResult.Success(
        ImapRawEventResolution(
            rawEventIds = rawEventIds,
            insertedCount = pendingInserts.size,
        ),
    )
}
