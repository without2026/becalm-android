package com.becalm.android.ui.sources

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract

public data class MeetingOpenDocumentRequest(
    val mimeTypes: Array<String>,
    val initialUri: Uri?,
) {
    override fun equals(other: Any?): Boolean =
        other is MeetingOpenDocumentRequest &&
            mimeTypes.contentEquals(other.mimeTypes) &&
            initialUri == other.initialUri

    override fun hashCode(): Int =
        31 * mimeTypes.contentHashCode() + (initialUri?.hashCode() ?: 0)
}

public class MeetingOpenDocumentContract : ActivityResultContract<MeetingOpenDocumentRequest, Uri?>() {
    override fun createIntent(context: Context, input: MeetingOpenDocumentRequest): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, input.mimeTypes)
            input.initialUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
