/**
 * Trailing-aligned close icon for ModalBottomSheet content.
 *
 * Material's ModalBottomSheet provides a `dragHandle` slot and supports
 * swipe-down dismissal, but those affordances are easy to miss for the 50s
 * persona who is not yet fluent in bottom-sheet conventions (impeccable
 * critique R4 P3). An explicit `×` IconButton at the trailing edge gives
 * older users a discoverable exit, focus targets the close action for DeX
 * keyboard navigation, and does not interfere with the swipe-down gesture
 * familiar to the 30s persona.
 */
package com.becalm.android.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.ui.theme.becalmFocusRing

@Composable
public fun SheetCloseRow(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier.becalmFocusRing(MaterialTheme.shapes.small, source),
            interactionSource = source,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.action_close),
            )
        }
    }
}
