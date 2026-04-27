package com.becalm.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.PipaActionLogEntry
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.PersonEnrichmentDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.worker.AppRuntimeSyncCoordinator
import com.becalm.android.worker.ForegroundCatchUpScheduler
import com.becalm.android.worker.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

public data class PrivacyManagementUiState(
    val loading: Boolean = true,
    val userEmail: String? = null,
    val voiceConsentEnabled: Boolean = false,
    val gmailConnected: Boolean = false,
    val outlookConnected: Boolean = false,
    val naverConnected: Boolean = false,
    val daumConnected: Boolean = false,
    val googleCalendarEnabled: Boolean = false,
    val outlookCalendarEnabled: Boolean = false,
    val processingPaused: Boolean = false,
    val pauseStartedAt: Long? = null,
    val activityLog: List<PipaActionLogEntry> = emptyList(),
    val commitmentCount: Int = 0,
    val emailCount: Int = 0,
    val enrichmentCount: Int = 0,
    val exporting: Boolean = false,
    val signedOut: Boolean = false,
    val error: String? = null,
)

public sealed interface PrivacyManagementEffect {
    public data class CreateExportDocument(
        val fileName: String,
        val bytes: ByteArray,
    ) : PrivacyManagementEffect
}

public enum class WithdrawConsentTarget {
    VOICE,
    GMAIL,
    OUTLOOK_MAIL,
    NAVER_IMAP,
    DAUM_IMAP,
    GOOGLE_CALENDAR,
    OUTLOOK_CALENDAR,
}

