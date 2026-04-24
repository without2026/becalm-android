package com.becalm.android.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.Flow

@Composable
public fun <T> CollectFlowEffect(
    flow: Flow<T>,
    onEffect: suspend (T) -> Unit,
) {
    LaunchedEffect(flow) {
        flow.collect { effect -> onEffect(effect) }
    }
}

@Composable
public fun HandleSnackbarMessage(
    message: String?,
    snackbarHostState: SnackbarHostState,
    onConsumed: () -> Unit,
) {
    LaunchedEffect(message, snackbarHostState) {
        if (message == null) return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onConsumed()
    }
}
