package com.becalm.android.ui.navigation

// R1 SCAFFOLD — ALL SCREEN COMPOSABLE CALLS ARE COMMENTED OUT.
// This file declares the complete NavHost skeleton matching ui-map.yml v1.
// Each composable block contains the argument-extraction logic that R7 will need;
// the actual screen composable invocation is marked with a "// R7:" TODO comment.
// R7 removes those TODO markers and writes the real screen calls once the
// composable implementations land.
//
// Expected compile state at R1 ship: CLEAN (no phantom import errors because
// screen references are inside comments only).

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

/**
 * Root navigation host for the BeCalm Android app.
 *
 * ## R1 scaffold state
 * Every route declared in `.spec/contracts/ui-map.yml` (version 1, 21 routes) is
 * registered here. The actual screen composables are **not yet implemented** — they
 * land in R7. Until then, each `composable { }` block contains only argument-extraction
 * logic and a `// R7:` placeholder comment showing exactly which composable R7 must
 * drop in. Do not remove argument-extraction logic while filling in R7 screens.
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
            // R7: SplashScreen(navController = navController)
        }

        composable(route = BecalmRoute.Terms.path) {
            // R7: TermsScreen(navController = navController)
        }

        composable(route = BecalmRoute.Login.path) {
            // R7: LoginScreen(navController = navController)
        }

        // ── Onboarding ─────────────────────────────────────────────────────────

        composable(route = BecalmRoute.OnboardingRecordingFolder.path) {
            // R7: RecordingFolderScreen(navController = navController)
        }

        composable(route = BecalmRoute.OnboardingContacts.path) {
            // R7: ContactsPermissionScreen(navController = navController)
        }

        composable(route = BecalmRoute.OnboardingGmail.path) {
            // R7: GmailOAuthScreen(navController = navController)
        }

        composable(route = BecalmRoute.OnboardingOutlookMail.path) {
            // R7: OutlookMailOAuthScreen(navController = navController)
        }

        composable(route = BecalmRoute.OnboardingImap.path) {
            // R7: ImapSetupScreen(navController = navController)
        }

        composable(route = BecalmRoute.OnboardingGoogleCalendar.path) {
            // R7: GoogleCalendarOAuthScreen(navController = navController)
        }

        composable(route = BecalmRoute.OnboardingOutlookCalendar.path) {
            // R7: OutlookCalendarOAuthScreen(navController = navController)
        }

        composable(route = BecalmRoute.OnboardingBattery.path) {
            // R7: BatteryOptimizationScreen(navController = navController)
        }

        composable(route = BecalmRoute.OnboardingColdSync.path) {
            // R7: ColdSyncScreen(navController = navController)
        }

        // ── Main app — 3-tab bottom nav ────────────────────────────────────────

        composable(route = BecalmRoute.Today.path) {
            // R7: TodayTimelineScreen(navController = navController)
        }

        composable(route = BecalmRoute.Persons.path) {
            // R7: PersonsScreen(navController = navController)
        }

        // /persons/unassigned must be registered BEFORE /persons/{person_id} so that
        // the literal segment "unassigned" is matched first and is not consumed as a
        // person_id argument.
        composable(route = BecalmRoute.PersonsUnassigned.path) {
            // R7: UnassignedEventsScreen(navController = navController)
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
            // R7: PersonDetailScreen(navController = navController, personId = personId)
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
            // R7: RawEventDetailSheet(navController = navController, personId = personId, eventId = eventId)
        }

        composable(route = BecalmRoute.Commitments.path) {
            // R7: CommitmentManagementScreen(navController = navController)
        }

        // ── Settings ───────────────────────────────────────────────────────────

        composable(route = BecalmRoute.Settings.path) {
            // R7: SettingsScreen(navController = navController)
        }

        composable(route = BecalmRoute.SettingsSources.path) {
            // R7: SourcesListScreen(navController = navController)
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
            // R7: SourceDetailScreen(navController = navController, sourceId = sourceId)
        }
    }
}
