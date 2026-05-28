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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.ui.sources.SourceDetailEffect
import com.becalm.android.ui.sources.SourceReconnectDestination
import com.becalm.android.ui.sources.SourcesListNavigation
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
    fun onboarding_complete_route_parses_person_id_argument() {
        setNavHost(startDestination = BecalmRoute.OnboardingComplete("person-42").path) {
            mapOf(
                BecalmRoute.OnboardingComplete.PATH to { entry ->
                    Text("complete:${entry.arguments?.getString(BecalmNavArgs.PERSON_ID)}")
                },
            )
        }

        composeTestRule.onNodeWithText("complete:person-42").assertIsDisplayed()
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
    fun settings_source_connection_route_parses_provider_argument() {
        setNavHost(startDestination = BecalmRoute.SettingsSourceConnection("google_calendar").path) {
            mapOf(
                BecalmRoute.SettingsSourceConnection.PATH to { entry ->
                    Text("provider:${entry.arguments?.getString(BecalmRoute.SettingsSourceConnection.ARG_PROVIDER)}")
                },
            )
        }

        composeTestRule.onNodeWithText("provider:google_calendar").assertIsDisplayed()
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
                StaticRouteCase(BecalmRoute.SignUp.path, BecalmRoute.SignUp.path, "signup-screen"),
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
    fun cold_sync_route_redirects_to_authenticated_home_without_rendering_deprecated_screen() {
        composeTestRule.setContent {
            BecalmTheme {
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()

                Column {
                    Text("route:${backStackEntry?.destination?.route.orEmpty()}")
                    BecalmNavHost(
                        navController = navController,
                        startDestination = BecalmRoute.OnboardingColdSync.path,
                        routeOverrides = mapOf(
                            BecalmRoute.Persons.path to { Text("persons-screen") },
                        ),
                    )
                }
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("persons-screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("persons-screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("route:${BecalmRoute.Persons.path}").assertIsDisplayed()
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
                StaticRouteCase(
                    BecalmRoute.SettingsSourceConnection("gmail").path,
                    BecalmRoute.SettingsSourceConnection.PATH,
                    "gmail-connection-screen",
                ),
                StaticRouteCase(BecalmRoute.ContactsSourceDetail.path, BecalmRoute.ContactsSourceDetail.path, "contacts-detail-screen"),
                StaticRouteCase(
                    BecalmRoute.SettingsContactsPermission.path,
                    BecalmRoute.SettingsContactsPermission.path,
                    "contacts-permission-screen",
                ),
                StaticRouteCase(BecalmRoute.Commitments.path, BecalmRoute.Commitments.path, "commitments-screen"),
            ),
        )
    }

    @Test
    fun settings_contacts_permission_navigation_uses_settings_route() {
        composeTestRule.setContent {
            BecalmTheme {
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()

                Column {
                    Text("route:${backStackEntry?.destination?.route.orEmpty()}")
                    Button(onClick = { navController.dispatchSourcesListNavigation(SourcesListNavigation.ContactsPermission) }) {
                        Text("open-contacts-permission")
                    }
                    BecalmNavHost(
                        navController = navController,
                        startDestination = BecalmRoute.SettingsSources.path,
                        routeOverrides = mapOf(
                            BecalmRoute.SettingsSources.path to { Text("settings-sources-screen") },
                            BecalmRoute.SettingsContactsPermission.path to { Text("settings-contacts-permission-screen") },
                            BecalmRoute.OnboardingContacts.path to { Text("onboarding-contacts-screen") },
                        ),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("settings-sources-screen").assertIsDisplayed()

        composeTestRule.onNodeWithText("open-contacts-permission").performClick()

        composeTestRule.onNodeWithText("settings-contacts-permission-screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("route:${BecalmRoute.SettingsContactsPermission.path}").assertIsDisplayed()
    }

    @Test
    fun settings_oauth_source_reconnect_navigation_uses_scoped_settings_routes() {
        val cases = listOf(
            SourceReconnectDestination.GMAIL to BecalmRoute.SettingsSourceConnection("gmail").path,
            SourceReconnectDestination.OUTLOOK_MAIL to BecalmRoute.SettingsSourceConnection("outlook_mail").path,
            SourceReconnectDestination.GOOGLE_CALENDAR to BecalmRoute.SettingsSourceConnection("google_calendar").path,
            SourceReconnectDestination.OUTLOOK_CALENDAR to BecalmRoute.SettingsSourceConnection("outlook_calendar").path,
        )

        composeTestRule.setContent {
            BecalmTheme {
                var index by remember { mutableStateOf(0) }
                val case = cases[index]

                key(index) {
                    val navController = rememberNavController()
                    val backStackEntry by navController.currentBackStackEntryAsState()

                    Column {
                        Text("route:${backStackEntry?.destination?.route.orEmpty()}")
                        Button(
                            onClick = {
                                navController.dispatchSourceDetailEffect(
                                    SourceDetailEffect.OpenReconnect(case.first),
                                )
                            },
                        ) {
                            Text("reconnect-source")
                        }
                        Button(onClick = { if (index < cases.lastIndex) index += 1 }) {
                            Text("next-case")
                        }
                        BecalmNavHost(
                            navController = navController,
                            startDestination = BecalmRoute.SourceDetail("source").path,
                            routeOverrides = mapOf(
                                BecalmRoute.SourceDetail.PATH to { Text("source-detail-screen") },
                                BecalmRoute.SettingsSourceConnection.PATH to { entry ->
                                    Text(
                                        "settings-provider:" +
                                            "${entry.arguments?.getString(BecalmRoute.SettingsSourceConnection.ARG_PROVIDER)}",
                                    )
                                },
                                BecalmRoute.OnboardingSources.path to { Text("onboarding-sources-screen") },
                            ),
                        )
                    }
                }
            }
        }

        cases.forEachIndexed { index, case ->
            composeTestRule.onNodeWithText("source-detail-screen").assertIsDisplayed()
            composeTestRule.onNodeWithText("reconnect-source").performClick()
            composeTestRule.onNodeWithText("route:${BecalmRoute.SettingsSourceConnection.PATH}").assertIsDisplayed()
            composeTestRule.onNodeWithText("settings-provider:${case.second.substringAfterLast('/')}").assertIsDisplayed()
            if (index < cases.lastIndex) {
                composeTestRule.onNodeWithText("next-case").performClick()
            }
        }
    }

    @Test
    fun settings_imap_reconnect_completion_returns_to_settings_sources() {
        assertSettingsReconnectCompletionReturns(
            destination = SourceReconnectDestination.NAVER_IMAP,
            destinationRoute = BecalmRoute.OnboardingEmailPipa.PATH,
            completionFallbackRoute = BecalmRoute.OnboardingGoogleCalendar.path,
        )
    }

    @Test
    fun settings_recording_reconnect_completion_returns_to_settings_sources() {
        assertSettingsReconnectCompletionReturns(
            destination = SourceReconnectDestination.RECORDING_FOLDER,
            destinationRoute = BecalmRoute.OnboardingRecordingFolder.path,
            completionFallbackRoute = BecalmRoute.OnboardingContacts.path,
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
                        startDestination = BecalmRoute.Persons.path,
                        routeOverrides = mapOf(
                            BecalmRoute.Persons.path to { Text("persons-screen") },
                            BecalmRoute.PersonDetail.PATH to { entry ->
                                Text("person:${entry.arguments?.getString(BecalmNavArgs.PERSON_ID)}")
                            },
                        ),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("persons-screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("route:persons").assertIsDisplayed()

        composeTestRule.onNodeWithText("open-person").performClick()
        composeTestRule.onNodeWithText("person:person-88").assertIsDisplayed()
        composeTestRule.onNodeWithText("route:persons/{person_id}").assertIsDisplayed()

        composeTestRule.onNodeWithText("go-back").performClick()
        composeTestRule.onNodeWithText("persons-screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("route:persons").assertIsDisplayed()
    }

    private fun assertSettingsReconnectCompletionReturns(
        destination: SourceReconnectDestination,
        destinationRoute: String,
        completionFallbackRoute: String,
    ) {
        composeTestRule.setContent {
            BecalmTheme {
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()

                Column {
                    Text("route:${backStackEntry?.destination?.route.orEmpty()}")
                    Button(onClick = { navController.navigate(BecalmRoute.SourceDetail("source").path) }) {
                        Text("open-source-detail")
                    }
                    BecalmNavHost(
                        navController = navController,
                        startDestination = BecalmRoute.SettingsSources.path,
                        routeOverrides = mapOf(
                            BecalmRoute.SettingsSources.path to { Text("settings-sources-screen") },
                            BecalmRoute.SourceDetail.PATH to {
                                Column {
                                    Text("source-detail-screen")
                                    Button(
                                        onClick = {
                                            navController.dispatchSourceDetailEffect(
                                                SourceDetailEffect.OpenReconnect(destination),
                                            )
                                        },
                                    ) {
                                        Text("reconnect-source")
                                    }
                                }
                            },
                            destinationRoute to {
                                Column {
                                    Text("reconnect-screen")
                                    Button(
                                        onClick = {
                                            navController.navigateAfterSourceReconnectOr(completionFallbackRoute)
                                        },
                                    ) {
                                        Text("complete-reconnect")
                                    }
                                }
                            },
                        ),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("settings-sources-screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("open-source-detail").performClick()
        composeTestRule.onNodeWithText("source-detail-screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("reconnect-source").performClick()
        composeTestRule.onNodeWithText("route:$destinationRoute").assertIsDisplayed()
        composeTestRule.onNodeWithText("complete-reconnect").performClick()
        composeTestRule.onNodeWithText("settings-sources-screen").assertIsDisplayed()
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
