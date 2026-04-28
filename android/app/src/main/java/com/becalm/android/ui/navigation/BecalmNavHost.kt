package com.becalm.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.becalm.android.ui.auth.LoginScreen
import com.becalm.android.ui.auth.SplashScreen
import com.becalm.android.ui.auth.TermsScreen
import com.becalm.android.ui.commitments.CommitmentCreateSheet
import com.becalm.android.ui.commitments.CommitmentDetailSheet
import com.becalm.android.ui.commitments.CommitmentEditSheet
import com.becalm.android.ui.commitments.CommitmentManagementScreen
import com.becalm.android.ui.onboarding.BatteryOptimizationScreen
import com.becalm.android.ui.onboarding.ColdSyncScreen
import com.becalm.android.ui.onboarding.ContactsPermissionScreen
import com.becalm.android.ui.onboarding.PipaThirdPartyConsentScreen
import com.becalm.android.ui.onboarding.GoogleCalendarOAuthScreen
import com.becalm.android.ui.onboarding.GmailOAuthScreen
import com.becalm.android.ui.onboarding.ImapSetupScreen
import com.becalm.android.ui.onboarding.NotificationPermissionScreen
import com.becalm.android.ui.onboarding.OnboardingEmailPipaConsentScreen
import com.becalm.android.ui.onboarding.OutlookCalendarOAuthScreen
import com.becalm.android.ui.onboarding.OutlookMailOAuthScreen
import com.becalm.android.ui.onboarding.RecordingFolderScreen
import com.becalm.android.ui.persons.PersonDetailScreen
import com.becalm.android.ui.persons.PersonsScreen
import com.becalm.android.ui.persons.RawEventDetailSheet
import com.becalm.android.ui.persons.UnassignedEventsScreen
import com.becalm.android.ui.settings.AccountDeletionScreen
import com.becalm.android.ui.settings.ActivityLogScreen
import com.becalm.android.ui.settings.ConsentWithdrawScreen
import com.becalm.android.ui.settings.PrivacyManagementScreen
import com.becalm.android.ui.settings.ProcessingPauseScreen
import com.becalm.android.ui.settings.ProcessingStatusScreen
import com.becalm.android.ui.settings.SettingsScreen
import com.becalm.android.ui.sources.ContactsSourceDetailScreen
import com.becalm.android.ui.sources.SourceDetailScreen
import com.becalm.android.ui.sources.SourcesListScreen
import com.becalm.android.ui.today.TodayTimelineScreen

/**
 * Test hook for replacing individual destinations with lightweight composables.
 *
 * Production call sites should use the default empty map. Instrumentation tests can
 * override a destination by its registered route string (e.g. [BecalmRoute.PersonDetail.PATH])
 * so route matching / arg parsing can be verified without booting Hilt-backed screens.
 */
public typealias BecalmNavHostOverride = @Composable (NavBackStackEntry) -> Unit

/**
 * Returns the named string argument from this back-stack entry, or `null` if absent.
 *
 * Used internally to reduce boilerplate in parameterised [composable] blocks.
 */
private fun NavBackStackEntry.stringArg(key: String): String? =
    arguments?.getString(key)

