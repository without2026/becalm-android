package com.becalm.android.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.worker.AppRuntimeSyncCoordinator
import com.becalm.android.worker.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

public enum class OnboardingCompletionConnectedSource {
    CONTACTS,
    CALENDAR,
    CALL_RECORDING,
    GMAIL,
}

public data class OnboardingCompletionSummaryUi(
    val personId: String = "ready",
    val personName: String? = null,
    val connectedSources: Set<OnboardingCompletionConnectedSource> = emptySet(),
    val notificationsEnabled: Boolean = false,
    val meetingRecordingReady: Boolean = false,
)

@HiltViewModel
internal class OnboardingCompleteViewModel @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    private val personIndexDao: PersonIndexDao,
    private val rawIngestionRepository: RawIngestionRepository,
    private val appRuntimeSyncCoordinator: AppRuntimeSyncCoordinator,
    private val workScheduler: WorkScheduler,
    private val logger: Logger,
) : ViewModel() {

    private val _uiState: MutableStateFlow<OnboardingCompletionSummaryUi> =
        MutableStateFlow(OnboardingCompletionSummaryUi())

    val uiState: StateFlow<OnboardingCompletionSummaryUi> = _uiState.asStateFlow()

    init {
        observeConnectedSources()
    }

    fun load(personId: String) {
        _uiState.update { it.copy(personId = personId, personName = null) }
        if (personId == ONBOARDING_COMPLETE_GENERIC_PERSON_ID) return
        viewModelScope.launch {
            try {
                val userId = userPrefsStore.observeCurrentUserId().first()
                if (userId.isNullOrBlank()) return@launch
                val personName = personIndexDao.findPersonsByIds(userId, listOf(personId))
                    .firstOrNull()
                    ?.displayName
                    ?.takeIf { it.isNotBlank() }
                _uiState.update { state ->
                    if (state.personId == personId) {
                        state.copy(personName = personName)
                    } else {
                        state
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { state ->
                    if (state.personId == personId) state.copy(personName = null) else state
                }
            }
        }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            userPrefsStore.setNotificationsEnabled(granted)
            val currentStatuses = userPrefsStore.observeOnboardingStepStatuses().first()
            val notificationStatus = OnboardingProgressResolver.encodeStepStatuses(
                mapOf(
                    OnboardingStep.NOTIFICATION_PERM to if (granted) {
                        StepStatus.GRANTED
                    } else {
                        StepStatus.DENIED
                    },
                ),
            )
            userPrefsStore.setOnboardingStepStatuses(
                currentStatuses + notificationStatus,
            )
        }
    }

    fun onMeetingRecordingPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            if (!granted) {
                userPrefsStore.setSourceEnabled(SourceType.MEETING, false)
                userPrefsStore.setRecordingFolderTreeUri(SourceType.MEETING, null)
                appRuntimeSyncCoordinator.refresh()
                return@launch
            }
            userPrefsStore.setThirdPartyProvisionConsent(true)
            userPrefsStore.setRecordingFolderTreeUri(
                SourceType.MEETING,
                RecordingPathSelection.forSourceType(SourceType.MEETING),
            )
            userPrefsStore.setSourceEnabled(SourceType.MEETING, true)
            releaseAwaitingRecordingRows()
            appRuntimeSyncCoordinator.refresh()
            workScheduler.enqueueMediaStoreOneShotNow(RECORDING_PERMISSION_LOOKBACK_DAYS)
        }
    }

    private suspend fun releaseAwaitingRecordingRows() {
        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId.isNullOrBlank()) return
        when (val result = rawIngestionRepository.releaseAwaitingConsentVoiceAndReturnIds(userId)) {
            is BecalmResult.Failure -> logger.w(
                TAG,
                "failed to release awaiting voice rows after meeting recording grant: ${result.error}",
            )
            is BecalmResult.Success -> {
                result.value.forEach { rawEventId ->
                    val entity = rawIngestionRepository.findById(id = rawEventId, userId = userId)
                        ?: return@forEach
                    val sourceRef = entity.sourceRef.takeUnless { it.isNullOrBlank() }
                        ?: return@forEach
                    when (entity.sourceType) {
                        SourceType.CALL_RECORDING,
                        SourceType.MEETING,
                        -> workScheduler.enqueueMeetingSpeakerPreview(
                            rawEventId = rawEventId,
                            audioUri = sourceRef,
                        )
                        SourceType.MESSAGE_SCREENSHOT -> workScheduler.enqueueMessageScreenshotUpload(
                            rawEventId = rawEventId,
                        )
                        else -> workScheduler.enqueueVoiceUpload(rawEventId = rawEventId, audioUri = sourceRef)
                    }
                }
            }
        }
    }

    private fun observeConnectedSources() {
        viewModelScope.launch {
            combine(
                userPrefsStore.observeOnboardingStepStatuses(),
                userPrefsStore.observeSourceEnabled(SourceType.GOOGLE_CALENDAR),
                userPrefsStore.observeSourceEnabled(SourceType.CALL_RECORDING),
                userPrefsStore.observeRecordingFolderTreeUri(SourceType.CALL_RECORDING),
                userPrefsStore.observeNotificationsEnabled(),
                userPrefsStore.observeSourceEnabled(SourceType.MEETING),
                userPrefsStore.observeRecordingFolderTreeUri(SourceType.MEETING),
                userPrefsStore.observeEmailSourceConnected(EmailPipaProvider.GMAIL),
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val stepStatusRaw = values[0] as Map<String, String>
                val googleCalendarEnabled = values[1] as Boolean
                val callRecordingEnabled = values[2] as Boolean
                val callRecordingFolder = values[3] as String?
                val notificationsEnabled = values[4] as Boolean
                val meetingEnabled = values[5] as Boolean
                val meetingFolder = values[6] as String?
                val gmailConnected = values[7] as Boolean
                val stepStates = OnboardingProgressResolver.decodeStepStatuses(stepStatusRaw)
                val connectedSources = buildSet {
                    if (stepStates[OnboardingStep.CONTACTS_PERM] == StepStatus.GRANTED) {
                        add(OnboardingCompletionConnectedSource.CONTACTS)
                    }
                    if (googleCalendarEnabled ||
                        stepStates[OnboardingStep.LINK_GOOGLE_CALENDAR] == StepStatus.COMPLETE
                    ) {
                        add(OnboardingCompletionConnectedSource.CALENDAR)
                    }
                    if (callRecordingEnabled && !callRecordingFolder.isNullOrBlank()) {
                        add(OnboardingCompletionConnectedSource.CALL_RECORDING)
                    }
                    if (gmailConnected ||
                        stepStates[OnboardingStep.LINK_GMAIL] == StepStatus.COMPLETE
                    ) {
                        add(OnboardingCompletionConnectedSource.GMAIL)
                    }
                }
                CompletionSourceSnapshot(
                    connectedSources = connectedSources,
                    notificationsEnabled = notificationsEnabled,
                    meetingRecordingReady = meetingEnabled && !meetingFolder.isNullOrBlank(),
                )
            }
                .distinctUntilChanged()
                .collect { snapshot ->
                    _uiState.update {
                        it.copy(
                            connectedSources = snapshot.connectedSources,
                            notificationsEnabled = snapshot.notificationsEnabled,
                            meetingRecordingReady = snapshot.meetingRecordingReady,
                        )
                    }
                }
        }
    }

    private data class CompletionSourceSnapshot(
        val connectedSources: Set<OnboardingCompletionConnectedSource>,
        val notificationsEnabled: Boolean,
        val meetingRecordingReady: Boolean,
    )

    private companion object {
        const val TAG = "OnboardingCompleteViewModel"
        const val RECORDING_PERMISSION_LOOKBACK_DAYS = 30
    }
}
