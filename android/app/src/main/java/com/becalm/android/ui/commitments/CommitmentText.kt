package com.becalm.android.ui.commitments

import androidx.compose.runtime.Composable
import com.becalm.android.R
import com.becalm.android.domain.commitment.CommitmentEditValidator
import com.becalm.android.domain.commitment.CommitmentManualValidator
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.components.uiMessageStringResource

public typealias CommitmentText = UiMessage

@Composable
internal fun commitmentStringResource(text: CommitmentText): String =
    uiMessageStringResource(text)

internal fun manualValidationErrorText(error: CommitmentManualValidator.Error): CommitmentText =
    CommitmentText.resource(
        when (error) {
            CommitmentManualValidator.Error.TITLE_REQUIRED,
            CommitmentManualValidator.Error.TITLE_TOO_LONG,
            -> R.string.commitment_manual_error_title
            CommitmentManualValidator.Error.DIRECTION_INVALID -> R.string.commitment_manual_error_direction
            CommitmentManualValidator.Error.QUOTE_REQUIRED,
            CommitmentManualValidator.Error.QUOTE_TOO_LONG,
            -> R.string.commitment_manual_error_quote
            CommitmentManualValidator.Error.PERSON_REF_INVALID -> R.string.commitment_manual_error_person_ref
        },
    )

internal fun editValidationErrorText(error: CommitmentEditValidator.Error): CommitmentText =
    CommitmentText.resource(
        when (error) {
            CommitmentEditValidator.Error.TITLE_REQUIRED -> R.string.commitment_edit_error_title_required
            CommitmentEditValidator.Error.TITLE_TOO_LONG -> R.string.commitment_edit_error_title_too_long
            CommitmentEditValidator.Error.PERSON_REF_INVALID -> R.string.commitment_edit_error_person_ref_invalid
            CommitmentEditValidator.Error.DIRECTION_INVALID -> R.string.commitment_edit_error_direction_required
        },
    )
