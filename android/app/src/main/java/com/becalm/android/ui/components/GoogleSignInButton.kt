package com.becalm.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.becalm.android.R

@Composable
public fun GoogleSignInButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactive = enabled && !loading
    OutlinedButton(
        onClick = { if (interactive) onClick() },
        enabled = interactive,
        modifier = modifier
            .height(48.dp)
            .testTag("google-sign-in-button")
            .semantics { contentDescription = text },
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, Color(0xFF747775)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color(0xFFFDFCF8),
            contentColor = Color(0xFF1F1F1F),
            disabledContainerColor = Color(0xFFFDFCF8),
            disabledContentColor = Color(0xFF7A766F),
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_google_g_mark),
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .alpha(if (interactive) 1f else 0.42f),
                )
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (interactive) Color(0xFF1F1F1F) else Color(0xFF7A766F),
                )
            }
        }
    }
}
