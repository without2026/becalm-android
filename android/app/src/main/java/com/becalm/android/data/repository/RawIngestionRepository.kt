package com.becalm.android.data.repository

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.result.daoOp
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.BatchUploadRequest
import com.becalm.android.data.remote.dto.BatchUploadResponse
import com.becalm.android.data.remote.dto.RawIngestionEventDto
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.dto.SourceEventParticipantInputDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Response
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

// ─── Interface ────────────────────────────────────────────────────────────────

/**
 * Local write/read and remote sync operations for raw ingestion events.
 *
 * Local events are always written to Room first (local-first model). The sync
 * queue ([findPendingSync] / [markSynced] / [markFailed]) is consumed by
 * SP-29 UploadWorker. [uploadBatch] performs a single idempotent HTTP call.
 */
public interface RawIngestionRepository {

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Inserts a single event locally, generating a UUID v4 [RawIngestionEventEntity.clientEventId]
     * if the caller left it blank (SYNC-003).
     *
     * @return [BecalmResult.Success] with the entity's [RawIngestionEventEntity.id];
     *   if the event already exists (by userId + clientEventId) the existing id is returned
     *   without a duplicate insert (ING-013).
     */
    public suspend fun insertLocal(event: RawIngestionEventEntity): BecalmResult<String>

    /**
     * Inserts a batch of events in a single Room transaction. Each item receives the same
     * UUID-generation and dedup guarantees as [insertLocal].
     *
     * @return [BecalmResult.Success] with the list of ids parallel to [events].
     */
    public suspend fun insertLocalBatch(events: List<RawIngestionEventEntity>): BecalmResult<List<String>>

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Returns distinct non-null person refs recently seen in raw events or commitments.
     */
    public suspend fun findDistinctPersonRefsForUser(userId: String, limit: Int): List<String>

    /**
     * Emits a live list of the most recent events for a specific [counterpartyRef] owned by [userId],
     * newest-first.
     *
     * @param limit Maximum list size per emission.
     */
    public fun observeForPerson(userId: String, counterpartyRef: String, limit: Int = 100): Flow<List<RawIngestionEventEntity>>

    /**
     * Emits a live list of the most recent events for a specific [sourceType] owned by [userId],
     * newest-first.
     *
     * @param limit Maximum list size per emission.
     */
    public fun observeForSourceType(userId: String, sourceType: String, limit: Int = 100): Flow<List<RawIngestionEventEntity>>

    /**
     * Emits the count of events for [userId] whose source is in [sourceTypes] and whose
     * timestamp is on or after [since].
     *
     * Used by cold-sync Stage 2 progress to project real local row counts from Room.
     */
    public fun observeCountForSourceTypesSince(
        userId: String,
        sourceTypes: List<String>,
        since: Instant,
    ): Flow<Int>

    /**
     * Returns the event matching [userId] + [clientEventId], or null if it does not exist locally.
     */
    public suspend fun findByClientEventId(userId: String, clientEventId: String): RawIngestionEventEntity?

    /**
     * Returns the event with the given primary-key [id] scoped to [userId], or null when
     * no row exists for that user.
     *
     * Although the primary key is globally unique, callers MUST pass the current user's
     * [userId] so that the DAO can reject cross-user reads: in multi-account or stale
     * back-stack scenarios one user's event UUID can end up in another user's navigation
     * stack, and we must never return another user's row.
     *
     * @param id The [RawIngestionEventEntity.id] UUID to look up.
     * @param userId The Supabase auth.users UUID of the requesting user.
     * @return The matching entity owned by [userId], or null.
     */
    public suspend fun findById(id: String, userId: String): RawIngestionEventEntity?

    // ── Sync queue ────────────────────────────────────────────────────────────

    /**
     * Returns up to [limit] events in "pending" sync state, ordered oldest-first.
     * Consumed by SP-29 UploadWorker.
     */
    public suspend fun findPendingSync(userId: String, limit: Int): List<RawIngestionEventEntity>

    /**
     * Marks the given event [ids] as "synced" in a single UPDATE.
     *
     * @return [BecalmResult.Success(Unit)] on success.
     */
    public suspend fun markSynced(ids: List<String>): BecalmResult<Unit>

