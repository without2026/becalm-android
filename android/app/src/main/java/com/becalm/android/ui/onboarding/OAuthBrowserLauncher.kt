package com.becalm.android.ui.onboarding

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import javax.inject.Inject

public class OAuthBrowserLauncher @Inject constructor() {

    public fun launch(
        activity: Activity,
        authorizationUrl: String,
    ): OAuthBrowserLaunchResult {
        val uri = runCatching { Uri.parse(authorizationUrl) }.getOrNull()
            ?: return OAuthBrowserLaunchResult.Unavailable
        if (uri.scheme != "https") return OAuthBrowserLaunchResult.Unavailable
        val packageManager = activity.packageManager
        val browserPackage = packageManager.selectTrustedOAuthBrowserPackage(uri)
            ?: return OAuthBrowserLaunchResult.Unavailable
        val useCustomTabs = packageManager.supportsCustomTabs(browserPackage)
        return try {
            if (useCustomTabs) {
                val customTabsIntent = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                customTabsIntent.intent.setPackage(browserPackage)
                customTabsIntent.launchUrl(activity, uri)
            } else {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, uri)
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .setPackage(browserPackage),
                )
            }
            OAuthBrowserLaunchResult.Launched(
                packageName = browserPackage,
                customTabs = useCustomTabs,
            )
        } catch (_: RuntimeException) {
            OAuthBrowserLaunchResult.Unavailable
        }
    }

    private fun PackageManager.selectTrustedOAuthBrowserPackage(uri: Uri): String? {
        val viewPackages = queryBrowsableViewPackages(uri)
        val customTabsPackages = viewPackages
            .filter { packageName -> supportsCustomTabs(packageName) }
            .toSet()
        return TrustedOAuthBrowserPolicy.selectTrustedBrowserPackage(
            viewPackages = viewPackages,
            customTabsPackages = customTabsPackages,
        )
    }

    private fun PackageManager.queryBrowsableViewPackages(uri: Uri): Set<String> {
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
        return queryIntentActivitiesCompat(intent)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    private fun PackageManager.supportsCustomTabs(packageName: String): Boolean {
        val serviceIntent = Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION)
            .setPackage(packageName)
        return resolveServiceCompat(serviceIntent) != null
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.queryIntentActivitiesCompat(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }

    @Suppress("DEPRECATION")
    private fun PackageManager.resolveServiceCompat(intent: Intent): ResolveInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resolveService(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            resolveService(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }

}

internal object TrustedOAuthBrowserPolicy {
    private val trustedBrowserPackages = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.fenix.nightly",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.android.browser",
    )

    fun selectTrustedBrowserPackage(
        viewPackages: Set<String>,
        customTabsPackages: Set<String>,
    ): String? =
        trustedBrowserPackages.firstOrNull { it in customTabsPackages }
            ?: trustedBrowserPackages.firstOrNull { it in viewPackages }
}

public sealed interface OAuthBrowserLaunchResult {
    public data class Launched(
        val packageName: String,
        val customTabs: Boolean,
    ) : OAuthBrowserLaunchResult

    public data object Unavailable : OAuthBrowserLaunchResult
}
