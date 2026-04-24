package com.becalm.android.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.data.repository.PersonEnrichmentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Instant

/** UI state for the contacts pseudo-source detail screen. */
public data class ContactsSourceDetailUiState(
    val connectionState: String = "",
    val enrichedCount: Int? = 0,
    val lastSyncAt: Instant? = null,
    val showPermissionRevokeButton: Boolean = false,
)

/** One-shot contacts detail effects. */
public sealed interface ContactsSourceDetailEffect {
    /** Open Android app settings so the user can revoke READ_CONTACTS manually. */
    public data object OpenPermissionSettings : ContactsSourceDetailEffect

    /** Re-enter the contacts permission screen when the source is currently disconnected. */
    public data object OpenContactsPermissionScreen : ContactsSourceDetailEffect
}

@HiltViewModel
public class ContactsSourceDetailViewModel @Inject constructor(
    personEnrichmentRepository: PersonEnrichmentRepository,
    private val contactsPermissionChecker: ContactsPermissionChecker,
) : ViewModel() {

    private val _effects: MutableSharedFlow<ContactsSourceDetailEffect> =
        MutableSharedFlow(extraBufferCapacity = 1)

    public val effects: SharedFlow<ContactsSourceDetailEffect> = _effects.asSharedFlow()

    public val state: StateFlow<ContactsSourceDetailUiState> = combine(
        personEnrichmentRepository.observeAll(),
        contactsPermissionChecker.observeGrantState(),
    ) { enrichmentRows, permissionGranted ->
        ContactsSourceDetailUiState(
            connectionState = if (permissionGranted) "CONNECTED" else "DISCONNECTED",
            enrichedCount = enrichmentRows.size,
            lastSyncAt = enrichmentRows.maxOfOrNull { it.lastSyncedAt },
            showPermissionRevokeButton = permissionGranted,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ContactsSourceDetailUiState(),
    )

    public fun onPermissionAction() {
        val target = if (contactsPermissionChecker.isGranted()) {
            ContactsSourceDetailEffect.OpenPermissionSettings
        } else {
            ContactsSourceDetailEffect.OpenContactsPermissionScreen
        }
        _effects.tryEmit(target)
    }
}