    /**
     * Records a failed upload attempt for event [id]: sets status to "failed",
     * increments retry_count by 1, and records [lastAttemptAt].
     *
     * @return [BecalmResult.Success(Unit)] on success.
     */
    public suspend fun markFailed(id: String, lastAttemptAt: Instant): BecalmResult<Unit>

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Converts [events] to DTOs and POSTs them to Railway via an idempotent batch endpoint.
     * HTTP errors are mapped to typed [BecalmError] variants; transport retries are
     * delegated to OkHttp's RetryInterceptor (SP-29 handles worker-level backoff).
     *
     * @return [BecalmResult.Success] with the server's [BatchUploadResponse] on HTTP 200.
     */
    public suspend fun uploadBatch(events: List<RawIngestionEventEntity>): BecalmResult<BatchUploadResponse>

    /**
     * Pulls backend-persisted raw events into Room.
     *
     * This is required for backend-managed sources where Railway writes raw rows directly
     * (for example Gmail / Outlook Mail OAuth sync) rather than receiving rows from Android.
     */
    public suspend fun refreshSince(
        userId: String,
        sourceType: String? = null,
        since: Instant? = null,
    ): BecalmResult<RefreshStats>

    /** Aggregated outcome of a single raw-event refresh pass. */
    public data class RefreshStats(
        val fetched: Int,
        val upserted: Int,
        val hasMore: Boolean,
        val nextCursor: String?,
    )

    // ── PIPA consent release / park ───────────────────────────────────────────

    /**
     * Atomically transitions all voice events for [userId] from "awaiting_consent" to
     * "pending" and returns the exact list of released IDs.
     *
     * The caller needs to enqueue [com.becalm.android.worker.VoiceUploadWorker] for
     * precisely the released rows — no more, no less (finding #2 fix).
     *
     * @return [BecalmResult.Success] with the released IDs (empty list if none were waiting).
     *
     * Spec refs: VOI-004, ONB-PIPA invariant.
     */
    public suspend fun releaseAwaitingConsentVoiceAndReturnIds(userId: String): BecalmResult<List<String>>

    /**
     * Returns the IDs of all voice events for [userId] that are in a cancellable state
     * ("pending" or "awaiting_consent"), then parks them all as "awaiting_consent".
     *
     * Called when the user withdraws PIPA consent so that the caller can cancel the
     * corresponding WorkManager unique-work entries by ID before any queued job proceeds
     * past the consent check (finding #1 fix).
     *
     * @return [BecalmResult.Success] with the IDs that were parked (empty when none).
     *
     * Spec refs: VOI-004.
     */
    public suspend fun parkAndCancelPendingVoice(userId: String): BecalmResult<List<String>>

    // ── Clear ─────────────────────────────────────────────────────────────────

    /**
     * Hard-deletes all local events for [userId]. Called on sign-out for scoped PIPA wipe;
     * full database wipe uses `Database.clearAllTables()` in [AuthRepository].
     *
     * @return [BecalmResult.Success] with the number of rows deleted.
     */
    public suspend fun deleteAllForUser(userId: String): BecalmResult<Int>
}

// ─── Implementation ───────────────────────────────────────────────────────────

private const val TAG = "RawIngestionRepository"

/**
 * Production implementation of [RawIngestionRepository].
 *
 * All DAO calls are already dispatched on Room's internal IO executor; no
 * Dispatcher switching is performed here.
 */
