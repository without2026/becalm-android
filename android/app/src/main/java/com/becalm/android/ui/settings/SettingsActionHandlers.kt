package com.becalm.android.ui.settings

import com.becalm.android.R
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.worker.WorkScheduler
import kotlinx.coroutines.flow.first

internal class SettingsPipaConsentHandler(
    private val userPrefsStore: UserPrefsStore,
    private val rawIngestionRepository: RawIngestionRepository,
    private val workScheduler: WorkScheduler,
    private val logger: Logger,
) {

    suspend fun toggle(
        enabled: Boolean,
        updateState: ((SettingsUiState) -> SettingsUiState) -> Unit,
    ) {
        userPrefsStore.setThirdPartyProvisionConsent(enabled)
        logger.i(TAG, "PIPA third-party provision consent toggled to $enabled")

        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId.isNullOrBlank()) {
            logger.w(TAG, "onTogglePipaConsent: userId absent — skipping post-toggle steps")
            return
        }

        if (enabled) {
            val releasedIds = unwrapIdsOrShowError(
                result = rawIngestionRepository.releaseAwaitingConsentVoiceAndReturnIds(userId),
                failureLogPrefix = "releaseAwaitingConsentVoiceAndReturnIds failed",
                failureMessage = UiMessage.resource(R.string.settings_error_voice_release_failed),
                updateState = updateState,
            )
            val enqueuedCount = reenqueueReleasedVoice(userId, releasedIds)
            logger.d(TAG, "re-enqueued $enqueuedCount voice uploads after consent grant")
        } else {
            val parkedIds = unwrapIdsOrShowError(
                result = rawIngestionRepository.parkAndCancelPendingVoice(userId),
                failureLogPrefix = "parkAndCancelPendingVoice failed",
                failureMessage = UiMessage.resource(R.string.settings_error_voice_park_failed),
                updateState = updateState,
            )
            for (id in parkedIds) {
                val entity = rawIngestionRepository.findById(id = id, userId = userId)
                when {
                    entity?.sourceType == SourceType.MESSAGE_SCREENSHOT -> {
                        workScheduler.cancelMessageScreenshotUpload(rawEventId = id)
                    }
                    else -> {
                        workScheduler.cancelVoiceUpload(rawEventId = id)
                    }
                }
            }
            logger.d(TAG, "parked and cancelled ${parkedIds.size} voice uploads after consent withdrawal")
        }
    }

    private fun unwrapIdsOrShowError(
        result: BecalmResult<List<String>>,
        failureLogPrefix: String,
        failureMessage: UiMessage,
        updateState: ((SettingsUiState) -> SettingsUiState) -> Unit,
    ): List<String> = when (result) {
        is BecalmResult.Success -> result.value
        is BecalmResult.Failure -> {
            logger.e(TAG, "$failureLogPrefix: ${result.error}")
            updateState { it.copy(error = failureMessage) }
            emptyList()
        }
    }

    private suspend fun reenqueueReleasedVoice(userId: String, releasedIds: List<String>): Int {
        var enqueuedCount = 0
        for (id in releasedIds) {
            val entity = rawIngestionRepository.findById(id = id, userId = userId)
            if (entity == null) {
                logger.w(TAG, "released voice row id=$id not found for re-enqueue — skipping")
                continue
            }
            val sourceRef = entity.sourceRef
            if (sourceRef.isNullOrBlank()) {
                logger.w(TAG, "released voice row id=$id has null sourceRef — skipping enqueue")
                continue
            }
            when {
                entity.sourceType == SourceType.MESSAGE_SCREENSHOT -> {
                    workScheduler.enqueueMessageScreenshotUpload(rawEventId = id)
                }
                else -> {
                    workScheduler.enqueueVoiceUpload(rawEventId = id, audioUri = sourceRef)
                }
            }
            enqueuedCount++
        }
        return enqueuedCount
    }

    private companion object {
        const val TAG: String = "SettingsViewModel"
    }
}

internal class SettingsSessionActionHandler(
    private val authRepository: AuthRepository,
    private val logger: Logger,
) {

    suspend fun runAuthOp(
        failureLogPrefix: String,
        failureMessage: UiMessage,
        op: suspend () -> BecalmResult<Unit>,
        onSuccess: () -> Unit,
        updateState: ((SettingsUiState) -> SettingsUiState) -> Unit,
    ) {
        updateState { it.copy(loading = true, error = null) }
        when (val result = op()) {
            is BecalmResult.Success -> onSuccess()
            is BecalmResult.Failure -> {
                logger.w(TAG, "$failureLogPrefix: ${result.error}")
                updateState { it.copy(loading = false, error = failureMessage) }
            }
        }
    }

    suspend fun signOut(
        updateState: ((SettingsUiState) -> SettingsUiState) -> Unit,
    ) = runAuthOp(
        failureLogPrefix = "sign-out failed",
        failureMessage = UiMessage.resource(R.string.settings_error_sign_out_failed),
        op = { authRepository.invalidateSession() },
        onSuccess = {
            logger.d(TAG, "sign-out completed (session invalidated, Room preserved)")
            updateState { it.copy(loading = false, signedOut = true) }
        },
        updateState = updateState,
    )

    suspend fun wipeLocalData(
        updateState: ((SettingsUiState) -> SettingsUiState) -> Unit,
    ) = runAuthOp(
        failureLogPrefix = "PIPA wipe failed",
        failureMessage = UiMessage.resource(R.string.settings_error_wipe_failed),
        op = { authRepository.signOut() },
        onSuccess = {
            logger.d(TAG, "PIPA wipe and sign-out completed")
            updateState { it.copy(loading = false, error = null) }
        },
        updateState = updateState,
    )

    private companion object {
        const val TAG: String = "SettingsViewModel"
    }
}
