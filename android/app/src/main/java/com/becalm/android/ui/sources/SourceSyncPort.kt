package com.becalm.android.ui.sources

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.WorkScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
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
    private val sourceStatusRepository: SourceStatusRepository,
    private val workScheduler: WorkScheduler,
    private val logger: Logger,
) : SourceSyncPort {

    private val api: RailwayApi
        get() = apiProvider.get()

    override suspend fun requestManualSync(sourceType: String): BecalmResult<Unit> =
        when (sourceType) {
            SourceType.GMAIL,
            SourceType.OUTLOOK_MAIL,
            -> syncBackendManagedMail(sourceType)

            SourceType.GOOGLE_CALENDAR,
            SourceType.OUTLOOK_CALENDAR,
            -> syncBackendManagedCalendar(sourceType)

            else -> {
                workScheduler.enqueueExpedited(sourceType)
                logger.d(TAG, "manual sync delegated to local worker sourceType=$sourceType")
                BecalmResult.Success(Unit)
            }
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
        when (val refresh = commitmentRepository.refreshSince(userId = userId, since = null)) {
            is BecalmResult.Success -> logger.d(
                TAG,
                "commitment refresh after backend mail sync sourceType=$sourceType " +
                    "fetched=${refresh.value.fetched} upserted=${refresh.value.upserted}",
            )
            is BecalmResult.Failure -> return onBackendSyncFailure(sourceType, refresh.error)
        }
        logger.d(TAG, "manual sync delegated to backend mail sourceType=$sourceType")
        return finalizeBackendSyncSuccess(sourceType)
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
                when (val calendarRefresh = calendarEventRepository.refreshSince(userId = userId, since = null)) {
                    is BecalmResult.Failure -> return onBackendSyncFailure(sourceType, calendarRefresh.error)
                    is BecalmResult.Success -> logger.d(
                        TAG,
                        "calendar refresh after backend calendar sync sourceType=$sourceType " +
                            "fetched=${calendarRefresh.value.fetched} upserted=${calendarRefresh.value.upserted}",
                    )
                }
                when (val commitmentRefresh = commitmentRepository.refreshSince(userId = userId, since = null)) {
                    is BecalmResult.Failure -> return onBackendSyncFailure(sourceType, commitmentRefresh.error)
                    is BecalmResult.Success -> logger.d(
                        TAG,
                        "commitment refresh after backend calendar sync sourceType=$sourceType " +
                            "fetched=${commitmentRefresh.value.fetched} upserted=${commitmentRefresh.value.upserted}",
                    )
                }
                logger.d(TAG, "manual sync delegated to backend calendar sourceType=$sourceType")
                return finalizeBackendSyncSuccess(sourceType)
            }
        }
    }

    private suspend fun finalizeBackendSyncSuccess(sourceType: String): BecalmResult<Unit> {
        return when (val refresh = sourceStatusRepository.refreshFromServer()) {
            is BecalmResult.Success -> refresh
            is BecalmResult.Failure -> {
                logger.w(TAG, "source_status refresh failed after backend sync sourceType=$sourceType")
                sourceStatusRepository.recordSyncSuccess(sourceType, Clock.System.now())
                BecalmResult.Success(Unit)
            }
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
