package com.becalm.android.ui.evidence

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.R
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.repository.SourceImportRepository
import com.becalm.android.ui.components.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public data class EvidenceImportUiState(
    val message: UiMessage? = null,
)

@HiltViewModel
public class EvidenceImportViewModel @Inject constructor(
    private val sourceImportRepository: SourceImportRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(EvidenceImportUiState())
    public val state: StateFlow<EvidenceImportUiState> = _state.asStateFlow()

    public fun onMessageScreenshotSelected(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _state.value = when (sourceImportRepository.importMessageScreenshot(uri)) {
                is BecalmResult.Success -> EvidenceImportUiState(UiMessage.resource(R.string.evidence_import_success))
                is BecalmResult.Failure -> EvidenceImportUiState(UiMessage.resource(R.string.evidence_import_error_message_screenshot))
            }
        }
    }

    public fun onMeetingAudioSelected(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _state.value = when (sourceImportRepository.importMeetingAudio(uri)) {
                is BecalmResult.Success -> EvidenceImportUiState(UiMessage.resource(R.string.evidence_import_success))
                is BecalmResult.Failure -> EvidenceImportUiState(UiMessage.resource(R.string.source_detail_error_meeting_audio_import_failed))
            }
        }
    }

    public fun onMeetingTranscriptSelected(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _state.value = when (sourceImportRepository.importMeetingTranscript(uri)) {
                is BecalmResult.Success -> EvidenceImportUiState(UiMessage.resource(R.string.evidence_import_success))
                is BecalmResult.Failure -> EvidenceImportUiState(UiMessage.resource(R.string.source_detail_error_meeting_transcript_import_failed))
            }
        }
    }

    public fun onManualTextSubmitted(text: String) {
        viewModelScope.launch {
            _state.value = when (sourceImportRepository.importManualText(text)) {
                is BecalmResult.Success -> EvidenceImportUiState(UiMessage.resource(R.string.evidence_import_success))
                is BecalmResult.Failure -> EvidenceImportUiState(UiMessage.resource(R.string.evidence_import_error_manual_text))
            }
        }
    }

    public fun onMessageShown() {
        _state.value = EvidenceImportUiState()
    }
}
