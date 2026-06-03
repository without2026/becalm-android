package com.becalm.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.R
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.PhoneNumberUtils
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.SelfIdentityAnchorEntity
import com.becalm.android.data.local.db.entity.SourceConnectionEntity
import com.becalm.android.data.repository.SelfIdentityRepository
import com.becalm.android.data.repository.SourceConnectionRepository
import com.becalm.android.data.repository.UserProfileRepository
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.sources.sourceConnectionTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

public data class SettingsIdentityUiState(
    val userId: String? = null,
    val displayName: String = "",
    val phone: String = "",
    val newAnchorType: String = "email",
    val newAnchorValue: String = "",
    val anchors: List<SelfIdentityAnchorUi> = emptyList(),
    val connections: List<SourceConnectionOwnershipUi> = emptyList(),
    val loading: Boolean = true,
    val savingProfile: Boolean = false,
    val addingAnchor: Boolean = false,
    val archivingAnchorIds: Set<String> = emptySet(),
    val disconnectingConnectionIds: Set<String> = emptySet(),
    val deletingConnectionIds: Set<String> = emptySet(),
    val confirmingDeleteConnectionId: String? = null,
    val notice: UiMessage? = null,
    val error: UiMessage? = null,
)

public data class SelfIdentityAnchorUi(
    val id: String,
    val type: String,
    val value: String,
    val status: String,
    val trust: String,
    val scope: String = "global",
)

public data class SourceConnectionOwnershipUi(
    val id: String,
    val title: String,
    val accountLabel: String,
    val status: String,
)

private const val TAG = "SettingsIdentityViewModel"

