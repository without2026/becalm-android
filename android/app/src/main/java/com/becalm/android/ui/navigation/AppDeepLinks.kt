package com.becalm.android.ui.navigation

import android.content.Intent
import android.net.Uri

public object AppDeepLinks {
    public const val PERSONS_URI: String = "becalm://persons"
    public const val PERSONS_UNASSIGNED_URI: String = "becalm://persons/unassigned"
    public const val OAUTH_COMPLETE_URI: String = "becalm://oauth-complete"

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
            // OAuth callback success is not the same thing as source sync success. Keep the
            // foreground source screen in place so its lifecycle refresh can recover the selected
            // provider without collapsing onboarding and settings into the same route.
            "oauth-complete" -> null
            "settings" -> when (uri.pathSegments) {
                emptyList<String>() -> BecalmRoute.Settings.path
                listOf("privacy") -> BecalmRoute.PrivacyManagement.path
                listOf("privacy", "consents") -> BecalmRoute.ConsentWithdraw.path
                listOf("privacy", "activity-log") -> BecalmRoute.ActivityLog.path
                listOf("sources") -> BecalmRoute.SettingsSources.path
                listOf("sources", "connect") -> BecalmRoute.SettingsSourceConnections.path
                listOf("sources", "contacts") -> BecalmRoute.ContactsSourceDetail.path
                listOf("sources", "contacts", "permission") -> BecalmRoute.SettingsContactsPermission.path
                else -> {
                    val segments = uri.pathSegments.orEmpty()
                    when {
                        segments.size == 2 && segments[0] == "sources" ->
                            BecalmRoute.SourceDetail(segments[1]).path
                        segments.size == 3 && segments[0] == "sources" && segments[1] == "connect" ->
                            BecalmRoute.SettingsSourceConnection(segments[2]).path
                        else -> null
                    }
                }
            }
            "persons" -> when (uri.pathSegments) {
                emptyList<String>() -> BecalmRoute.Persons.path
                listOf("unassigned") -> BecalmRoute.PersonsUnassigned.path
                else -> uri.pathSegments
                    ?.singleOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { BecalmRoute.PersonDetail(it).path }
            }
            else -> null
        }
    }
}
