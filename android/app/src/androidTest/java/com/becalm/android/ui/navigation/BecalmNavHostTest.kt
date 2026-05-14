package com.becalm.android.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.ui.theme.BecalmTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BecalmNavHostTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun persons_unassigned_route_matches_literal_destination_before_person_detail() {
        setNavHost(startDestination = BecalmRoute.PersonsUnassigned.path) {
            mapOf(
                BecalmRoute.PersonsUnassigned.path to { Text("unassigned-screen") },
                BecalmRoute.PersonDetail.PATH to { entry ->
                    Text("person:${entry.arguments?.getString(BecalmNavArgs.PERSON_ID)}")
                },
            )
        }

        composeTestRule.onNodeWithText("unassigned-screen").assertIsDisplayed()
    }

    @Test
    fun person_detail_route_parses_person_id_argument() {
        setNavHost(startDestination = BecalmRoute.PersonDetail("person-42").path) {
            mapOf(
                BecalmRoute.PersonDetail.PATH to { entry ->
                    Text("person:${entry.arguments?.getString(BecalmNavArgs.PERSON_ID)}")
                },
            )
        }

        composeTestRule.onNodeWithText("person:person-42").assertIsDisplayed()
    }

    @Test
    fun raw_event_detail_route_parses_both_path_arguments() {
        setNavHost(startDestination = BecalmRoute.RawEventDetail("person-42", "event-7").path) {
            mapOf(
                BecalmRoute.RawEventDetail.PATH to { entry ->
                    Text(
                        "raw:${entry.arguments?.getString(BecalmNavArgs.PERSON_ID)}:" +
                            "${entry.arguments?.getString(BecalmNavArgs.EVENT_ID)}",
                    )
                },
            )
        }

        composeTestRule.onNodeWithText("raw:person-42:event-7").assertIsDisplayed()
    }

    @Test
    fun email_pipa_route_parses_provider_slug() {
        setNavHost(startDestination = BecalmRoute.OnboardingEmailPipa("gmail").path) {
            mapOf(
                BecalmRoute.OnboardingEmailPipa.PATH to { entry ->
                    Text("provider:${entry.arguments?.getString(BecalmRoute.OnboardingEmailPipa.ARG_PROVIDER)}")
                },
            )
        }

        composeTestRule.onNodeWithText("provider:gmail").assertIsDisplayed()
    }

    @Test
    fun commitment_create_route_accepts_optional_supersede_query() {
        setNavHost(startDestination = BecalmRoute.CommitmentCreate("legacy-9").path) {
            mapOf(
                BecalmRoute.CommitmentCreate.PATH to { entry ->
                    Text(
                        "supersede:${entry.arguments?.getString(BecalmRoute.CommitmentCreate.ARG_SUPERSEDE_OF)}",
                    )
                },
            )
        }

        composeTestRule.onNodeWithText("supersede:legacy-9").assertIsDisplayed()
    }

    @Test
    fun source_detail_route_parses_source_type_argument() {
        setNavHost(startDestination = BecalmRoute.SourceDetail("gmail").path) {
            mapOf(
                BecalmRoute.SourceDetail.PATH to { entry ->
                    Text("source:${entry.arguments?.getString(BecalmNavArgs.SOURCE_ID)}")
                },
            )
        }

        composeTestRule.onNodeWithText("source:gmail").assertIsDisplayed()
    }

    @Test
    fun commitment_detail_route_parses_id_argument() {
        setNavHost(startDestination = BecalmRoute.CommitmentDetail("cmt-77").path) {
            mapOf(
                BecalmRoute.CommitmentDetail.PATH to { entry ->
                    Text("detail:${entry.arguments?.getString(BecalmRoute.CommitmentDetail.ARG_ID)}")
                },
            )
        }

        composeTestRule.onNodeWithText("detail:cmt-77").assertIsDisplayed()
    }

    @Test
    fun commitment_edit_route_parses_id_argument() {
        setNavHost(startDestination = BecalmRoute.CommitmentEdit("cmt-91").path) {
            mapOf(
                BecalmRoute.CommitmentEdit.PATH to { entry ->
                    Text("edit:${entry.arguments?.getString(BecalmRoute.CommitmentEdit.ARG_ID)}")
                },
            )
        }

        composeTestRule.onNodeWithText("edit:cmt-91").assertIsDisplayed()
    }

    @Test
    fun auth_static_routes_are_registered() {
        assertStaticRoutesResolve(
            listOf(
                StaticRouteCase(BecalmRoute.Splash.path, BecalmRoute.Splash.path, "splash-screen"),
                StaticRouteCase(BecalmRoute.Terms.path, BecalmRoute.Terms.path, "terms-screen"),
                StaticRouteCase(BecalmRoute.Login.path, BecalmRoute.Login.path, "login-screen"),
            ),
        )
    }

    @Test
    fun onboarding_static_routes_are_registered() {
        assertStaticRoutesResolve(
            listOf(
                StaticRouteCase(BecalmRoute.OnboardingSetup.path, BecalmRoute.OnboardingSetup.path, "onb-setup-screen"),
                StaticRouteCase(BecalmRoute.OnboardingPipaConsent.path, BecalmRoute.OnboardingPipaConsent.path, "onb-pipa-screen"),
                StaticRouteCase(BecalmRoute.OnboardingRecordingFolder.path, BecalmRoute.OnboardingRecordingFolder.path, "recording-folder-screen"),
                StaticRouteCase(BecalmRoute.OnboardingContacts.path, BecalmRoute.OnboardingContacts.path, "contacts-screen"),
                StaticRouteCase(BecalmRoute.OnboardingSources.path, BecalmRoute.OnboardingSources.path, "sources-screen"),
                StaticRouteCase(BecalmRoute.OnboardingGmail.path, BecalmRoute.OnboardingGmail.path, "gmail-screen"),
                StaticRouteCase(BecalmRoute.OnboardingOutlookMail.path, BecalmRoute.OnboardingOutlookMail.path, "outlook-mail-screen"),
                StaticRouteCase(BecalmRoute.OnboardingImap.path, BecalmRoute.OnboardingImap.path, "imap-screen"),
                StaticRouteCase(BecalmRoute.OnboardingGoogleCalendar.path, BecalmRoute.OnboardingGoogleCalendar.path, "google-calendar-screen"),
                StaticRouteCase(BecalmRoute.OnboardingOutlookCalendar.path, BecalmRoute.OnboardingOutlookCalendar.path, "outlook-calendar-screen"),
                StaticRouteCase(BecalmRoute.OnboardingNotificationPerm.path, BecalmRoute.OnboardingNotificationPerm.path, "notification-screen"),
                StaticRouteCase(BecalmRoute.OnboardingBattery.path, BecalmRoute.OnboardingBattery.path, "battery-screen"),
                StaticRouteCase(BecalmRoute.OnboardingColdSync.path, BecalmRoute.OnboardingColdSync.path, "cold-sync-screen"),
            ),
        )
    }

    @Test
    fun settings_sources_and_privacy_routes_are_registered() {
        assertStaticRoutesResolve(
            listOf(
                StaticRouteCase(BecalmRoute.Settings.path, BecalmRoute.Settings.path, "settings-screen"),
                StaticRouteCase(BecalmRoute.PrivacyManagement.path, BecalmRoute.PrivacyManagement.path, "privacy-screen"),
                StaticRouteCase(BecalmRoute.ConsentWithdraw.path, BecalmRoute.ConsentWithdraw.path, "withdraw-screen"),
                StaticRouteCase(BecalmRoute.ProcessingPause.path, BecalmRoute.ProcessingPause.path, "pause-screen"),
                StaticRouteCase(BecalmRoute.AccountDeletion.path, BecalmRoute.AccountDeletion.path, "delete-screen"),
                StaticRouteCase(BecalmRoute.ActivityLog.path, BecalmRoute.ActivityLog.path, "activity-log-screen"),
                StaticRouteCase(BecalmRoute.SettingsSources.path, BecalmRoute.SettingsSources.path, "sources-screen"),
                StaticRouteCase(BecalmRoute.SettingsSourceConnections.path, BecalmRoute.SettingsSourceConnections.path, "source-connections-screen"),
                StaticRouteCase(BecalmRoute.ContactsSourceDetail.path, BecalmRoute.ContactsSourceDetail.path, "contacts-detail-screen"),
                StaticRouteCase(BecalmRoute.Commitments.path, BecalmRoute.Commitments.path, "commitments-screen"),
            ),
        )
    }

    @Test
    fun nav_host_preserves_back_stack_when_navigating_forward_and_popping() {
        composeTestRule.setContent {
            BecalmTheme {
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()

                Column {
                    Text("route:${backStackEntry?.destination?.route.orEmpty()}")
                    Button(onClick = { navController.navigate(BecalmRoute.PersonDetail("person-88").path) }) {
                        Text("open-person")
                    }
                    Button(onClick = { navController.popBackStack() }) {
                        Text("go-back")
                    }
                    BecalmNavHost(
                        navController = navController,
                        startDestination = BecalmRoute.Today.path,
                        routeOverrides = mapOf(
                            BecalmRoute.Today.path to { Text("today-screen") },
                            BecalmRoute.PersonDetail.PATH to { entry ->
                                Text("person:${entry.arguments?.getString(BecalmNavArgs.PERSON_ID)}")
                            },
                        ),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("today-screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("route:today").assertIsDisplayed()

        composeTestRule.onNodeWithText("open-person").performClick()
        composeTestRule.onNodeWithText("person:person-88").assertIsDisplayed()
        composeTestRule.onNodeWithText("route:persons/{person_id}").assertIsDisplayed()

        composeTestRule.onNodeWithText("go-back").performClick()
        composeTestRule.onNodeWithText("today-screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("route:today").assertIsDisplayed()
    }

    private fun setNavHost(
        startDestination: String,
        overrides: () -> Map<String, BecalmNavHostOverride>,
    ) {
        composeTestRule.setContent {
            BecalmTheme {
                BecalmNavHost(
                    navController = rememberNavController(),
                    startDestination = startDestination,
                    routeOverrides = overrides(),
                )
            }
        }
    }

    private fun assertStaticRoutesResolve(routes: List<StaticRouteCase>) {
        composeTestRule.setContent {
            BecalmTheme {
                var index by remember { mutableStateOf(0) }
                val route = routes[index]
                Column {
                    Button(onClick = { if (index < routes.lastIndex) index += 1 }) {
                        Text("next-route")
                    }
                    key(index) {
                        BecalmNavHost(
                            navController = rememberNavController(),
                            startDestination = route.startDestination,
                            routeOverrides = mapOf(route.overrideKey to { Text(route.expectedText) }),
                        )
                    }
                }
            }
        }

        routes.forEachIndexed { index, route ->
            composeTestRule.onNodeWithText(route.expectedText).assertIsDisplayed()
            if (index < routes.lastIndex) {
                composeTestRule.onNodeWithText("next-route").performClick()
            }
        }
    }

    private data class StaticRouteCase(
        val startDestination: String,
        val overrideKey: String,
        val expectedText: String,
    )
}
