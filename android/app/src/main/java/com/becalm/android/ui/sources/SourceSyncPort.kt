package com.becalm.android.ui.sources

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.analytics.NoopProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.ScheduleEventLinkRepository
import com.becalm.android.data.repository.SelfIdentityRepository
import com.becalm.android.data.repository.SourceConnectionRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.CalendarRelationRefresh
import com.becalm.android.worker.SourceRelationRefreshCoordinator
import com.becalm.android.worker.SourceRelationRefreshPlan
import com.becalm.android.worker.WorkScheduler
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
    private val sourceEventParticipantRepository: SourceEventParticipantRepository,
    private val sourceConnectionRepository: SourceConnectionRepository,
    private val selfIdentityRepository: SelfIdentityRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val workScheduler: WorkScheduler,
    private val logger: Logger,
    private val productAnalytics: ProductAnalyticsClient = NoopProductAnalyticsClient(),
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SourceSyncPort {

    private val api: RailwayApi
        get() = apiProvider.get()

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
        sourceStatusRepository.recordSyncStart(sourceType)
        val response = api.syncMailSource(provider = sourceType)
        if (!response.isSuccessful) {
            return onBackendSyncFailure(sourceType, response.toSyncError())
        }
        when (
            val refresh = relationRefreshCoordinator().refresh(
                userId = userId,
                plan = SourceRelationRefreshPlan(
                    sourceType = sourceType,
                    rawSourceType = sourceType,
                ),
            )
        ) {
            is BecalmResult.Success -> logger.d(
                TAG,
                "relation refresh after backend mail sync sourceType=$sourceType changed=${refresh.value.changedCount}",
            )
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
        sourceStatusRepository.recordSyncStart(sourceType)
        when (val result = calendarEventRepository.triggerServerSync()) {
            is BecalmResult.Failure -> return onBackendSyncFailure(sourceType, result.error)
            is BecalmResult.Success -> {
                when (
                    val refresh = relationRefreshCoordinator().refresh(
                        userId = userId,
                        plan = SourceRelationRefreshPlan(
                            sourceType = sourceType,
                            calendarRefresh = CalendarRelationRefresh(),
                        ),
                    )
                ) {
                    is BecalmResult.Failure -> return onBackendSyncFailure(sourceType, refresh.error)
                    is BecalmResult.Success -> logger.d(
                        TAG,
                        "relation refresh after backend calendar sync sourceType=$sourceType changed=${refresh.value.changedCount}",
                    )
                }
                logger.d(TAG, "manual sync delegated to backend calendar sourceType=$sourceType")
                return finalizeBackendSyncSuccess(userId, sourceType)
            }
        }
    }

    private fun relationRefreshCoordinator(): SourceRelationRefreshCoordinator =
        SourceRelationRefreshCoordinator(
            rawIngestionRepository = rawIngestionRepository,
            calendarEventRepository = calendarEventRepository,
            commitmentRepository = commitmentRepository,
            sourceEventParticipantRepository = sourceEventParticipantRepository,
            commitmentParticipantRepository = commitmentParticipantRepository,
            scheduleEventLinkRepository = scheduleEventLinkRepository,
            workScheduler = workScheduler,
            logger = logger,
        )

    private suspend fun finalizeBackendSyncSuccess(userId: String, sourceType: String): BecalmResult<Unit> {
        refreshIdentityMirrorsAfterBackendSync(userId, sourceType)
        return when (val refresh = sourceStatusRepository.refreshFromServer()) {
            is BecalmResult.Success -> refresh
            is BecalmResult.Failure -> {
                logger.w(TAG, "source_status refresh failed after backend sync sourceType=$sourceType")
                sourceStatusRepository.recordSyncSuccess(sourceType, Clock.System.now())
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
        logger.w(TAG, "manual sync failed sourceType=$sourceType error=${error::class.simpleName}")
        return BecalmResult.Failure(error)
    }

    private fun <T> Response<T>.toSyncError(): BecalmError = when (code()) {
        401 -> BecalmError.Unauthorized
        404 -> BecalmError.NotFound("mail_source")
        422 -> BecalmError.Validation(null, message())
        429 -> BecalmError.RateLimited(headers().get("Retry-After")?.toLongOrNull())
        in 500..599 -> BecalmError.ServerError(code(), errorBody()?.string())
        else -> BecalmError.Network(code(), message())
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

    private companion object {
        private const val TAG = "SourceSyncPort"
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
