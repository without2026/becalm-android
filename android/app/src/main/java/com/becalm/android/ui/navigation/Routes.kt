package com.becalm.android.ui.navigation

/**
 * Sealed hierarchy of every navigable route in the BeCalm Android app.
 *
 * ## Pattern
 * - Routes with no parameters are `data object` singletons. Use [path] directly:
 *   ```kotlin
 *   navController.navigate(BecalmRoute.Today.path)
 *   ```
 * - Routes with path parameters are `data class` instances. Pass argument values at the
 *   call site so [path] is fully resolved before handing it to the NavController:
 *   ```kotlin
 *   navController.navigate(BecalmRoute.PersonDetail("phone:+82-10-1234-5678").path)
 *   ```
 *   Each parameterised route also exposes a companion [PATH] template
 *   (`"segment/{argName}"`) used when *registering* the destination in [BecalmNavHost],
 *   and `ARG_<NAME>` string constants for `navArgument(...)` bindings.
 *
 * ## Source of truth
 * Route paths are derived from `.spec/contracts/ui-map.yml` (version 1, 21 entries).
 * The spec header claims 22 routes; the actual entry count in that file is 21 —
 * this implementation matches the 21 declared entries exactly.
 *
 * ## Leading-slash divergence (R1-03)
 * The spec lists every route with a leading `/` (e.g. `/today`, `/persons/{person_id}`)
 * using a URL-style notation. Compose Navigation routes, however, are **opaque string
 * identifiers** matched verbatim by `NavHost` / `NavController`, not URL paths — the
 * framework convention is to register and navigate to them *without* a leading `/`
 * (see `androidx.navigation.compose.NavHost` samples and AOSP reference apps such as
 * Now in Android). Prefixing a slash would make these strings non-idiomatic and would
 * not match any Android Navigation deep-link or argument-binding behaviour.
 *
 * Therefore the leading `/` from `ui-map.yml` is **documentation-only**: the spec's
 * `/foo/bar` maps to the Compose route `"foo/bar"` declared here. Route values in
 * this file are the authoritative identifiers used at runtime; the spec's slashes
 * are read as path separators for human readability only.
 */
public sealed class BecalmRoute(public val path: String) {

    // ── Auth / Public ──────────────────────────────────────────────────────────

    /** Splash gate: decides onboarding vs /today based on DataStore state. */
    public data object Splash : BecalmRoute("splash")

    /** Terms & conditions acceptance screen shown before login. */
    public data object Terms : BecalmRoute("terms")

    /** Email/password + Google Sign-In login screen. */
    public data object Login : BecalmRoute("login")

    // ── Onboarding (auth required) ─────────────────────────────────────────────

    /**
     * Onboarding step 3 of 12: PIPA 제3자 제공 + 국외 이전 동의 (ONB-PIPA).
     *
     * Shown immediately after login, before RecordingFolderScreen.
     * [동의] navigates to [OnboardingRecordingFolder]; [동의 안 함] skips to [OnboardingContacts].
     */
    public data object OnboardingPipaConsent : BecalmRoute("onboarding/pipa-consent")

    /** Onboarding step: recording folder permission grant. */
    public data object OnboardingRecordingFolder : BecalmRoute("onboarding/recording-folder")

    /**
     * Onboarding step: READ_CONTACTS permission with PIPA notice (ENR-001).
     * Graceful skip — refusal does not block app functionality.
     */
    public data object OnboardingContacts : BecalmRoute("onboarding/contacts")

    /** Onboarding step: Gmail OAuth connection. */
    public data object OnboardingGmail : BecalmRoute("onboarding/gmail")

    /** Onboarding step: Outlook Mail OAuth connection. */
    public data object OnboardingOutlookMail : BecalmRoute("onboarding/outlook-mail")

    /** Onboarding step: IMAP / App Password manual setup. */
    public data object OnboardingImap : BecalmRoute("onboarding/imap")

