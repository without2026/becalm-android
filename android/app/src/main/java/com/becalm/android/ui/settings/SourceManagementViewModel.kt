package com.becalm.android.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.becalm.android.data.local.DataStoreKeys
import com.becalm.android.workers.EnrichmentWorker
import com.becalm.android.workers.UploadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

// spec: SMG-001..SMG-005 — source management
// spec: SMG-004 — disconnect: clear cursor + Keystore creds, preserve Room data
// spec: SMG-005 — [지금 동기화] = per-source WorkManager enqueueUniqueWork

enum class SourceConnectionState { CONNECTED, DISCONNECTED, ERROR }

data class SourceStatus(
    val sourceId: String,
    val displayName: String,
    val state: SourceConnectionState,
    val errorMessage: String? = null,
    val lastSyncAt: Long? = null,
    val eventsSyncedCount: Int = 0
)

data class SourceManagementUiState(
    val sources: List<SourceStatus> = emptyList(),
    val isLoading: Boolean = false
)

@HiltViewModel
class SourceManagementViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val workManager: WorkManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SourceManagementUiState(isLoading = true))
    val uiState: StateFlow<SourceManagementUiState> = _uiState

    init {
        loadSourceStatuses()
    }

    // spec: SMG-001 — build 7-row list: 6 sources + contacts pseudo-source
    private fun loadSourceStatuses() {
        viewModelScope.launch {
            val prefs = dataStore.data.first()
            val sources = listOf(
                SourceStatus("voice", "녹음 파일",
                    if (prefs[DataStoreKeys.VOICE_SAF_URI] != null) SourceConnectionState.CONNECTED
                    else SourceConnectionState.DISCONNECTED),
                SourceStatus("gmail", "Gmail",
                    if (prefs[DataStoreKeys.GMAIL_CONNECTED] == true) SourceConnectionState.CONNECTED
                    else SourceConnectionState.DISCONNECTED),
                SourceStatus("outlook_mail", "Outlook 메일",
                    if (prefs[DataStoreKeys.OUTLOOK_MAIL_CONNECTED] == true) SourceConnectionState.CONNECTED
                    else SourceConnectionState.DISCONNECTED),
                SourceStatus("naver_imap", "네이버 메일",
                    if (prefs[DataStoreKeys.NAVER_IMAP_CONNECTED] == true) SourceConnectionState.CONNECTED
                    else SourceConnectionState.DISCONNECTED),
                SourceStatus("google_calendar", "Google 캘린더",
                    if (prefs[DataStoreKeys.GOOGLE_CALENDAR_CONNECTED] == true) SourceConnectionState.CONNECTED
                    else SourceConnectionState.DISCONNECTED),
                SourceStatus("outlook_calendar", "Outlook 캘린더",
                    if (prefs[DataStoreKeys.OUTLOOK_CALENDAR_CONNECTED] == true) SourceConnectionState.CONNECTED
                    else SourceConnectionState.DISCONNECTED),
                // spec: ENR-008 — contacts pseudo-source
                SourceStatus("contacts", "연락처",
                    if (prefs[DataStoreKeys.CONTACTS_PERMISSION_GRANTED] == true) SourceConnectionState.CONNECTED
                    else SourceConnectionState.DISCONNECTED)
            )
            _uiState.value = SourceManagementUiState(sources = sources, isLoading = false)
        }
    }

    // spec: SMG-004 — disconnect: clear DataStore cursor + Keystore creds; preserve Room data
    fun disconnectSource(sourceId: String) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                when (sourceId) {
                    "voice" -> {
                        prefs.remove(DataStoreKeys.VOICE_SAF_URI)
                        prefs.remove(DataStoreKeys.CURSOR_VOICE)
                    }
                    "gmail" -> {
                        prefs[DataStoreKeys.GMAIL_CONNECTED] = false
                        prefs.remove(DataStoreKeys.CURSOR_GMAIL)
                        // Keystore token deletion handled by auth flow
                    }
                    "outlook_mail" -> {
                        prefs[DataStoreKeys.OUTLOOK_MAIL_CONNECTED] = false
                        prefs.remove(DataStoreKeys.CURSOR_OUTLOOK_MAIL)
                    }
                    "naver_imap" -> {
                        prefs[DataStoreKeys.NAVER_IMAP_CONNECTED] = false
                        prefs.remove(DataStoreKeys.CURSOR_NAVER_IMAP)
                    }
                    "google_calendar" -> {
                        prefs[DataStoreKeys.GOOGLE_CALENDAR_CONNECTED] = false
                        prefs.remove(DataStoreKeys.CURSOR_GOOGLE_CALENDAR)
                    }
                    "outlook_calendar" -> {
                        prefs[DataStoreKeys.OUTLOOK_CALENDAR_CONNECTED] = false
                        prefs.remove(DataStoreKeys.CURSOR_OUTLOOK_CALENDAR)
                    }
                }
            }
            loadSourceStatuses()
        }
    }

    // spec: SMG-005 — [지금 동기화]: per-source WorkManager enqueueUniqueWork
    fun triggerManualSync(sourceId: String) {
        viewModelScope.launch {
            when (sourceId) {
                "voice" -> {
                    // Voice is triggered via ING-011 foreground catch-up; manual = enqueue upload
                    UploadWorker.enqueue(workManager)
                }
                else -> {
                    // SP-4 will implement per-source WorkManager jobs
                    // For scaffold: enqueue generic upload worker
                    UploadWorker.enqueue(workManager)
                }
            }
        }
    }
}
