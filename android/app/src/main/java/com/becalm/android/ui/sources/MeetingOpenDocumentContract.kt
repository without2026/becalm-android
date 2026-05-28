package com.becalm.android.ui.sources

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

public data class MeetingOpenDocumentRequest(
    val mimeTypes: Array<String>,
) {
    override fun equals(other: Any?): Boolean =
        other is MeetingOpenDocumentRequest &&
            mimeTypes.contentEquals(other.mimeTypes)

    override fun hashCode(): Int =
        mimeTypes.contentHashCode()
}

public class MeetingOpenDocumentContract : ActivityResultContract<MeetingOpenDocumentRequest, Uri?>() {
    override fun createIntent(context: Context, input: MeetingOpenDocumentRequest): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, input.mimeTypes)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
