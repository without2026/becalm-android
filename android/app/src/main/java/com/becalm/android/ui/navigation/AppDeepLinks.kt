package com.becalm.android.ui.navigation

import android.content.Intent
import android.net.Uri

public object AppDeepLinks {
    public const val PERSONS_UNASSIGNED_URI: String = "becalm://persons/unassigned"

    public fun routeFrom(intent: Intent): String? {
        if (intent.action != Intent.ACTION_VIEW) return null
        return routeFrom(intent.data ?: return null)
    }

    public fun routeFrom(uri: Uri): String? {
        if (uri.scheme != "becalm") return null
        return when (uri.host) {
            "commitments" -> uri.pathSegments
                ?.lastOrNull { it.isNotBlank() }
                ?.let { BecalmRoute.CommitmentDetail(it).path }
            "persons" -> if (uri.pathSegments == listOf("unassigned")) {
                BecalmRoute.PersonsUnassigned.path
            } else {
                null
            }
            else -> null
        }
    }
}
