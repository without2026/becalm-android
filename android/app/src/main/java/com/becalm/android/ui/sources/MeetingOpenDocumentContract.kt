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

internal data class MeetingOpenDocumentIntentConfig(
    val action: String,
    val category: String,
    val type: String,
    val mimeTypes: Array<String>,
    val includesInitialUri: Boolean,
    val grantsPersistableUriPermission: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is MeetingOpenDocumentIntentConfig &&
            action == other.action &&
            category == other.category &&
            type == other.type &&
            mimeTypes.contentEquals(other.mimeTypes) &&
            includesInitialUri == other.includesInitialUri &&
            grantsPersistableUriPermission == other.grantsPersistableUriPermission

    override fun hashCode(): Int {
        var result = action.hashCode()
        result = 31 * result + category.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + mimeTypes.contentHashCode()
        result = 31 * result + includesInitialUri.hashCode()
        result = 31 * result + grantsPersistableUriPermission.hashCode()
        return result
    }
}

internal fun MeetingOpenDocumentRequest.toOpenDocumentIntentConfig(): MeetingOpenDocumentIntentConfig =
    MeetingOpenDocumentIntentConfig(
        action = Intent.ACTION_OPEN_DOCUMENT,
        category = Intent.CATEGORY_OPENABLE,
        type = "*/*",
        mimeTypes = mimeTypes,
        includesInitialUri = false,
        grantsPersistableUriPermission = false,
    )

public class MeetingOpenDocumentContract : ActivityResultContract<MeetingOpenDocumentRequest, Uri?>() {
    override fun createIntent(context: Context, input: MeetingOpenDocumentRequest): Intent {
        val config = input.toOpenDocumentIntentConfig()
        return Intent(config.action).apply {
            addCategory(config.category)
            type = config.type
            putExtra(Intent.EXTRA_MIME_TYPES, config.mimeTypes)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
