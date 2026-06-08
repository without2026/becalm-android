package com.becalm.android.ui.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.EvidenceCard
import com.becalm.android.ui.components.SheetCloseRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun PersonActionEvidenceDialog(
    detail: PersonActionEvidenceDetailUi,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("person-action-evidence-sheet"),
    ) {
        SheetCloseRow(onClose = onDismiss)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.commitment_action_evidence_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = detail.originalTitle?.takeIf { it.isNotBlank() } ?: detail.evidenceLabel,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            evidenceMeta(detail)?.let { meta ->
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            EvidenceCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            ) {
                PersonActionEvidenceTextBlock(
                    label = stringResource(R.string.commitment_action_evidence_why),
                    body = detail.whyText.ifBlank { detail.evidenceLabel },
                )
            }
            detail.metadataText?.takeIf { it.isNotBlank() }?.let { metadata ->
                EvidenceCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    PersonActionEvidenceTextBlock(
                        label = stringResource(R.string.commitment_action_evidence_metadata),
                        body = metadata,
                    )
                }
            }
            EvidenceCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            ) {
                PersonActionEvidenceTextBlock(
                    label = stringResource(R.string.commitment_action_evidence_original),
                    body = detail.localOriginalBodyText(),
                )
            }
            BecalmButton(
                text = stringResource(R.string.commitment_action_evidence_close),
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
                variant = BecalmButtonVariant.Text,
            )
        }
    }
}

@Composable
private fun PersonActionEvidenceDetailUi.localOriginalBodyText(): String =
    when {
        originalIsLocal && originalTruncated ->
            originalText + "\n\n" + stringResource(R.string.commitment_action_evidence_original_truncated)
        originalIsLocal ->
            originalText
        else ->
            stringResource(R.string.commitment_action_evidence_original_missing_local)
    }

@Composable
private fun PersonActionEvidenceTextBlock(
    label: String,
    body: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun evidenceMeta(detail: PersonActionEvidenceDetailUi): String? =
    listOfNotNull(
        detail.sourceType?.takeIf { it.isNotBlank() },
        detail.evidenceLabel.takeIf { it.isNotBlank() },
    )
        .distinct()
        .joinToString(" · ")
        .takeIf { it.isNotBlank() }
