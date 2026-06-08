package com.becalm.android.ui.navigation

internal object DeepLinkNavigationPolicy {
    fun shouldDeferNavigation(pendingRoute: String, currentRoute: String?): Boolean =
        pendingRoute.isOAuthResultRoute() &&
            (currentRoute == null || currentRoute == BecalmRoute.Splash.path)

    fun shouldConsumeWithoutNavigation(pendingRoute: String, currentRoute: String?): Boolean =
        pendingRoute.isOAuthResultRoute() && currentRoute.isOnboardingRoute()

    private fun String.isOAuthResultRoute(): Boolean =
        startsWith("${BecalmRoute.SettingsSources.path}?sourceConnectionResult=")

    private fun String?.isOnboardingRoute(): Boolean =
        this?.startsWith("onboarding/") == true
}
