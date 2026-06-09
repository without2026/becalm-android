package com.becalm.android.ui.sources

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.analytics.NoopProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.entity.SourceConnectionEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.ErrorEnvelopeDto
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonActionRepository
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.ProcessingStatusMessages
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.ScheduleEventLinkRepository
import com.becalm.android.data.repository.SelfIdentityRepository
import com.becalm.android.data.repository.SourceConnectionRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.data.repository.SOURCE_CONNECTION_STATUS_NEEDS_REAUTH
import com.becalm.android.data.repository.SourceSyncJobPollResult
import com.becalm.android.data.repository.SourceSyncJobPoller
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.repository.UserCorrectionRepository
import com.becalm.android.data.repository.isSyncableBackendSourceConnectionStatus
import com.becalm.android.data.repository.toSnapshot
import com.becalm.android.worker.CalendarRelationRefresh
import com.becalm.android.worker.SourceRelationRefreshCoordinator
import com.becalm.android.worker.SourceRelationRefreshPlan
import com.becalm.android.worker.WorkScheduler
import com.squareup.moshi.Moshi
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import retrofit2.Response

/**
 * Production seam for per-source manual sync ownership.
 *
 * Backend-managed sources (Gmail / Outlook Mail / Google Calendar / Outlook Calendar)
 * must no longer route through on-device provider workers. Local sources (IMAP / voice)
 * still use [WorkScheduler].
 */
public interface SourceSyncPort {
    public suspend fun requestManualSync(sourceType: String): BecalmResult<Unit>
}

