package com.becalm.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.ProcessingPhase
import com.becalm.android.data.repository.ProcessingSourceState
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.isActive
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.theme.glassPanel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Immutable
public data class ProcessingStatusUiState(
    val rows: List<ProcessingStatusRow> = emptyList(),
)

@Immutable
public data class ProcessingStatusRow(
    val sourceType: String,
    val title: String,
    val phase: ProcessingPhase,
    val label: String,
    val itemCount: Int,
    val message: String?,
    val updatedAt: Instant?,
)

@HiltViewModel
public class ProcessingStatusViewModel @Inject constructor(
    processingStatusRepository: ProcessingStatusRepository,
) : ViewModel() {
    public val state: StateFlow<ProcessingStatusUiState> =
        processingStatusRepository.observeAll()
            .map { states ->
                ProcessingStatusUiState(
                    rows = states.map(::toRow),
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ProcessingStatusUiState(),
            )

    private fun toRow(state: ProcessingSourceState): ProcessingStatusRow =
        ProcessingStatusRow(
            sourceType = state.sourceType,
            title = sourceTitle(state.sourceType),
            phase = state.phase,
            label = phaseLabel(state.phase),
            itemCount = state.itemCount,
            message = state.message,
            updatedAt = state.updatedAt,
        )
}

@Composable
public fun ProcessingStatusScreen(
    navController: NavHostController,
    viewModel: ProcessingStatusViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProcessingStatusContent(
        state = state,
        onBack = navController::popBackStack,
    )
}

@Composable
internal fun ProcessingStatusContent(
    state: ProcessingStatusUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BecalmScaffold(
        modifier = modifier,
        title = stringResource(R.string.processing_status_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.rows, key = { it.sourceType }) { row ->
                ProcessingStatusItem(row)
            }
        }
    }
}

@Composable
private fun ProcessingStatusItem(row: ProcessingStatusRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(MaterialTheme.shapes.medium)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhaseIndicator(row.phase)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = row.statusText(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PhaseIndicator(phase: ProcessingPhase) {
    if (phase.isActive) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
        )
    } else {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun ProcessingStatusRow.statusText(): String {
    val countText = if (itemCount > 0) " · $itemCount item(s)" else ""
    val messageText = message?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    val timeText = updatedAt?.let { " · ${formatTimeHHmm(it)}" }.orEmpty()
    return label + countText + messageText + timeText
}

private fun sourceTitle(sourceType: String): String = when (sourceType) {
    SourceType.VOICE -> "Voice recordings"
    SourceType.CALL_RECORDING -> "Call recordings"
    SourceType.NAVER_IMAP -> "Naver Mail"
    SourceType.DAUM_IMAP -> "Daum Mail"
    SourceType.GMAIL -> "Gmail"
    SourceType.OUTLOOK_MAIL -> "Outlook Mail"
    SourceType.GOOGLE_CALENDAR -> "Google Calendar"
    SourceType.OUTLOOK_CALENDAR -> "Outlook Calendar"
    else -> sourceType
}

private fun phaseLabel(phase: ProcessingPhase): String = when (phase) {
    ProcessingPhase.IDLE -> "No recent activity"
    ProcessingPhase.SCANNING -> "Scanning for new items"
    ProcessingPhase.NEW_ITEMS -> "New items found"
    ProcessingPhase.GEMINI -> "Gemini extraction in progress"
    ProcessingPhase.UPLOADING -> "Uploading and extracting"
    ProcessingPhase.NO_NEW_ITEMS -> "No new items"
    ProcessingPhase.SYNCED -> "Processing complete"
    ProcessingPhase.BLOCKED -> "Blocked"
    ProcessingPhase.ERROR -> "Error"
}

private fun formatTimeHHmm(at: Instant): String {
    val local = at.toLocalDateTime(TimeZone.currentSystemDefault())
    val hh = local.hour.toString().padStart(2, '0')
    val mm = local.minute.toString().padStart(2, '0')
    return "$hh:$mm"
}
