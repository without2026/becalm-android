package com.becalm.android.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

// spec: ui-map.yml — 22 routes, 3-tab BottomNavigationBar
// Navigation decisions (onboarding vs main) are driven by DataStore state read in SplashScreen.

@Composable
fun BeCalmApp() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = BeCalmRoute.Splash.route
    ) {
        // spec: AUTH — public routes
        composable(BeCalmRoute.Splash.route) {
            // SplashScreen — placeholder; full impl in SP-2
            PlaceholderScreen("SplashScreen")
        }
        composable(BeCalmRoute.Terms.route) {
            PlaceholderScreen("TermsScreen")
        }
        composable(BeCalmRoute.Login.route) {
            PlaceholderScreen("LoginScreen")
        }
        // spec: ONB-001..ONB-008 + ONB-CONTACTS — onboarding routes
        composable(BeCalmRoute.OnboardingRecordingFolder.route) {
            PlaceholderScreen("RecordingFolderScreen")
        }
        composable(BeCalmRoute.OnboardingContacts.route) {
            PlaceholderScreen("ContactsPermissionScreen")
        }
        composable(BeCalmRoute.OnboardingGmail.route) {
            PlaceholderScreen("GmailOAuthScreen")
        }
        composable(BeCalmRoute.OnboardingOutlookMail.route) {
            PlaceholderScreen("OutlookMailOAuthScreen")
        }
        composable(BeCalmRoute.OnboardingImap.route) {
            PlaceholderScreen("ImapSetupScreen")
        }
        composable(BeCalmRoute.OnboardingGoogleCalendar.route) {
            PlaceholderScreen("GoogleCalendarOAuthScreen")
        }
        composable(BeCalmRoute.OnboardingOutlookCalendar.route) {
            PlaceholderScreen("OutlookCalendarOAuthScreen")
        }
        composable(BeCalmRoute.OnboardingBattery.route) {
            PlaceholderScreen("BatteryOptimizationScreen")
        }
        composable(BeCalmRoute.OnboardingColdSync.route) {
            PlaceholderScreen("ColdSyncScreen")
        }
        // spec: TDY-001..TDY-010 — Tab 1
        composable(BeCalmRoute.Today.route) {
            PlaceholderScreen("TodayTimelineScreen")
        }
        // spec: SRC-001..SRC-008 — Tab 2
        composable(BeCalmRoute.Persons.route) {
            PlaceholderScreen("PersonsScreen")
        }
        composable(BeCalmRoute.PersonDetail.route) {
            PlaceholderScreen("PersonDetailScreen")
        }
        composable(BeCalmRoute.RawEventDetail.route) {
            PlaceholderScreen("RawEventDetailSheet")
        }
        composable(BeCalmRoute.UnassignedEvents.route) {
            PlaceholderScreen("UnassignedEventsScreen")
        }
        // spec: CMT-001..CMT-010 — Tab 3
        composable(BeCalmRoute.Commitments.route) {
            PlaceholderScreen("CommitmentManagementScreen")
        }
        // spec: SMG-001..SMG-005 — Settings
        composable(BeCalmRoute.Settings.route) {
            PlaceholderScreen("SettingsScreen")
        }
        composable(BeCalmRoute.SourcesList.route) {
            PlaceholderScreen("SourcesListScreen")
        }
        composable(BeCalmRoute.SourceDetail.route) {
            PlaceholderScreen("SourceDetailScreen")
        }
    }
}

// Route definitions matching ui-map.yml
sealed class BeCalmRoute(val route: String) {
    object Splash : BeCalmRoute("/splash")
    object Terms : BeCalmRoute("/terms")
    object Login : BeCalmRoute("/login")
    object OnboardingRecordingFolder : BeCalmRoute("/onboarding/recording-folder")
    object OnboardingContacts : BeCalmRoute("/onboarding/contacts")
    object OnboardingGmail : BeCalmRoute("/onboarding/gmail")
    object OnboardingOutlookMail : BeCalmRoute("/onboarding/outlook-mail")
    object OnboardingImap : BeCalmRoute("/onboarding/imap")
    object OnboardingGoogleCalendar : BeCalmRoute("/onboarding/google-calendar")
    object OnboardingOutlookCalendar : BeCalmRoute("/onboarding/outlook-calendar")
    object OnboardingBattery : BeCalmRoute("/onboarding/battery")
    object OnboardingColdSync : BeCalmRoute("/onboarding/cold-sync")
    object Today : BeCalmRoute("/today")
    object Persons : BeCalmRoute("/persons")
    object PersonDetail : BeCalmRoute("/persons/{person_id}")
    object RawEventDetail : BeCalmRoute("/persons/{person_id}/events/{event_id}")
    object UnassignedEvents : BeCalmRoute("/persons/unassigned")
    object Commitments : BeCalmRoute("/commitments")
    object Settings : BeCalmRoute("/settings")
    object SourcesList : BeCalmRoute("/settings/sources")
    object SourceDetail : BeCalmRoute("/settings/sources/{source_id}")
}