/**
 * Root navigation host for the BeCalm Android app.
 *
 * Every route declared in `.spec/contracts/ui-map.yml` (version 1, 21 routes) is
 * registered and wired to its screen composable.
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
    routeOverrides: Map<String, BecalmNavHostOverride> = emptyMap(),
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {

        // ── Auth / Public ──────────────────────────────────────────────────────

        composable(route = BecalmRoute.Splash.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.Splash.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                SplashScreen(navController = navController)
            }
        }

        composable(route = BecalmRoute.Terms.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.Terms.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                TermsScreen(navController = navController)
            }
        }

        composable(route = BecalmRoute.Login.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.Login.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                LoginScreen(navController = navController)
            }
        }

        // ── Onboarding ─────────────────────────────────────────────────────────

        // ONB-PIPA: step 3 of 12 — shown after login, before recording-folder.
        // [동의] → recording-folder; [동의 안 함] → contacts (recording-folder skipped).
        composable(route = BecalmRoute.OnboardingPipaConsent.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.OnboardingPipaConsent.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                PipaThirdPartyConsentScreen(
                    onConsented = {
                        navController.navigate(BecalmRoute.OnboardingRecordingFolder.path)
                    },
                    onDeclined = {
                        navController.navigate(BecalmRoute.OnboardingContacts.path)
                    },
                )
            }
        }

        composable(route = BecalmRoute.OnboardingRecordingFolder.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.OnboardingRecordingFolder.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                RecordingFolderScreen(navController = navController)
            }
        }

        composable(route = BecalmRoute.OnboardingContacts.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.OnboardingContacts.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                ContactsPermissionScreen(navController = navController)
            }
        }

        composable(
            route = BecalmRoute.OnboardingEmailPipa.PATH,
            arguments = listOf(
                navArgument(BecalmRoute.OnboardingEmailPipa.ARG_PROVIDER) {
                    type = NavType.StringType
                },
            ),
        ) { entry ->
            val override = routeOverrides[BecalmRoute.OnboardingEmailPipa.PATH]
            if (override != null) {
                override(entry)
            } else {
                val slug = entry.stringArg(BecalmRoute.OnboardingEmailPipa.ARG_PROVIDER) ?: ""
                OnboardingEmailPipaConsentScreen(
                    providerSlug = slug,
                    navController = navController,
                )
            }
        }

        composable(route = BecalmRoute.OnboardingGmail.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.OnboardingGmail.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                GmailOAuthScreen(navController = navController)
            }
        }

        composable(route = BecalmRoute.OnboardingOutlookMail.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.OnboardingOutlookMail.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                OutlookMailOAuthScreen(navController = navController)
            }
        }

        composable(route = BecalmRoute.OnboardingImap.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.OnboardingImap.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                ImapSetupScreen(navController = navController)
            }
        }

        composable(route = BecalmRoute.OnboardingGoogleCalendar.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.OnboardingGoogleCalendar.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                GoogleCalendarOAuthScreen(navController = navController)
            }
        }

        composable(route = BecalmRoute.OnboardingOutlookCalendar.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.OnboardingOutlookCalendar.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                OutlookCalendarOAuthScreen(navController = navController)
            }
        }

        composable(route = BecalmRoute.OnboardingNotificationPerm.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.OnboardingNotificationPerm.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                NotificationPermissionScreen(navController = navController)
            }
        }

        composable(route = BecalmRoute.OnboardingBattery.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.OnboardingBattery.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                BatteryOptimizationScreen(navController = navController)
            }
        }

        composable(route = BecalmRoute.OnboardingColdSync.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.OnboardingColdSync.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                ColdSyncScreen(navController = navController)
            }
        }

        // ── Main app — 3-tab bottom nav ────────────────────────────────────────

        composable(route = BecalmRoute.Today.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.Today.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                TodayTimelineScreen(navController = navController)
            }
        }

        composable(route = BecalmRoute.Persons.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.Persons.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                PersonsScreen(navController = navController)
            }
        }

        // /persons/unassigned must be registered BEFORE /persons/{person_id} so that
        // the literal segment "unassigned" is matched first and is not consumed as a
        // person_id argument.
        composable(route = BecalmRoute.PersonsUnassigned.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.PersonsUnassigned.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                UnassignedEventsScreen(navController = navController)
            }
        }

        composable(
            route = BecalmRoute.PersonDetail.PATH,
            arguments = listOf(
                navArgument(BecalmNavArgs.PERSON_ID) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.PersonDetail.PATH]
            if (override != null) {
                override(backStackEntry)
            } else {
                val personId = backStackEntry.stringArg(BecalmNavArgs.PERSON_ID)
                    ?: return@composable
                PersonDetailScreen(navController = navController, personId = personId)
            }
        }

        composable(
            route = BecalmRoute.RawEventDetail.PATH,
            arguments = listOf(
                navArgument(BecalmNavArgs.PERSON_ID) { type = NavType.StringType },
                navArgument(BecalmNavArgs.EVENT_ID) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.RawEventDetail.PATH]
            if (override != null) {
                override(backStackEntry)
            } else {
                val personId = backStackEntry.stringArg(BecalmNavArgs.PERSON_ID)
                    ?: return@composable
                val eventId = backStackEntry.stringArg(BecalmNavArgs.EVENT_ID)
                    ?: return@composable
                RawEventDetailSheet(navController = navController, personId = personId, eventId = eventId)
            }
        }

        composable(route = BecalmRoute.Commitments.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.Commitments.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                CommitmentManagementScreen(
                    onOpenDetail = { id ->
                        navController.navigate(BecalmRoute.CommitmentDetail(id).path)
                    },
                    onOpenCreate = {
                        navController.navigate(BecalmRoute.CommitmentCreate(null).path)
                    },
                )
            }
        }

        // MAN-001..006 + EDIT-007: manual-create / supersede-create sheet.
        // `supersedeOf` is declared nullable with defaultValue=null so the
        // plain FAB navigation path (`commitments/new`) matches the same
        // destination as the supersede path (`commitments/new?supersedeOf=...`).
        composable(
            route = BecalmRoute.CommitmentCreate.PATH,
            arguments = listOf(
                navArgument(BecalmRoute.CommitmentCreate.ARG_SUPERSEDE_OF) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.CommitmentCreate.PATH]
            if (override != null) {
                override(backStackEntry)
            } else {
                val supersedeOf = backStackEntry.stringArg(BecalmRoute.CommitmentCreate.ARG_SUPERSEDE_OF)
                CommitmentCreateSheet(
                    supersedeOf = supersedeOf,
                    onDismiss = { navController.popBackStack() },
                )
            }
        }

        composable(
            route = BecalmRoute.CommitmentDetail.PATH,
            arguments = listOf(
                navArgument(BecalmRoute.CommitmentDetail.ARG_ID) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.CommitmentDetail.PATH]
            if (override != null) {
                override(backStackEntry)
            } else {
                val id = backStackEntry.stringArg(BecalmRoute.CommitmentDetail.ARG_ID).orEmpty()
                CommitmentDetailSheet(
                    commitmentId = id,
                    onDismiss = { navController.popBackStack() },
                    onEdit = { navController.navigate(BecalmRoute.CommitmentEdit(id).path) },
                )
            }
        }

        composable(
            route = BecalmRoute.CommitmentEdit.PATH,
            arguments = listOf(
                navArgument(BecalmRoute.CommitmentEdit.ARG_ID) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.CommitmentEdit.PATH]
            if (override != null) {
                override(backStackEntry)
            } else {
                val id = backStackEntry.stringArg(BecalmRoute.CommitmentEdit.ARG_ID).orEmpty()
                CommitmentEditSheet(
                    commitmentId = id,
                    onDismiss = { navController.popBackStack() },
                )
            }
        }

        // ── Settings ───────────────────────────────────────────────────────────

        composable(route = BecalmRoute.Settings.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.Settings.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                SettingsScreen(navController = navController)
            }
        }

        composable(route = BecalmRoute.PrivacyManagement.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.PrivacyManagement.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                PrivacyManagementScreen(navController = navController)
            }
        }

        composable(route = BecalmRoute.ConsentWithdraw.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.ConsentWithdraw.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                ConsentWithdrawScreen(navController = navController)
            }
        }

        composable(route = BecalmRoute.ProcessingPause.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.ProcessingPause.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                ProcessingPauseScreen(navController = navController)
            }
        }

        composable(route = BecalmRoute.ProcessingStatus.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.ProcessingStatus.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                ProcessingStatusScreen(navController = navController)
            }
        }

        composable(route = BecalmRoute.AccountDeletion.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.AccountDeletion.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                AccountDeletionScreen(navController = navController)
            }
        }

        composable(route = BecalmRoute.ActivityLog.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.ActivityLog.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                ActivityLogScreen(navController = navController)
            }
        }

        composable(route = BecalmRoute.SettingsSources.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.SettingsSources.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                SourcesListScreen(navController = navController)
            }
        }

        composable(route = BecalmRoute.ContactsSourceDetail.path) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.ContactsSourceDetail.path]
            if (override != null) {
                override(backStackEntry)
            } else {
                ContactsSourceDetailScreen(navController = navController)
            }
        }

        composable(
            route = BecalmRoute.SourceDetail.PATH,
            arguments = listOf(
                navArgument(BecalmNavArgs.SOURCE_ID) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val override = routeOverrides[BecalmRoute.SourceDetail.PATH]
            if (override != null) {
                override(backStackEntry)
            } else {
                val sourceId = backStackEntry.stringArg(BecalmNavArgs.SOURCE_ID)
                    ?: return@composable
                SourceDetailScreen(navController = navController, sourceId = sourceId)
            }
        }
    }
}
