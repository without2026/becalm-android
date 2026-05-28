package com.becalm.android.share

import android.content.Context
import android.content.Intent
import com.becalm.android.MainActivity

internal object ShareImportNavigation {
    private const val EXTRA_SHARE_IMPORT_COMPLETED = "com.becalm.android.extra.SHARE_IMPORT_COMPLETED"

    fun mainAppIntent(
        context: Context,
        route: String?,
        importCompleted: Boolean,
    ): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                if (!route.isNullOrBlank()) {
                    putExtra(MainActivity.EXTRA_START_ROUTE, route)
                }
                if (importCompleted) {
                    putExtra(EXTRA_SHARE_IMPORT_COMPLETED, true)
                }
            }

    fun hasCompletedImport(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_SHARE_IMPORT_COMPLETED, false) == true
}