@Singleton
public class RawIngestionRepositoryImpl @Inject constructor(
    private val dao: RawIngestionEventDao,
    private val apiProvider: Provider<RailwayApi>,
    private val emailBodyRepositoryProvider: Provider<EmailBodyRepository>,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RawIngestionRepository {

    private val api: RailwayApi
        get() = apiProvider.get()

    public constructor(
        dao: RawIngestionEventDao,
        api: RailwayApi,
        logger: Logger,
    ) : this(
        dao = dao,
        apiProvider = Provider { api },
        emailBodyRepositoryProvider = Provider { NoopEmailBodyRepository },
        logger = logger,
    )

    // ── Write ─────────────────────────────────────────────────────────────────

    override suspend fun insertLocal(event: RawIngestionEventEntity): BecalmResult<String> {
        val resolved = event.ensureClientEventId()
        // ING-013: polite read-before-write to avoid triggering the UNIQUE constraint
        val existing = dao.findByClientEventId(resolved.userId, resolved.clientEventId)
        if (existing != null) {
            logger.d(TAG, "insertLocal dedup hit for id=${existing.id}")
            return BecalmResult.Success(existing.id)
        }
        return logger.daoOp(TAG, "insert failed") {
            dao.insert(resolved)
            logger.d(TAG, "insertLocal ok id=${resolved.id}")
            resolved.id
        }
    }

    override suspend fun insertLocalBatch(events: List<RawIngestionEventEntity>): BecalmResult<List<String>> {
        if (events.isEmpty()) return BecalmResult.Success(emptyList())
        val resolved = events.map { it.ensureClientEventId() }
        return logger.daoOp(TAG, "batch insert failed") {
            val idsByKey = mutableMapOf<Pair<String, String>, String>()
            val ids = resolved.map { event ->
                val key = event.userId to event.clientEventId
                idsByKey[key] ?: insertOrFindExisting(event).also { idsByKey[key] = it }
            }
            logger.d(TAG, "insertLocalBatch ok count=${resolved.size}")
            ids
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    override suspend fun findDistinctPersonRefsForUser(userId: String, limit: Int): List<String> =
        dao.findDistinctPersonRefsForUser(userId, limit)

    override fun observeForPerson(
        userId: String,
        counterpartyRef: String,
        limit: Int,
    ): Flow<List<RawIngestionEventEntity>> =
        dao.observeRecentForPerson(userId, counterpartyRef, limit)

    override fun observeForSourceType(
        userId: String,
        sourceType: String,
        limit: Int,
    ): Flow<List<RawIngestionEventEntity>> =
        dao.observeRecentForSourceType(userId, sourceType, limit)

    override fun observeCountForSourceTypesSince(
        userId: String,
        sourceTypes: List<String>,
        since: Instant,
    ): Flow<Int> =
        dao.observeCountForSourceTypesSince(userId, sourceTypes, since)

    override suspend fun findByClientEventId(
        userId: String,
        clientEventId: String,
    ): RawIngestionEventEntity? =
        dao.findByClientEventId(userId, clientEventId)

    override suspend fun findById(id: String, userId: String): RawIngestionEventEntity? =
        dao.findById(id = id, userId = userId)

    // ── Sync queue ────────────────────────────────────────────────────────────

    override suspend fun findPendingSync(
        userId: String,
        limit: Int,
    ): List<RawIngestionEventEntity> =
        dao.findPendingForUpload(userId, limit)

    override suspend fun markSynced(ids: List<String>): BecalmResult<Unit> {
        if (ids.isEmpty()) return BecalmResult.Success(Unit)
        return logger.daoOp(TAG, "markSynced failed") {
            dao.markSynced(ids)
            logger.d(TAG, "markSynced count=${ids.size}")
        }
    }

    override suspend fun markFailed(id: String, lastAttemptAt: Instant): BecalmResult<Unit> =
        logger.daoOp(TAG, "markFailed failed") {
            dao.markFailed(id = id, retryIncrement = 1, now = lastAttemptAt)
            logger.d(TAG, "markFailed id=$id")
        }

    // ── Upload ────────────────────────────────────────────────────────────────

    override suspend fun uploadBatch(events: List<RawIngestionEventEntity>): BecalmResult<BatchUploadResponse> {
        val dtos = events.map { event ->
            val extractionContext = event.extractionContext()
            event.toDto(
                emailBodyPlain = extractionContext.emailBodyPlain,
                participants = extractionContext.participants,
            )
        }
        val request = BatchUploadRequest(events = dtos)
        return try {
            val response = api.batchUploadRawEvents(request = request)
            logger.d(TAG, "uploadBatch http=${response.code()} count=${events.size}")
            response.toBecalmResult()
        } catch (e: IOException) {
            logger.e(TAG, "uploadBatch network error count=${events.size}", e)
            BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
        } catch (e: Exception) {
            logger.e(TAG, "uploadBatch unexpected error count=${events.size}", e)
            BecalmResult.Failure(BecalmError.Unknown(e))
        }
    }

    override suspend fun refreshSince(
        userId: String,
        sourceType: String?,
        since: Instant?,
    ): BecalmResult<RawIngestionRepository.RefreshStats> = withContext(ioDispatcher) {
        var cursor: String? = null
        var totalFetched = 0
        var totalUpserted = 0
        var lastHasMore = false
        var lastCursor: String? = null

        repeat(REFRESH_PAGE_CAP) { pageIndex ->
            if (pageIndex > 0 && !lastHasMore) return@repeat

            val response = try {
                api.getRawIngestionEvents(
                    cursor = cursor,
                    limit = PAGE_LIMIT,
                    since = since?.toString(),
                    sourceType = sourceType,
                )
            } catch (e: IOException) {
                logger.e(TAG, "refreshSince network error on page $pageIndex", e)
                return@withContext BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
            } catch (e: Exception) {
                logger.e(TAG, "refreshSince unexpected error on page $pageIndex", e)
                return@withContext BecalmResult.Failure(BecalmError.Unknown(e))
            }

            if (!response.isSuccessful) {
                logger.w(TAG, "refreshSince HTTP ${response.code()} on page $pageIndex")
                return@withContext BecalmResult.Failure(response.toRefreshError())
            }

            val body = response.body()
                ?: return@withContext BecalmResult.Failure(
                    BecalmError.Unknown(IllegalStateException("null body on page $pageIndex")),
                )
            val entities = body.data.map { it.toEntity(userId) }
            dao.upsertSyncedFromServer(entities)

            totalFetched += body.data.size
            totalUpserted += entities.size
            lastHasMore = body.hasMore
            lastCursor = body.cursor
            cursor = body.cursor
        }

        logger.d(TAG, "refreshSince done sourceType=$sourceType fetched=$totalFetched upserted=$totalUpserted")
        BecalmResult.Success(
            RawIngestionRepository.RefreshStats(
                fetched = totalFetched,
                upserted = totalUpserted,
                hasMore = lastHasMore,
                nextCursor = lastCursor,
            ),
        )
    }

    // ── PIPA consent release / park ───────────────────────────────────────────

    override suspend fun releaseAwaitingConsentVoiceAndReturnIds(userId: String): BecalmResult<List<String>> =
        logger.daoOp(TAG, "releaseAwaitingConsentVoiceAndReturnIds failed") {
            val ids = dao.releaseAwaitingConsentVoiceAndReturnIds(userId)
            logger.d(TAG, "releaseAwaitingConsentVoiceAndReturnIds userId_hash=${userId.hashCode()} count=${ids.size}")
            ids
        }

    override suspend fun parkAndCancelPendingVoice(userId: String): BecalmResult<List<String>> =
        logger.daoOp(TAG, "parkAndCancelPendingVoice failed") {
            // Atomic: SELECT ids + UPDATE WHERE id IN (:ids) run inside a single @Transaction.
            // Eliminates the race where a new 'pending' row inserted between the old two-call
            // SELECT/UPDATE would be parked but its ID never returned (finding #1 fix).
            val ids = dao.parkCancellablePendingVoiceAndReturnIds(
                userId = userId,
                now = System.currentTimeMillis(),
            )
            logger.d(TAG, "parkAndCancelPendingVoice userId_hash=${userId.hashCode()} count=${ids.size}")
            ids
        }

    // ── Clear ─────────────────────────────────────────────────────────────────

    override suspend fun deleteAllForUser(userId: String): BecalmResult<Int> =
        logger.daoOp(TAG, "deleteAllForUser failed") {
            val count = dao.deleteAllForUser(userId)
            logger.d(TAG, "deleteAllForUser count=$count")
            count
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns a copy of this entity with a UUID v4 clientEventId if the caller
     * left it blank — SYNC-003 requires a server-side idempotency key to be present.
     */
    private fun RawIngestionEventEntity.ensureClientEventId(): RawIngestionEventEntity =
        if (clientEventId.isBlank()) copy(clientEventId = UUID.randomUUID().toString()) else this

    private suspend fun insertOrFindExisting(event: RawIngestionEventEntity): String {
        val existing = dao.findByClientEventId(event.userId, event.clientEventId)
        if (existing != null) return existing.id

        val rowId = dao.insert(event)
        if (rowId != -1L) return event.id

        return dao.findByClientEventId(event.userId, event.clientEventId)?.id ?: event.id
    }

    private suspend fun RawIngestionEventEntity.extractionContext(): SourceExtractionContext {
        if (sourceType !in EMAIL_SOURCE_TYPES) {
            return SourceExtractionContext(
                emailBodyPlain = null,
                participants = counterpartyRef?.let {
                    listOf(counterpartyParticipant(it, evidenceSource = "metadata"))
                }.orEmpty(),
            )
        }
        val body = emailBodyRepositoryProvider.get().getByRawEventId(id)
            ?: return SourceExtractionContext(
                emailBodyPlain = null,
                participants = counterpartyRef?.let {
                    listOf(counterpartyParticipant(it, evidenceSource = "metadata"))
                }.orEmpty(),
            )
        val participants = emailParticipants(body.folder, body.fromAddress, body.toAddresses)
        if (body.parseFailed || body.groupEmail) {
            return SourceExtractionContext(emailBodyPlain = null, participants = participants)
        }
        return SourceExtractionContext(
            emailBodyPlain = body.bodyPlain?.takeIf { it.isNotBlank() },
            participants = participants,
        )
    }

    private fun RawIngestionEventEntity.toDto(
        emailBodyPlain: String?,
        participants: List<SourceEventParticipantInputDto>,
    ): RawIngestionEventDto {
        val emailHeaders = emailHeaderContext()
        return RawIngestionEventDto(
            id = id,
            clientEventId = clientEventId,
            userId = userId,
            sourceType = sourceType,
            sourceRef = sourceRef,
            messageIdHeader = emailHeaders?.messageIdHeader,
            inReplyToHeader = emailHeaders?.inReplyToHeader,
            referencesHeader = emailHeaders?.referencesHeader,
            counterpartyRef = counterpartyRef,
            participants = participants.takeIf { it.isNotEmpty() },
            eventTitle = eventTitle,
            eventSnippet = eventSnippet,
            durationSeconds = durationSeconds,
            location = location,
            // EMAIL-001 direction hint (`.spec/email-pipeline.spec.yml:15-18`) — INBOX|SENT for
            // email source_types, null elsewhere. Must be propagated so Railway can drive the
            // server-side counterparty_ref derivation from the same raw-event metadata the client saw.
            folder = folder,
            commitmentsExtractedCount = commitmentsExtractedCount,
            emailBodyPlain = emailBodyPlain,
            timestamp = timestamp,
        )
    }

    private data class SourceExtractionContext(
        val emailBodyPlain: String?,
        val participants: List<SourceEventParticipantInputDto>,
    )

    private data class EmailHeaderContext(
        val messageIdHeader: String?,
        val inReplyToHeader: String?,
        val referencesHeader: String?,
    )

    private fun RawIngestionEventEntity.emailHeaderContext(): EmailHeaderContext? {
        if (sourceType !in EMAIL_SOURCE_TYPES) return null
        val raw = sourceRef?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val json = JSONObject(raw)
            EmailHeaderContext(
                messageIdHeader = json.optNonBlankString("message_id"),
                inReplyToHeader = json.optNonBlankString("in_reply_to"),
                referencesHeader = json.optNonBlankString("references"),
            )
        }.getOrNull()
    }

    private fun emailParticipants(
        folder: String?,
        fromAddress: String?,
        toAddressesJson: String?,
    ): List<SourceEventParticipantInputDto> {
        val normalizedFolder = folder?.lowercase()
        return buildList {
            canonicalEmail(fromAddress)?.let { sender ->
                add(
                    emailParticipant(
                        role = "sender",
                        relationToUser = if (normalizedFolder == "sent") "self" else "counterparty",
                        email = sender,
                    ),
                )
            }
            parseEmailArray(toAddressesJson).forEach { recipient ->
                add(
                    emailParticipant(
                        role = "recipient",
                        relationToUser = if (normalizedFolder == "sent") "counterparty" else "self",
                        email = recipient,
                    ),
                )
            }
        }.distinctBy { "${it.role}:${it.relationToUser}:${it.email}" }
    }

    private fun parseEmailArray(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (index in 0 until array.length()) {
                    val candidate = array.optJSONObject(index)?.optString("email")
                        ?: array.optString(index)
                    canonicalEmail(candidate)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.optNonBlankString(name: String): String? =
        optString(name, "").takeIf { it.isNotBlank() }

    private fun emailParticipant(
        role: String,
        relationToUser: String,
        email: String,
    ): SourceEventParticipantInputDto =
        SourceEventParticipantInputDto(
            role = role,
            relationToUser = relationToUser,
            identityType = "email",
            rawValue = email,
            normalizedValue = email,
            email = email,
            evidence = email,
            confidence = 0.95,
            evidenceSource = "metadata",
        )

    private fun counterpartyParticipant(value: String, evidenceSource: String): SourceEventParticipantInputDto {
        val email = canonicalEmail(value)
        val phone = value.takeIf { it.startsWith("+") && it.drop(1).all(Char::isDigit) }
        return SourceEventParticipantInputDto(
            role = "counterparty",
            relationToUser = "counterparty",
            identityType = when {
                email != null -> "email"
                phone != null -> "phone"
                else -> "name"
            },
            rawValue = value,
            normalizedValue = email ?: phone ?: value.trim().lowercase(),
            displayName = if (email == null && phone == null) value else null,
            email = email,
            phone = phone,
            evidence = value,
            confidence = if (email != null || phone != null) 0.85 else 0.5,
            evidenceSource = evidenceSource,
        )
    }

    private fun canonicalEmail(value: String?): String? {
        val text = value?.trim()?.lowercase() ?: return null
        if (!text.contains("@")) return null
        return text
            .substringAfter("<", text)
            .substringBefore(">")
            .takeIf { it.contains("@") && it.contains(".") }
    }

    private fun RawIngestionEventDto.toEntity(userId: String): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = id ?: UUID.nameUUIDFromBytes("$userId:$sourceType:$clientEventId".toByteArray()).toString(),
            userId = userId,
            clientEventId = clientEventId,
            sourceType = sourceType,
            sourceRef = sourceRef,
            counterpartyRef = counterpartyRef,
            eventTitle = eventTitle,
            eventSnippet = eventSnippet,
            durationSeconds = durationSeconds,
            location = location,
            folder = folder,
            commitmentsExtractedCount = commitmentsExtractedCount ?: 0,
            timestamp = timestamp,
            syncStatus = "synced",
        )

    private fun <T> Response<T>.toRefreshError(): BecalmError = when (code()) {
        401 -> BecalmError.Unauthorized
        404 -> BecalmError.NotFound("raw_ingestion_events")
        422 -> BecalmError.Validation(null, message())
        429 -> BecalmError.RateLimited(headers()["Retry-After"]?.toLongOrNull())
        in 500..599 -> BecalmError.ServerError(code(), errorBody()?.string())
        else -> BecalmError.Network(code(), message())
    }

    /**
     * Maps a Retrofit [Response] to a [BecalmResult], applying the error semantics
     * documented in api-contract.yml for the ingestion batch endpoint.
     */
    private fun Response<BatchUploadResponse>.toBecalmResult(): BecalmResult<BatchUploadResponse> {
        val body = body()
        if (isSuccessful && body != null) return BecalmResult.Success(body)
        return when (code()) {
            401 -> BecalmResult.Failure(BecalmError.Unauthorized)
            413 -> BecalmResult.Failure(BecalmError.Validation(field = null, message = "batch too large"))
            422 -> {
                val msg = errorBody()?.string() ?: "validation error"
                BecalmResult.Failure(BecalmError.Validation(field = null, message = msg))
            }
            429 -> {
                val retryAfter = headers()["Retry-After"]?.toLongOrNull()
                BecalmResult.Failure(BecalmError.RateLimited(retryAfter))
            }
            in 500..599 -> {
                val errBody = errorBody()?.string()
                BecalmResult.Failure(BecalmError.ServerError(code(), errBody))
            }
            else -> {
                val msg = errorBody()?.string() ?: message()
                BecalmResult.Failure(BecalmError.Network(code(), msg ?: "unknown error"))
            }
        }
    }

    private companion object {
        private const val PAGE_LIMIT = 100
        private val EMAIL_SOURCE_TYPES = setOf(
            SourceType.GMAIL,
            SourceType.OUTLOOK_MAIL,
            SourceType.NAVER_IMAP,
            SourceType.DAUM_IMAP,
        )
    }
}

private object NoopEmailBodyRepository : EmailBodyRepository {
    override suspend fun insert(entity: com.becalm.android.data.local.db.entity.EmailBodyEntity) = Unit

    override suspend fun getByRawEventId(rawEventId: String): com.becalm.android.data.local.db.entity.EmailBodyEntity? = null

    override suspend fun findByProviderMessage(
        userId: String,
        sourceType: String,
        folder: String,
        providerMessageId: String,
    ): com.becalm.android.data.local.db.entity.EmailBodyEntity? = null

    override suspend fun markParseFailed(id: String) = Unit
}
