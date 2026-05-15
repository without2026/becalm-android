package com.becalm.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

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
                GoogleGMark(
                    enabled = interactive,
                    modifier = Modifier.size(18.dp),
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

@Composable
private fun GoogleGMark(
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        fun markColor(enabledColor: Color): Color =
            if (enabled) enabledColor else Color(0xFF7A766F)
        val strokeWidth = size.minDimension * 0.16f
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Square)
        val inset = strokeWidth / 2f
        drawGoogleArc(
            color = markColor(Color(0xFF4285F4)),
            startAngle = -35f,
            sweepAngle = 95f,
            inset = inset,
            stroke = stroke,
        )
        drawGoogleArc(
            color = markColor(Color(0xFF34A853)),
            startAngle = 60f,
            sweepAngle = 95f,
            inset = inset,
            stroke = stroke,
        )
        drawGoogleArc(
            color = markColor(Color(0xFFFBBC05)),
            startAngle = 155f,
            sweepAngle = 72f,
            inset = inset,
            stroke = stroke,
        )
        drawGoogleArc(
            color = markColor(Color(0xFFEA4335)),
            startAngle = 227f,
            sweepAngle = 98f,
            inset = inset,
            stroke = stroke,
        )
        drawLine(
            color = markColor(Color(0xFF4285F4)),
            start = Offset(size.width * 0.54f, size.height * 0.50f),
            end = Offset(size.width * 0.94f, size.height * 0.50f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Square,
        )
        drawLine(
            color = markColor(Color(0xFF4285F4)),
            start = Offset(size.width * 0.78f, size.height * 0.50f),
            end = Offset(size.width * 0.78f, size.height * 0.66f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Square,
        )
    }
}

private fun DrawScope.drawGoogleArc(
    color: Color,
    startAngle: Float,
    sweepAngle: Float,
    inset: Float,
    stroke: Stroke,
) {
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = size.copy(
            width = size.width - stroke.width,
            height = size.height - stroke.width,
        ),
        style = stroke,
    )
}
