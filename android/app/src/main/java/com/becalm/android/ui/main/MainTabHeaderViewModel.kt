package com.becalm.android.ui.main

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Immutable
public data class MainTabHeaderState(
    val sourceStatus: Map<String, SourceStatusUi> = emptyMap(),
    val overallSyncing: Boolean = false,
    val overall: OverallSyncState = OverallSyncState.Idle,
)

@HiltViewModel
public class MainTabHeaderViewModel @Inject constructor(
    sourceStatusRepository: SourceStatusRepository,
) : ViewModel() {
    public val state: StateFlow<MainTabHeaderState> = sourceStatusRepository.observeAll()
        .map(::buildHeaderState)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainTabHeaderState(),
        )

    private fun buildHeaderState(statuses: List<SourceStatus>): MainTabHeaderState =
        MainTabHeaderState(
            sourceStatus = buildSourceStatusUiMap(statuses),
            overallSyncing = statuses.any { it.status == SourceConnectionStatus.SYNCING },
            overall = deriveOverallState(statuses),
        )
}
