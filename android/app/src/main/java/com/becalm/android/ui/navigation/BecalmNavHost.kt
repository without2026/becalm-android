package com.becalm.android.ui.navigation

// R8 IMPLEMENTATION — All screen composable calls wired.
// Previously every composable block contained a "// R7:" TODO placeholder.
// This file replaces every placeholder with the real screen composable call.
// Argument-extraction logic from R1 is preserved unchanged.

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.becalm.android.ui.auth.LoginScreen
import com.becalm.android.ui.auth.SplashScreen
import com.becalm.android.ui.auth.TermsScreen
import com.becalm.android.ui.commitments.CommitmentManagementScreen
import com.becalm.android.ui.onboarding.BatteryOptimizationScreen
import com.becalm.android.ui.onboarding.ColdSyncScreen
import com.becalm.android.ui.onboarding.ContactsPermissionScreen
import com.becalm.android.ui.onboarding.GoogleCalendarOAuthScreen
import com.becalm.android.ui.onboarding.GmailOAuthScreen
import com.becalm.android.ui.onboarding.ImapSetupScreen
import com.becalm.android.ui.onboarding.OutlookCalendarOAuthScreen
import com.becalm.android.ui.onboarding.OutlookMailOAuthScreen
import com.becalm.android.ui.onboarding.RecordingFolderScreen
import com.becalm.android.ui.persons.PersonDetailScreen
import com.becalm.android.ui.persons.PersonsScreen
import com.becalm.android.ui.persons.RawEventDetailSheet
import com.becalm.android.ui.persons.UnassignedEventsScreen
import com.becalm.android.ui.settings.SettingsScreen
import com.becalm.android.ui.sources.SourceDetailScreen
import com.becalm.android.ui.sources.SourcesListScreen
import com.becalm.android.ui.today.TodayTimelineScreen

/**
 * Root navigation host for the BeCalm Android app.
 *
 * ## R8 implementation state
 * Every route declared in `.spec/contracts/ui-map.yml` (version 1, 21 routes) is
 * registered and wired to its real screen composable. Argument-extraction logic
 * introduced in R1 is preserved; screen composable calls replace the R7 placeholders.
 *
 * ## Usage
 * ```kotlin
 * val navController = rememberNavController()
 * BecalmNavHost(
 *     navController = navController,
 *     startDestination = BecalmRoute.Splash.path,
 * )
 * ```
 *
 * @param navController The [NavHostController] that drives all back-stack operations.
 * @param startDestination The [BecalmRoute.path] string used as the initial destination.
 * @param modifier Optional [Modifier] applied to the [NavHost] container.
 */
@Composable
public fun BecalmNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {

        // ── Auth / Public ──────────────────────────────────────────────────────

        composable(route = BecalmRoute.Splash.path) {
            SplashScreen(navController = navController)
        }

        composable(route = BecalmRoute.Terms.path) {
            TermsScreen(navController = navController)
        }

        composable(route = BecalmRoute.Login.path) {
            LoginScreen(navController = navController)
        }

        // ── Onboarding ─────────────────────────────────────────────────────────

        composable(route = BecalmRoute.OnboardingRecordingFolder.path) {
            RecordingFolderScreen(navController = navController)
        }

        composable(route = BecalmRoute.OnboardingContacts.path) {
            ContactsPermissionScreen(navController = navController)
        }

        composable(route = BecalmRoute.OnboardingGmail.path) {
            GmailOAuthScreen(navController = navController)
        }

        composable(route = BecalmRoute.OnboardingOutlookMail.path) {
            OutlookMailOAuthScreen(navController = navController)
        }

        composable(route = BecalmRoute.OnboardingImap.path) {
            ImapSetupScreen(navController = navController)
        }

        composable(route = BecalmRoute.OnboardingGoogleCalendar.path) {
            GoogleCalendarOAuthScreen(navController = navController)
        }

        composable(route = BecalmRoute.OnboardingOutlookCalendar.path) {
            OutlookCalendarOAuthScreen(navController = navController)
        }

        composable(route = BecalmRoute.OnboardingBattery.path) {
            BatteryOptimizationScreen(navController = navController)
        }

        composable(route = BecalmRoute.OnboardingColdSync.path) {
            ColdSyncScreen(navController = navController)
        }

        // ── Main app — 3-tab bottom nav ────────────────────────────────────────

        composable(route = BecalmRoute.Today.path) {
            TodayTimelineScreen(navController = navController)
        }

        composable(route = BecalmRoute.Persons.path) {
            PersonsScreen(navController = navController)
        }

        // /persons/unassigned must be registered BEFORE /persons/{person_id} so that
        // the literal segment "unassigned" is matched first and is not consumed as a
        // person_id argument.
        composable(route = BecalmRoute.PersonsUnassigned.path) {
            UnassignedEventsScreen(navController = navController)
        }

        composable(
            route = BecalmRoute.PersonDetail.PATH,
            arguments = listOf(
                navArgument(BecalmNavArgs.PERSON_ID) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val personId = backStackEntry.arguments
                ?.getString(BecalmNavArgs.PERSON_ID)
                ?: return@composable
            PersonDetailScreen(navController = navController, personId = personId)
        }

        composable(
            route = BecalmRoute.RawEventDetail.PATH,
            arguments = listOf(
                navArgument(BecalmNavArgs.PERSON_ID) { type = NavType.StringType },
                navArgument(BecalmNavArgs.EVENT_ID) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val personId = backStackEntry.arguments
                ?.getString(BecalmNavArgs.PERSON_ID)
                ?: return@composable
            val eventId = backStackEntry.arguments
                ?.getString(BecalmNavArgs.EVENT_ID)
                ?: return@composable
            RawEventDetailSheet(navController = navController, personId = personId, eventId = eventId)
        }

        composable(route = BecalmRoute.Commitments.path) {
            CommitmentManagementScreen(navController = navController)
        }

        // ── Settings ───────────────────────────────────────────────────────────

        composable(route = BecalmRoute.Settings.path) {
            SettingsScreen(navController = navController)
        }

        composable(route = BecalmRoute.SettingsSources.path) {
            SourcesListScreen(navController = navController)
        }

        composable(
            route = BecalmRoute.SourceDetail.PATH,
            arguments = listOf(
                navArgument(BecalmNavArgs.SOURCE_ID) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val sourceId = backStackEntry.arguments
                ?.getString(BecalmNavArgs.SOURCE_ID)
                ?: return@composable
            SourceDetailScreen(navController = navController, sourceId = sourceId)
        }
    }
}