    /** Onboarding step: Google Calendar OAuth connection. */
    public data object OnboardingGoogleCalendar : BecalmRoute("onboarding/google-calendar")

    /** Onboarding step: Outlook Calendar OAuth connection. */
    public data object OnboardingOutlookCalendar : BecalmRoute("onboarding/outlook-calendar")

    /**
     * Onboarding step: battery optimisation exemption (ONB-005).
     * 2-step: standard REQUEST_IGNORE_BATTERY_OPTIMIZATIONS + Samsung sleeping-apps guide.
     */
    public data object OnboardingBattery : BecalmRoute("onboarding/battery")

    /**
     * Onboarding step: first-run cold-sync progress screen (TDY-010 / ONB-008).
     * Shown when Room is entirely empty after onboarding completes.
     * DataStore `onboarding_completed=true` written only after dismiss or sync completion.
     */
    public data object OnboardingColdSync : BecalmRoute("onboarding/cold-sync")

    // ── Main app — 3-tab bottom nav ────────────────────────────────────────────

    /** Tab 1: today's timeline — unified calendar events + due commitments. */
    public data object Today : BecalmRoute("today")

    /** Tab 2: persons list with enriched display names and DNBadge counts. */
    public data object Persons : BecalmRoute("persons")

    /**
     * Person detail: 3-section body — pending commitments / completed /
     * interaction history (SRC-002, SRC-008).
     *
     * Usage: `navController.navigate(BecalmRoute.PersonDetail("usr_abc123").path)`
     */
    public data class PersonDetail(public val personId: String) :
        BecalmRoute("persons/$personId") {
        public companion object {
            /** NavHost destination template. */
            public const val PATH: String = "persons/{person_id}"

            /** `navArgument` key for [personId]. */
            public const val ARG_PERSON_ID: String = "person_id"
        }
    }

    /**
     * Raw event detail bottom sheet for a specific event under a person
     * (extended fields loaded from Room).
     *
     * Usage:
     * ```kotlin
     * navController.navigate(BecalmRoute.RawEventDetail("usr_abc123", "evt_xyz").path)
     * ```
     */
    public data class RawEventDetail(
        public val personId: String,
        public val eventId: String,
    ) : BecalmRoute("persons/$personId/events/$eventId") {
        public companion object {
            /** NavHost destination template. */
            public const val PATH: String = "persons/{person_id}/events/{event_id}"

            /** `navArgument` key for [personId]. */
            public const val ARG_PERSON_ID: String = "person_id"

            /** `navArgument` key for [eventId]. */
            public const val ARG_EVENT_ID: String = "event_id"
        }
    }

    /** Unassigned events screen: raw events where `person_ref IS NULL`. */
    public data object PersonsUnassigned : BecalmRoute("persons/unassigned")

    /** Tab 3: commitment management with filter tabs (전체 / 내가 한 / 상대가 한). */
    public data object Commitments : BecalmRoute("commitments")

    // ── Settings ───────────────────────────────────────────────────────────────

    /** Settings root — accessed via top-right icon on TodayTimelineScreen. */
    public data object Settings : BecalmRoute("settings")

    /** Sources list: 6-source adapter status rows + contacts pseudo-source (ENR-008, SMG-001). */
    public data object SettingsSources : BecalmRoute("settings/sources")

    /**
     * Source detail: status, last-sync info, reconnect / disconnect / manual-sync
     * actions (SMG-002..005).
     *
     * Usage: `navController.navigate(BecalmRoute.SourceDetail("gmail").path)`
     */
    public data class SourceDetail(public val sourceId: String) :
        BecalmRoute("settings/sources/$sourceId") {
        public companion object {
            /** NavHost destination template. */
            public const val PATH: String = "settings/sources/{source_id}"

            /** `navArgument` key for [sourceId]. */
            public const val ARG_SOURCE_ID: String = "source_id"
        }
    }
}
