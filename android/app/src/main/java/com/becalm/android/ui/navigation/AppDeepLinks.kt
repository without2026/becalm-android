package com.becalm.android.ui.navigation

import android.content.Intent
import android.net.Uri
import com.becalm.android.BuildConfig

public object AppDeepLinks {
    public const val PERSONS_URI: String = "becalm://persons"
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
            "persons" -> when (uri.pathSegments) {
                emptyList<String>() -> BecalmRoute.Persons.path
                listOf("unassigned") -> BecalmRoute.PersonsUnassigned.path
                else -> null
            }
            "qa" -> if (BuildConfig.DEBUG && uri.pathSegments == listOf("route")) {
                uri.getQueryParameter("path")?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            else -> null
        }
    }
}