@Singleton
public class DefaultSourceSyncPort @Inject constructor(
    private val authRepository: AuthRepository,
    private val apiProvider: Provider<RailwayApi>,
    private val calendarEventRepository: CalendarEventRepository,
    private val commitmentRepository: CommitmentRepository,
    private val commitmentParticipantRepository: CommitmentParticipantRepository,
    private val rawIngestionRepository: RawIngestionRepository,
    private val scheduleEventLinkRepository: ScheduleEventLinkRepository? = null,
    private val personActionRepository: PersonActionRepository,
    private val sourceEventParticipantRepository: SourceEventParticipantRepository,
    private val sourceConnectionRepository: SourceConnectionRepository,
    private val syncCursorStore: SyncCursorStore,
    private val selfIdentityRepository: SelfIdentityRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val userCorrectionRepository: UserCorrectionRepository,
    private val workScheduler: WorkScheduler,
    private val logger: Logger,
    moshi: Moshi,
    private val productAnalytics: ProductAnalyticsClient = NoopProductAnalyticsClient(),
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SourceSyncPort {

    private val api: RailwayApi
        get() = apiProvider.get()
    private val errorEnvelopeAdapter = moshi.adapter(ErrorEnvelopeDto::class.java)

    override suspend fun requestManualSync(sourceType: String): BecalmResult<Unit> = withContext(ioDispatcher) {
        trackSourceSync(
            eventName = ProductAnalyticsEvents.SOURCE_SYNC_STARTED,
            sourceType = sourceType,
            result = "started",
        )
        val result = when (sourceType) {
            SourceType.GMAIL,
            SourceType.OUTLOOK_MAIL,
            -> syncBackendManagedMail(sourceType)

            SourceType.GOOGLE_CALENDAR,
            SourceType.OUTLOOK_CALENDAR,
            -> syncBackendManagedCalendar(sourceType)

            SourceType.VOICE,
            SourceType.CALL_RECORDING,
            SourceType.MEETING,
            -> {
                workScheduler.enqueueExpedited(sourceType)
                logger.d(TAG, "manual sync delegated to MediaStore worker sourceType=$sourceType")
                BecalmResult.Success(Unit)
            }

            else -> {
                workScheduler.enqueueExpedited(sourceType)
                logger.d(TAG, "manual sync delegated to local worker sourceType=$sourceType")
                BecalmResult.Success(Unit)
            }
        }
        when (result) {
            is BecalmResult.Success -> trackSourceSync(
                eventName = ProductAnalyticsEvents.SOURCE_SYNC_COMPLETED,
                sourceType = sourceType,
                result = if (sourceType.syncOwner() == "local") "enqueued" else "success",
            )
            is BecalmResult.Failure -> trackSourceSync(
                eventName = ProductAnalyticsEvents.SOURCE_SYNC_FAILED,
                sourceType = sourceType,
                result = result.error.analyticsReason(),
                retryable = result.error.isRetryableForSync(),
            )
        }
        result
    }

    private suspend fun syncBackendManagedMail(sourceType: String): BecalmResult<Unit> {
        val userId = authRepository.currentSession()?.userId ?: return onBackendSyncFailure(
            sourceType = sourceType,
            error = BecalmError.Unauthorized,
        )
        when (val connectionSync = syncBackendManagedConnections(userId, sourceType)) {
            is BecalmResult.Failure -> return onBackendSyncFailure(sourceType, connectionSync.error)
            is BecalmResult.Success -> if (!connectionSync.value) return BecalmResult.Success(Unit)
        }
        processingStatusRepository.recordUploading(sourceType)
        when (
            val refresh = relationRefreshCoordinator().refresh(
                userId = userId,
                plan = SourceRelationRefreshPlan(
                    sourceType = sourceType,
                    rawSourceType = sourceType,
                    resetMirrorCursorBeforeRefresh = true,
                ),
            )
        ) {
            is BecalmResult.Success -> {
                processingStatusRepository.recordSynced(sourceType, refresh.value.changedCount)
                logger.d(
                    TAG,
                    "relation refresh after backend mail sync sourceType=$sourceType changed=${refresh.value.changedCount}",
                )
            }
            is BecalmResult.Failure -> return onBackendSyncFailure(sourceType, refresh.error)
        }
        logger.d(TAG, "manual sync delegated to backend mail sourceType=$sourceType")
        return finalizeBackendSyncSuccess(userId, sourceType)
    }

    private suspend fun syncBackendManagedCalendar(sourceType: String): BecalmResult<Unit> {
        val userId = authRepository.currentSession()?.userId ?: return onBackendSyncFailure(
            sourceType = sourceType,
            error = BecalmError.Unauthorized,
        )
        when (val connectionSync = syncBackendManagedConnections(userId, sourceType)) {
            is BecalmResult.Failure -> return onBackendSyncFailure(sourceType, connectionSync.error)
            is BecalmResult.Success -> if (!connectionSync.value) return BecalmResult.Success(Unit)
        }
        processingStatusRepository.recordUploading(sourceType)
        when (
            val refresh = relationRefreshCoordinator().refresh(
                userId = userId,
                plan = SourceRelationRefreshPlan(
                    sourceType = sourceType,
                    calendarRefresh = CalendarRelationRefresh(),
                    resetMirrorCursorBeforeRefresh = true,
                ),
            )
        ) {
            is BecalmResult.Failure -> return onBackendSyncFailure(sourceType, refresh.error)
            is BecalmResult.Success -> {
                processingStatusRepository.recordSynced(sourceType, refresh.value.changedCount)
                logger.d(
                    TAG,
                    "relation refresh after backend calendar sync sourceType=$sourceType changed=${refresh.value.changedCount}",
                )
            }
        }
        logger.d(TAG, "manual sync delegated to backend calendar sourceType=$sourceType")
        return finalizeBackendSyncSuccess(userId, sourceType)
    }

    private suspend fun syncBackendManagedConnections(
        userId: String,
        sourceType: String,
    ): BecalmResult<Boolean> {
        sourceStatusRepository.recordSyncStart(sourceType)
        processingStatusRepository.recordScanning(sourceType)
        val connections = when (val refresh = sourceConnectionRepository.refresh(userId)) {
            is BecalmResult.Success -> {
                val backendConnections = refresh.value.backendConnectionsFor(sourceType)
                if (backendConnections.any { it.status == SOURCE_CONNECTION_STATUS_NEEDS_REAUTH }) {
                    return BecalmResult.Failure(
                        BecalmError.Validation("source_connection", SOURCE_CONNECTION_STATUS_NEEDS_REAUTH),
                    )
                }
                backendConnections.syncableBackendConnections()
            }
            is BecalmResult.Failure -> return BecalmResult.Failure(refresh.error)
        }
        if (connections.isEmpty()) {
            return BecalmResult.Failure(BecalmError.NotFound("source_connection/$sourceType"))
        }
        val jobs = mutableListOf<Pair<SourceConnectionEntity, com.becalm.android.data.remote.dto.SourceSyncJobResponse>>()
        for (connection in connections) {
            val response = api.syncSourceConnection(connection.id)
            if (!response.isSuccessful) {
                val error = response.toSyncError("source_connection")
                if (error.isSourceReconnectRequired()) {
                    refreshSourceRecoveryMirrors(userId, sourceType)
                }
                return BecalmResult.Failure(error)
            }
            val body = response.body()
                ?: return BecalmResult.Failure(BecalmError.Unknown(IllegalStateException("null body")))
            jobs += connection to body
        }
        for ((connection, body) in jobs) {
            when (
                val pollResult = SourceSyncJobPoller(
                    api = api,
                    logger = logger,
                ).awaitTerminal(sourceType, body.toSnapshot())
            ) {
                is SourceSyncJobPollResult.Completed -> logger.d(
                    TAG,
                    "backend connection sync completed sourceType=$sourceType connectionId=${connection.id} synced=${pollResult.synced}",
                )
                is SourceSyncJobPollResult.Pending -> {
                    if (pollResult.reasonCode == "provider_has_more_pages") {
                        when (
                            val refresh = relationRefreshCoordinator().refresh(
                                userId = userId,
                                plan = SourceRelationRefreshPlan(
                                    sourceType = sourceType,
                                    rawSourceType = sourceType,
                                    resetMirrorCursorBeforeRefresh = true,
                                ),
                            )
                        ) {
                            is BecalmResult.Failure -> return BecalmResult.Failure(refresh.error)
                            is BecalmResult.Success -> logger.d(
                                TAG,
                                "partial backend sync refresh sourceType=$sourceType connectionId=${connection.id} changed=${refresh.value.changedCount}",
                            )
                        }
                    }
                    processingStatusRepository.recordScanning(sourceType, pollResult.processingMessageCode())
                    workScheduler.enqueueSourceRelationRefresh(
                        sourceType,
                        initialDelaySeconds = (pollResult.retryAfterSeconds ?: PENDING_BACKEND_REFRESH_DELAY_SECONDS)
                            .coerceAtLeast(PENDING_BACKEND_REFRESH_DELAY_SECONDS),
                        resetBeforeRefresh = true,
                    )
                    logger.d(
                        TAG,
                        "backend connection sync pending sourceType=$sourceType connectionId=${connection.id} message=${pollResult.message}",
                    )
                    return BecalmResult.Success(false)
                }
                is SourceSyncJobPollResult.Failed -> return BecalmResult.Failure(pollResult.toSyncError())
            }
        }
        return BecalmResult.Success(true)
    }

    private fun relationRefreshCoordinator(): SourceRelationRefreshCoordinator =
        SourceRelationRefreshCoordinator(
            rawIngestionRepository = rawIngestionRepository,
            calendarEventRepository = calendarEventRepository,
            commitmentRepository = commitmentRepository,
            sourceEventParticipantRepository = sourceEventParticipantRepository,
            commitmentParticipantRepository = commitmentParticipantRepository,
            scheduleEventLinkRepository = scheduleEventLinkRepository,
            personActionRepository = personActionRepository,
            userCorrectionRepository = userCorrectionRepository,
            syncCursorStore = syncCursorStore,
            workScheduler = workScheduler,
            logger = logger,
        )

    private suspend fun finalizeBackendSyncSuccess(userId: String, sourceType: String): BecalmResult<Unit> {
        refreshIdentityMirrorsAfterBackendSync(userId, sourceType)
        val completedAt = Clock.System.now()
        return when (val refresh = sourceStatusRepository.refreshFromServer()) {
            is BecalmResult.Success -> {
                sourceStatusRepository.recordSyncSuccess(sourceType, completedAt)
                refresh
            }
            is BecalmResult.Failure -> {
                logger.w(TAG, "source_status refresh failed after backend sync sourceType=$sourceType")
                sourceStatusRepository.recordSyncSuccess(sourceType, completedAt)
                BecalmResult.Success(Unit)
            }
        }
    }

    private suspend fun refreshIdentityMirrorsAfterBackendSync(userId: String, sourceType: String) {
        if (sourceConnectionRepository.refresh(userId) is BecalmResult.Failure) {
            logger.w(TAG, "source_connections refresh failed after backend sync sourceType=$sourceType")
        }
        if (selfIdentityRepository.refresh(userId) is BecalmResult.Failure) {
            logger.w(TAG, "self_identity_anchors refresh failed after backend sync sourceType=$sourceType")
        }
    }

    private suspend fun refreshSourceRecoveryMirrors(userId: String, sourceType: String) {
        if (sourceConnectionRepository.refresh(userId) is BecalmResult.Failure) {
            logger.w(TAG, "source_connections refresh failed after backend reconnect response sourceType=$sourceType")
        }
        if (sourceStatusRepository.refreshFromServer() is BecalmResult.Failure) {
            logger.w(TAG, "source_status refresh failed after backend reconnect response sourceType=$sourceType")
        }
    }

    private suspend fun onBackendSyncFailure(
        sourceType: String,
        error: BecalmError,
    ): BecalmResult<Unit> {
        val message = when (error) {
            is BecalmError.Unauthorized -> "unauthorized"
            is BecalmError.RateLimited -> "rate_limited"
            is BecalmError.Validation -> error.message
            is BecalmError.NotFound -> error.resource
            is BecalmError.ServerError -> error.body ?: "server_error"
            is BecalmError.Network -> error.message.ifBlank { "network_error" }
            is BecalmError.Io -> error.message
            is BecalmError.Permission -> error.permission
            is BecalmError.Cancelled -> "cancelled"
            is BecalmError.ExtractorUnavailable -> error.reason
            is BecalmError.Unknown -> error.throwable.message ?: "unknown"
        }
        sourceStatusRepository.recordSyncError(sourceType, message, Clock.System.now())
        processingStatusRepository.recordError(sourceType, message)
        logger.w(TAG, "manual sync failed sourceType=$sourceType error=${error::class.simpleName}")
        return BecalmResult.Failure(error)
    }

    private fun <T> Response<T>.toSyncError(resource: String = "mail_source"): BecalmError {
        val rawBody = runCatching { errorBody()?.string().orEmpty() }.getOrDefault("")
        val envelope = rawBody.takeIf { it.isNotBlank() }?.let { body ->
            runCatching { errorEnvelopeAdapter.fromJson(body) }.getOrNull()
        }
        return when (code()) {
            401 -> BecalmError.Unauthorized
            404 -> BecalmError.NotFound(resource)
            409 -> if (envelope.isReconnectSourceEnvelope()) {
                BecalmError.Validation("source_connection", SOURCE_CONNECTION_STATUS_NEEDS_REAUTH)
            } else {
                BecalmError.Network(409, envelope.safeSyncMessage(rawBody, message()))
            }
            422 -> BecalmError.Validation(null, envelope.safeSyncMessage(rawBody, message()))
            429 -> BecalmError.RateLimited(headers().get("Retry-After")?.toLongOrNull() ?: envelope?.retryAfterSeconds)
            in 500..599 -> BecalmError.ServerError(code(), rawBody.ifBlank { envelope?.error })
            else -> BecalmError.Network(code(), envelope.safeSyncMessage(rawBody, message()))
        }
    }

    private fun trackSourceSync(
        eventName: String,
        sourceType: String,
        result: String,
        retryable: Boolean? = null,
    ) {
        val properties = buildMap<String, Any> {
            put("source_type", sourceType)
            put("owner", sourceType.syncOwner())
            put("provider_family", sourceType.providerFamily())
            put("result", result)
            retryable?.let { put("retryable", it) }
        }
        productAnalytics.track(
            ProductAnalyticsEvent(
                eventId = UUID.randomUUID().toString(),
                eventName = eventName,
                occurredAt = Clock.System.now(),
                properties = properties,
            ),
        )
    }

    private fun String.syncOwner(): String =
        when (this) {
            SourceType.GMAIL,
            SourceType.OUTLOOK_MAIL,
            SourceType.GOOGLE_CALENDAR,
            SourceType.OUTLOOK_CALENDAR,
            -> "backend"
            else -> "local"
        }

    private fun String.providerFamily(): String =
        when (this) {
            SourceType.GMAIL,
            SourceType.OUTLOOK_MAIL,
            SourceType.NAVER_IMAP,
            SourceType.DAUM_IMAP,
            -> "mail"
            SourceType.GOOGLE_CALENDAR,
            SourceType.OUTLOOK_CALENDAR,
            -> "calendar"
            SourceType.VOICE,
            SourceType.MEETING,
            SourceType.CALL_RECORDING,
            -> "audio"
            else -> "other"
        }

    private fun BecalmError.analyticsReason(): String =
        when (this) {
            is BecalmError.Unauthorized -> "unauthorized"
            is BecalmError.RateLimited -> "rate_limited"
            is BecalmError.Validation -> "validation"
            is BecalmError.NotFound -> "not_found"
            is BecalmError.ServerError -> "server_error"
            is BecalmError.Network -> "network_error"
            is BecalmError.Io -> "io_error"
            is BecalmError.Permission -> "permission_denied"
            is BecalmError.Cancelled -> "cancelled"
            is BecalmError.ExtractorUnavailable -> "extractor_unavailable"
            is BecalmError.Unknown -> "unknown"
        }

    private fun BecalmError.isRetryableForSync(): Boolean =
        when (this) {
            is BecalmError.RateLimited,
            is BecalmError.ServerError,
            is BecalmError.Network,
            is BecalmError.Io,
            is BecalmError.ExtractorUnavailable,
            is BecalmError.Unknown,
            -> true
            is BecalmError.Unauthorized,
            is BecalmError.Validation,
            is BecalmError.NotFound,
            is BecalmError.Permission,
            is BecalmError.Cancelled,
            -> false
        }

    private fun SourceSyncJobPollResult.Failed.toSyncError(): BecalmError =
        if (retryable) {
            BecalmError.ServerError(503, message)
        } else {
            BecalmError.Validation(null, message)
        }

    private fun BecalmError.isSourceReconnectRequired(): Boolean =
        this is BecalmError.Validation && message == SOURCE_CONNECTION_STATUS_NEEDS_REAUTH

    private fun ErrorEnvelopeDto?.isReconnectSourceEnvelope(): Boolean =
        this?.clientAction == "reconnect_source" ||
            this?.error in SOURCE_RECONNECT_ERROR_CODES

    private fun ErrorEnvelopeDto?.safeSyncMessage(rawBody: String, fallback: String): String =
        when {
            this?.clientAction == "reconnect_source" -> SOURCE_CONNECTION_STATUS_NEEDS_REAUTH
            this?.error in SOURCE_RECONNECT_ERROR_CODES -> SOURCE_CONNECTION_STATUS_NEEDS_REAUTH
            !this?.clientAction.isNullOrBlank() -> this?.clientAction.orEmpty()
            !this?.error.isNullOrBlank() -> this?.error.orEmpty()
            rawBody.isNotBlank() -> rawBody
            else -> fallback
        }

    private fun SourceSyncJobPollResult.Pending.processingMessageCode(): String? =
        reasonCode.sourceSyncProcessingMessageCode()

    private fun List<SourceConnectionEntity>.backendConnectionsFor(sourceType: String): List<SourceConnectionEntity> =
        filter { connection -> connection.toBackendSourceType() == sourceType }

    private fun List<SourceConnectionEntity>.syncableBackendConnections(): List<SourceConnectionEntity> =
        filter { connection -> isSyncableBackendSourceConnectionStatus(connection.status) }

    private fun SourceConnectionEntity.toBackendSourceType(): String? =
        when {
            provider == "google" && capability == "mail" -> SourceType.GMAIL
            provider == "outlook" && capability == "mail" -> SourceType.OUTLOOK_MAIL
            provider == "google" && capability == "calendar" -> SourceType.GOOGLE_CALENDAR
            provider == "outlook" && capability == "calendar" -> SourceType.OUTLOOK_CALENDAR
            else -> null
        }

    private fun String?.sourceSyncProcessingMessageCode(): String? =
        when (this) {
            "backpressure_delayed" -> ProcessingStatusMessages.SOURCE_SYNC_BACKPRESSURE_DELAYED
            "provider_has_more_pages" -> ProcessingStatusMessages.SOURCE_SYNC_IMPORTING_MORE_PAGES
            "llm_rate_limited_retrying" -> ProcessingStatusMessages.LLM_RATE_LIMITED_RETRYING
            "llm_processing_retrying" -> ProcessingStatusMessages.LLM_PROCESSING_RETRYING
            "llm_processing_failed" -> ProcessingStatusMessages.LLM_PROCESSING_FAILED
            else -> null
        }

    private companion object {
        private const val TAG = "SourceSyncPort"
        private const val PENDING_BACKEND_REFRESH_DELAY_SECONDS: Long = 45L
        private val SOURCE_RECONNECT_ERROR_CODES = setOf(
            "source_connection_disconnected",
            "source_connection_needs_reauth",
            "provider_needs_reauth",
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
public abstract class SourceSyncModule {

    @Binds
    @Singleton
    public abstract fun bindSourceSyncPort(
        impl: DefaultSourceSyncPort,
    ): SourceSyncPort
}