@HiltViewModel
public class SettingsIdentityViewModel @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    private val userProfileRepository: UserProfileRepository,
    private val selfIdentityRepository: SelfIdentityRepository,
    private val sourceConnectionRepository: SourceConnectionRepository,
    private val logger: Logger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsIdentityUiState())
    public val uiState: StateFlow<SettingsIdentityUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    public fun refresh() {
        viewModelScope.launch {
            val userId = userPrefsStore.observeCurrentUserId().first()
            if (userId.isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = UiMessage.resource(R.string.settings_identity_error_no_user),
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(userId = userId, loading = true, error = null) }
            val profileResult = userProfileRepository.refreshFromServer(userId)
            if (profileResult is BecalmResult.Failure) {
                logger.w(TAG, "profile refresh failed, using local mirror")
            }
            val connectionsResult = sourceConnectionRepository.refresh(userId)
            if (connectionsResult is BecalmResult.Failure) {
                logger.w(TAG, "source connection refresh failed, using local mirror")
            }
            val anchorsResult = selfIdentityRepository.refresh(userId)
            if (anchorsResult is BecalmResult.Failure) {
                logger.w(TAG, "identity anchor refresh failed, using local mirror")
            }
            val profile = userProfileRepository.find(userId)
            val connections = when (connectionsResult) {
                is BecalmResult.Success -> connectionsResult.value
                is BecalmResult.Failure -> sourceConnectionRepository.observeAll(userId).first()
            }
            val anchors = when (anchorsResult) {
                is BecalmResult.Success -> anchorsResult.value
                is BecalmResult.Failure -> selfIdentityRepository.observeAll(userId).first()
            }
            _uiState.update {
                it.copy(
                    userId = userId,
                    displayName = profile?.displayNameOverride.orEmpty(),
                    phone = profile?.phoneE164Self.orEmpty(),
                    anchors = anchors.map(SelfIdentityAnchorEntity::toUi),
                    connections = connections.map(SourceConnectionEntity::toUi),
                    loading = false,
                    error = null,
                )
            }
        }
    }

    public fun onDisplayNameChange(value: String) {
        _uiState.update { it.copy(displayName = value) }
    }

    public fun onPhoneChange(value: String) {
        _uiState.update { it.copy(phone = value) }
    }

    public fun onNewAnchorTypeChange(value: String) {
        if (value !in SELF_ANCHOR_TYPES) return
        _uiState.update { it.copy(newAnchorType = value) }
    }

    public fun onNewAnchorValueChange(value: String) {
        _uiState.update { it.copy(newAnchorValue = value) }
    }

    public fun onSaveProfile() {
        val state = _uiState.value
        val userId = state.userId ?: return
        val phone = normalizeSelfPhone(state.phone)
        viewModelScope.launch {
            _uiState.update { it.copy(savingProfile = true, notice = null, error = null) }
            try {
                val localProfile = userProfileRepository.upsertLocal(
                    userId = userId,
                    displayName = state.displayName,
                    phoneE164Self = phone,
                )
                upsertOptionalLocalSelfAnchor(userId, anchorType = "phone", value = phone)
                _uiState.update {
                    it.copy(
                        displayName = localProfile.displayNameOverride.orEmpty(),
                        phone = localProfile.phoneE164Self.orEmpty(),
                        error = null,
                    )
                }
                if (mirrorProfileRemote(userId, localProfile.displayNameOverride.orEmpty(), localProfile.phoneE164Self.orEmpty())) {
                    val anchors = selfIdentityRepository.observeAll(userId).first()
                    _uiState.update {
                        it.copy(
                            anchors = anchors.map(SelfIdentityAnchorEntity::toUi),
                            savingProfile = false,
                            notice = UiMessage.resource(R.string.settings_identity_profile_saved),
                            error = null,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            savingProfile = false,
                            error = UiMessage.resource(R.string.settings_identity_error_save_profile),
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _uiState.update {
                    it.copy(
                        savingProfile = false,
                        error = UiMessage.resource(R.string.settings_identity_error_save_profile),
                    )
                }
            }
        }
    }

    private suspend fun upsertOptionalLocalSelfAnchor(
        userId: String,
        anchorType: String,
        value: String,
    ) {
        val trimmed = normalizeSelfAnchorValue(anchorType, value)
        if (trimmed.isEmpty()) return
        selfIdentityRepository.upsertLocalAnchor(
            userId = userId,
            anchorType = anchorType,
            value = trimmed,
            displayValue = trimmed,
            source = "user_profile",
        )
    }

    private suspend fun mirrorProfileRemote(
        userId: String,
        displayName: String,
        phone: String,
    ): Boolean {
        try {
            return when (userProfileRepository.updateRemote(userId = userId, displayName = displayName, phoneE164Self = phone)) {
                is BecalmResult.Success -> {
                    val anchorCreated = createOptionalSelfAnchor(userId, anchorType = "phone", value = phone)
                    selfIdentityRepository.refresh(userId)
                    anchorCreated
                }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "settings identity remote profile mirror failed")
                    false
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            logger.w(TAG, "settings identity remote profile mirror failed", t)
            return false
        }
    }

    private suspend fun createOptionalSelfAnchor(
        userId: String,
        anchorType: String,
        value: String,
    ): Boolean {
        val trimmed = normalizeSelfAnchorValue(anchorType, value)
        if (trimmed.isEmpty()) return true
        return when (
            selfIdentityRepository.createAnchor(
                userId = userId,
                anchorType = anchorType,
                value = trimmed,
                displayValue = trimmed,
                source = "user_profile",
            )
        ) {
            is BecalmResult.Success -> true
            is BecalmResult.Failure -> false
        }
    }

    public fun onAddAnchor() {
        val state = _uiState.value
        val userId = state.userId ?: return
        val value = normalizeSelfAnchorValue(state.newAnchorType, state.newAnchorValue)
        if (value.isEmpty()) {
            _uiState.update { it.copy(error = UiMessage.resource(R.string.settings_identity_error_anchor_value)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(addingAnchor = true, notice = null, error = null) }
            try {
                when (
                    selfIdentityRepository.createAnchor(
                        userId = userId,
                        anchorType = state.newAnchorType,
                        value = value,
                        displayValue = value,
                        source = "user_profile",
                    )
                ) {
                    is BecalmResult.Success -> {
                        val anchors = selfIdentityRepository.observeAll(userId).first()
                        _uiState.update {
                            it.copy(
                                newAnchorValue = "",
                                anchors = anchors.map(SelfIdentityAnchorEntity::toUi),
                                addingAnchor = false,
                                notice = UiMessage.resource(R.string.settings_identity_anchor_added),
                                error = null,
                            )
                        }
                    }
                    is BecalmResult.Failure -> {
                        logger.w(TAG, "settings identity remote anchor mirror failed")
                        _uiState.update {
                            it.copy(
                                addingAnchor = false,
                                error = UiMessage.resource(R.string.settings_identity_error_add_anchor),
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _uiState.update {
                    it.copy(
                        addingAnchor = false,
                        error = UiMessage.resource(R.string.settings_identity_error_add_anchor),
                    )
                }
            }
        }
    }

    private fun normalizeSelfAnchorValue(anchorType: String, value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        return if (anchorType == "phone") normalizeSelfPhone(trimmed) else trimmed
    }

    private fun normalizeSelfPhone(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        return PhoneNumberUtils.toE164OrNull(trimmed) ?: trimmed
    }

    public fun onArchiveAnchor(id: String) {
        val userId = _uiState.value.userId ?: return
        if (id in _uiState.value.archivingAnchorIds) return
        _uiState.update { it.copy(archivingAnchorIds = it.archivingAnchorIds + id, error = null) }
        viewModelScope.launch {
            when (selfIdentityRepository.updateAnchor(id = id, status = "disabled")) {
                is BecalmResult.Success -> {
                    val anchors = selfIdentityRepository.observeAll(userId).first()
                    _uiState.update {
                        it.copy(
                            anchors = anchors.map(SelfIdentityAnchorEntity::toUi),
                            archivingAnchorIds = it.archivingAnchorIds - id,
                            error = null,
                        )
                    }
                }
                is BecalmResult.Failure -> _uiState.update {
                    it.copy(
                        archivingAnchorIds = it.archivingAnchorIds - id,
                        error = UiMessage.resource(R.string.settings_identity_error_update_anchor),
                    )
                }
            }
        }
    }

    public fun onDisconnectConnection(connectionId: String) {
        val userId = _uiState.value.userId ?: return
        val state = _uiState.value
        if (connectionId in state.disconnectingConnectionIds || connectionId in state.deletingConnectionIds) return
        _uiState.update {
            it.copy(
                disconnectingConnectionIds = it.disconnectingConnectionIds + connectionId,
                confirmingDeleteConnectionId = null,
                error = null,
            )
        }
        viewModelScope.launch {
            when (sourceConnectionRepository.disconnectConnection(userId, connectionId)) {
                is BecalmResult.Success -> {
                    val connections = sourceConnectionRepository.observeAll(userId).first()
                    _uiState.update {
                        it.copy(
                            connections = connections.map(SourceConnectionEntity::toUi),
                            disconnectingConnectionIds = it.disconnectingConnectionIds - connectionId,
                            error = null,
                        )
                    }
                }
                is BecalmResult.Failure -> _uiState.update {
                    it.copy(
                        disconnectingConnectionIds = it.disconnectingConnectionIds - connectionId,
                        error = UiMessage.resource(R.string.settings_identity_error_disconnect_connection),
                    )
                }
            }
        }
    }

    public fun onRequestDeleteConnection(connectionId: String) {
        val state = _uiState.value
        if (connectionId in state.disconnectingConnectionIds || connectionId in state.deletingConnectionIds) return
        _uiState.update { it.copy(confirmingDeleteConnectionId = connectionId, error = null) }
    }

    public fun onDismissDeleteConnection() {
        _uiState.update { it.copy(confirmingDeleteConnectionId = null) }
    }

    public fun onConfirmDeleteConnection() {
        val userId = _uiState.value.userId ?: return
        val connectionId = _uiState.value.confirmingDeleteConnectionId ?: return
        if (connectionId in _uiState.value.deletingConnectionIds) return
        _uiState.update {
            it.copy(
                deletingConnectionIds = it.deletingConnectionIds + connectionId,
                confirmingDeleteConnectionId = null,
                error = null,
            )
        }
        viewModelScope.launch {
            when (sourceConnectionRepository.deleteConnection(userId, connectionId)) {
                is BecalmResult.Success -> {
                    val connections = sourceConnectionRepository.observeAll(userId).first()
                    _uiState.update {
                        it.copy(
                            connections = connections.map(SourceConnectionEntity::toUi),
                            deletingConnectionIds = it.deletingConnectionIds - connectionId,
                            error = null,
                        )
                    }
                }
                is BecalmResult.Failure -> _uiState.update {
                    it.copy(
                        deletingConnectionIds = it.deletingConnectionIds - connectionId,
                        error = UiMessage.resource(R.string.settings_identity_error_delete_connection),
                    )
                }
            }
        }
    }

    public fun onErrorDismissed() {
        _uiState.update { it.copy(error = null) }
    }

    public fun onNoticeDismissed() {
        _uiState.update { it.copy(notice = null) }
    }
}

private fun SelfIdentityAnchorEntity.toUi(): SelfIdentityAnchorUi =
    SelfIdentityAnchorUi(
        id = id,
        type = anchorType,
        value = displayValue ?: normalizedValue,
        status = status,
        trust = trust,
        scope = scope,
    )

private fun SourceConnectionEntity.toUi(): SourceConnectionOwnershipUi =
    SourceConnectionOwnershipUi(
        id = id,
        title = sourceConnectionTitle(provider = provider, capability = capability),
        accountLabel = accountDisplayName ?: accountIdentifier ?: provider,
        status = status,
    )

private val SELF_ANCHOR_TYPES = setOf("email", "phone", "alias")
