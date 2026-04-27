package com.becalm.android.ui.sources

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.result.safeMessage
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.dto.SourceType

internal object SourceDetailActionResolver {
    fun reconnectDestinationFor(sourceType: String): SourceReconnectDestination? =
        when (sourceType) {
            SourceType.VOICE -> SourceReconnectDestination.RECORDING_FOLDER
            SourceType.GMAIL -> SourceReconnectDestination.GMAIL
            SourceType.OUTLOOK_MAIL -> SourceReconnectDestination.OUTLOOK_MAIL
            SourceType.NAVER_IMAP,
            SourceType.DAUM_IMAP,
            -> SourceReconnectDestination.IMAP
            SourceType.GOOGLE_CALENDAR -> SourceReconnectDestination.GOOGLE_CALENDAR
            SourceType.OUTLOOK_CALENDAR -> SourceReconnectDestination.OUTLOOK_CALENDAR
            else -> null
        }
}

internal class SourceDetailActionHandler(
    private val sourceAdministrationPort: SourceAdministrationPort,
    private val sourceSyncPort: SourceSyncPort,
    private val logger: Logger,
) {
    suspend fun requestManualSync(
        sourceType: String,
        hasValidSourceType: Boolean,
    ): BecalmResult<Unit> {
        if (!hasValidSourceType || sourceType == SourceType.CALL_RECORDING) {
            logger.w(TAG, "onManualSync ignored for unsupported sourceType=$sourceType")
            return BecalmResult.Failure(BecalmError.Validation("sourceType", "unsupported source"))
        }
        return sourceSyncPort.requestManualSync(sourceType)
    }

    suspend fun disconnect(
        sourceType: String,
        onSuccess: (SourceDisconnectOutcome) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        when (val result = sourceAdministrationPort.disconnect(sourceType)) {
            is BecalmResult.Success -> {
                onSuccess(result.value)
                logger.d(TAG, "disconnect completed for sourceType=$sourceType")
            }
            is BecalmResult.Failure -> {
                val message = when (val error = result.error) {
                    is BecalmError.Validation -> error.message
                    else -> error.safeMessage
                }
                onFailure(message)
                logger.w(TAG, "disconnect failed for sourceType=$sourceType")
            }
        }
    }

    private companion object {
        const val TAG: String = "SourceDetailViewModel"
    }
}
