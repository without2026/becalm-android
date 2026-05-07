package com.becalm.android.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Resource-backed UI message for snackbar/error boundaries.
 *
 * ViewModels keep stable resource IDs instead of committing to one language.
 */
public data class UiMessage(
    @StringRes val resId: Int,
    val args: List<String> = emptyList(),
) {
    public companion object {
        public fun resource(@StringRes resId: Int, vararg args: String): UiMessage =
            UiMessage(resId = resId, args = args.toList())
    }
}

@Composable
public fun uiMessageStringResource(message: UiMessage): String =
    stringResource(message.resId, *message.args.toTypedArray())