@HiltViewModel
public class PrivacyManagementViewModel @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    private val authRepository: AuthRepository,
    private val rawIngestionRepository: RawIngestionRepository,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val commitmentDao: CommitmentDao,
    private val personEnrichmentDao: PersonEnrichmentDao,
    private val privacyDataExporter: PrivacyDataExporter,
    private val workScheduler: WorkScheduler,
    private val appRuntimeSyncCoordinator: AppRuntimeSyncCoordinator,
    private val foregroundCatchUpScheduler: ForegroundCatchUpScheduler,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {

    private val _staticState = MutableStateFlow(PrivacyManagementUiState())
    private val _effects = MutableSharedFlow<PrivacyManagementEffect>()
    public val effects = _effects.asSharedFlow()

    public val uiState: StateFlow<PrivacyManagementUiState> = combine(
        _staticState,
        userPrefsStore.observeThirdPartyProvisionConsent(),
        userPrefsStore.observeEmailSourceConnected(EmailPipaProvider.GMAIL),
        userPrefsStore.observeEmailSourceConnected(EmailPipaProvider.OUTLOOK_MAIL),
        userPrefsStore.observeEmailSourceConnected(EmailPipaProvider.NAVER_IMAP),
        userPrefsStore.observeEmailSourceConnected(EmailPipaProvider.DAUM_IMAP),
        userPrefsStore.observeSourceEnabled(SourceType.GOOGLE_CALENDAR),
        userPrefsStore.observeSourceEnabled(SourceType.OUTLOOK_CALENDAR),
        userPrefsStore.observeProcessingPaused(),
        userPrefsStore.observePauseStartedAt(),
        userPrefsStore.observePipaActionLog(),
    ) { values ->
        val base = values[0] as PrivacyManagementUiState
        base.copy(
            voiceConsentEnabled = values[1] as Boolean,
            gmailConnected = values[2] as Boolean,
            outlookConnected = values[3] as Boolean,
            naverConnected = values[4] as Boolean,
            daumConnected = values[5] as Boolean,
            googleCalendarEnabled = values[6] as Boolean,
            outlookCalendarEnabled = values[7] as Boolean,
            processingPaused = values[8] as Boolean,
            pauseStartedAt = values[9] as Long?,
            activityLog = values[10] as List<PipaActionLogEntry>,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = PrivacyManagementUiState(),
    )

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch(ioDispatcher) {
            val session = authRepository.currentSession()
            val userId = session?.userId
            val commitmentCount = userId?.let { commitmentDao.countForUser(it) } ?: 0
            val emailCount = userId?.let { rawIngestionEventDao.countEmailRowsForUser(it) } ?: 0
            val enrichmentCount = personEnrichmentDao.countAll()
            _staticState.value = _staticState.value.copy(
                loading = false,
                userEmail = session?.email,
                commitmentCount = commitmentCount,
                emailCount = emailCount,
                enrichmentCount = enrichmentCount,
            )
        }
    }

    public fun onErrorDismissed() {
        _staticState.value = _staticState.value.copy(error = null)
    }

    public fun onExportRequested() {
        viewModelScope.launch(ioDispatcher) {
            val userId = currentUserId()
            if (userId.isNullOrBlank()) {
                _staticState.value = _staticState.value.copy(error = "no signed-in user")
                return@launch
            }
            _staticState.value = _staticState.value.copy(exporting = true, error = null)
            runCatching {
                privacyDataExporter.export(userId, Clock.System.now().toEpochMilliseconds())
            }.onSuccess { payload ->
                _effects.emit(
                    PrivacyManagementEffect.CreateExportDocument(
                        fileName = payload.fileName,
                        bytes = payload.bytes,
                    ),
                )
            }.onFailure { error ->
                logger.e(TAG, "export failed", error)
                _staticState.value = _staticState.value.copy(
                    exporting = false,
                    error = error.message ?: "export failed",
                )
            }
        }
    }

    public fun onExportSaved() {
        viewModelScope.launch(ioDispatcher) {
            userPrefsStore.appendPipaActionLog(
                PipaActionLogEntry(
                    action = "data_export",
                    timestampIso = Clock.System.now().toString(),
                ),
            )
            _staticState.value = _staticState.value.copy(exporting = false, error = null)
        }
    }

    public fun onExportFailed(message: String) {
        _staticState.value = _staticState.value.copy(exporting = false, error = message)
    }

    public fun onWithdrawConsent(target: WithdrawConsentTarget) {
        viewModelScope.launch(ioDispatcher) {
            when (target) {
                WithdrawConsentTarget.VOICE -> withdrawVoiceConsent()
                WithdrawConsentTarget.GMAIL -> withdrawEmailProvider(EmailPipaProvider.GMAIL)
                WithdrawConsentTarget.OUTLOOK_MAIL -> withdrawEmailProvider(EmailPipaProvider.OUTLOOK_MAIL)
                WithdrawConsentTarget.NAVER_IMAP -> withdrawEmailProvider(EmailPipaProvider.NAVER_IMAP)
                WithdrawConsentTarget.DAUM_IMAP -> withdrawEmailProvider(EmailPipaProvider.DAUM_IMAP)
                WithdrawConsentTarget.GOOGLE_CALENDAR -> withdrawSource(SourceType.GOOGLE_CALENDAR)
                WithdrawConsentTarget.OUTLOOK_CALENDAR -> withdrawSource(SourceType.OUTLOOK_CALENDAR)
            }
        }
    }

    public fun onSetProcessingPaused(paused: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            if (paused) {
                userPrefsStore.setProcessingPaused(true)
                userPrefsStore.setPauseStartedAt(Clock.System.now().toEpochMilliseconds())
                userPrefsStore.appendPipaActionLog(
                    PipaActionLogEntry(
                        action = "processing_pause",
                        timestampIso = Clock.System.now().toString(),
                    ),
                )
            } else {
                userPrefsStore.setProcessingPaused(false)
                userPrefsStore.setPauseStartedAt(null)
                userPrefsStore.appendPipaActionLog(
                    PipaActionLogEntry(
                        action = "processing_resume",
                        timestampIso = Clock.System.now().toString(),
                    ),
                )
                appRuntimeSyncCoordinator.refresh()
                foregroundCatchUpScheduler.triggerCatchUp()
            }
        }
    }

    public fun onConfirmAccountDeletion(
        emailInput: String,
        confirmationText: String,
    ) {
        viewModelScope.launch(ioDispatcher) {
            val expectedEmail = _staticState.value.userEmail
            if (expectedEmail.isNullOrBlank() || emailInput != expectedEmail) {
                _staticState.value = _staticState.value.copy(error = "email mismatch")
                return@launch
            }
            if (confirmationText != "삭제") {
                _staticState.value = _staticState.value.copy(error = "confirmation text mismatch")
                return@launch
            }
            userPrefsStore.appendPipaActionLog(
                PipaActionLogEntry(
                    action = "account_delete_initiated",
                    timestampIso = Clock.System.now().toString(),
                ),
            )
            when (authRepository.signOut()) {
                is BecalmResult.Success -> {
                    _staticState.value = _staticState.value.copy(signedOut = true, error = null)
                }
                is BecalmResult.Failure -> {
                    _staticState.value = _staticState.value.copy(error = "account deletion failed")
                }
            }
        }
    }

    private suspend fun withdrawVoiceConsent() {
        val userId = currentUserId() ?: return
        userPrefsStore.setThirdPartyProvisionConsent(false)
        val parkedIds = when (val parked = rawIngestionRepository.parkAndCancelPendingVoice(userId)) {
            is BecalmResult.Success -> parked.value
            is BecalmResult.Failure -> {
                _staticState.value = _staticState.value.copy(error = parked.error.toString())
                return
            }
        }
        parkedIds.forEach(workScheduler::cancelVoiceUpload)
        userPrefsStore.appendPipaActionLog(
            PipaActionLogEntry(
                action = "consent_withdraw",
                timestampIso = Clock.System.now().toString(),
                details = mapOf("consent_type" to "pipa_third_party"),
            ),
        )
    }

    private suspend fun withdrawEmailProvider(provider: EmailPipaProvider) {
        userPrefsStore.setEmailPipaConsent(provider, granted = false)
        userPrefsStore.setEmailSourceConnected(provider, connected = false)
        userPrefsStore.setEmailSourceManagedByBackend(provider, managed = false)
        userPrefsStore.appendPipaActionLog(
            PipaActionLogEntry(
                action = "consent_withdraw",
                timestampIso = Clock.System.now().toString(),
                details = mapOf("source" to provider.storageKey),
            ),
        )
    }

    private suspend fun withdrawSource(sourceType: String) {
        userPrefsStore.setSourceEnabled(sourceType, false)
        userPrefsStore.appendPipaActionLog(
            PipaActionLogEntry(
                action = "consent_withdraw",
                timestampIso = Clock.System.now().toString(),
                details = mapOf("source" to sourceType),
            ),
        )
        appRuntimeSyncCoordinator.refresh()
    }

    private suspend fun currentUserId(): String? = userPrefsStore.observeCurrentUserId().first()

    private companion object {
        private const val TAG = "PrivacyManagementVM"
    }
}
