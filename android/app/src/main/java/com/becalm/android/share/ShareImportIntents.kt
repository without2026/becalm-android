package com.becalm.android.share

import android.content.Intent
import android.net.Uri
import android.os.Build

internal object ShareImportIntents {
    fun singleImageUri(intent: Intent): Uri? {
        if (intent.action != Intent.ACTION_SEND) return null
        val type = intent.type?.lowercase().orEmpty()
        if (!type.startsWith("image/")) return null
        return streamUri(intent) ?: clipDataUri(intent)
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }

    private fun clipDataUri(intent: Intent): Uri? {
        val clipData = intent.clipData ?: return null
        if (clipData.itemCount != 1) return null
        return clipData.getItemAt(0).uri
    }
}
