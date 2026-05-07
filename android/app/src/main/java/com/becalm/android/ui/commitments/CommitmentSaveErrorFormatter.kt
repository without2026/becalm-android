package com.becalm.android.ui.commitments

import com.becalm.android.R
import com.becalm.android.core.result.BecalmError

internal object CommitmentSaveErrorFormatter {
    val SUPERSEDE_SOURCE_NOT_FOUND: CommitmentText =
        CommitmentText.resource(R.string.commitment_save_error_supersede_source_not_found)

    fun format(error: BecalmError): CommitmentText = when (error) {
        is BecalmError.Unauthorized -> CommitmentText.resource(R.string.commitment_save_error_login_required)
        is BecalmError.NotFound -> CommitmentText.resource(R.string.commitment_save_error_deleted)
        is BecalmError.Validation -> CommitmentText.resource(R.string.commitment_save_error_validation)
        else -> CommitmentText.resource(R.string.commitment_save_error_generic)
    }
}
