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
 * Route paths are derived from `.spec/contracts/ui-map.yml` (version 1, 24 entries).
 * This implementation keeps the declared routes registered, including compatibility
 * routes retained for legacy onboarding source deep links.
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
     * Canonical first-run setup surface after login.
     *
     * Terms and login remain public/auth gates. Post-login setup groups recommended
     * permissions and optional source connections in one screen so users do not walk
     * through one app page per permission. Legacy individual onboarding routes remain
     * registered for settings reconnect and recovery flows.
     */
    public data object OnboardingSetup : BecalmRoute("onboarding/setup")

    /**
     * Compatibility route for the former standalone PIPA 제3자 제공 + 국외 이전 동의 screen.
     *
     * Canonical first-run setup now shows the same disclosure inline before the
     * recording folder permission flow.
     */
    public data object OnboardingPipaConsent : BecalmRoute("onboarding/pipa-consent")

    /** Compatibility route: standalone recording folder permission grant. */
    public data object OnboardingRecordingFolder : BecalmRoute("onboarding/recording-folder")

    /** Onboarding step: optional READ_CALL_LOG consent for call-recording person matching. */
    public data object OnboardingCallLogMatching : BecalmRoute("onboarding/call-log-matching")

    /** Compatibility route: READ_CONTACTS permission with PIPA notice (ENR-001). */
    public data object OnboardingContacts : BecalmRoute("onboarding/contacts")

    /** Compatibility/settings route: connect optional mail and calendar OAuth sources. */
    public data object OnboardingSources : BecalmRoute("onboarding/sources")

    /**
     * PIPA 제3자 제공 disclosure shown immediately before the email-provider OAuth /
     * credential screen (S6-D). `provider` is one of `gmail`, `outlook_mail`, `imap`
     * and matches [com.becalm.android.data.local.datastore.EmailPipaProvider.storageKey].
     *
     * Usage:
     * ```kotlin
     * navController.navigate(BecalmRoute.OnboardingEmailPipa("gmail").path)
     * ```
     */
    public data class OnboardingEmailPipa(public val provider: String) :
        BecalmRoute("onboarding/pipa-email/$provider") {
        public companion object {
            /** NavHost destination template. */
            public const val PATH: String = "onboarding/pipa-email/{provider}"

            /** `navArgument` key for the provider storage slug. */
            public const val ARG_PROVIDER: String = "provider"
        }
    }

    /** Source connection route: Gmail OAuth connection. */
    public data object OnboardingGmail : BecalmRoute("onboarding/gmail")

    /** Source connection route: Outlook Mail OAuth connection. */
    public data object OnboardingOutlookMail : BecalmRoute("onboarding/outlook-mail")

    /** Source connection route: IMAP / App Password manual setup. */
    public data object OnboardingImap : BecalmRoute("onboarding/imap")

    /** Source connection route: Google Calendar OAuth connection. */
    public data object OnboardingGoogleCalendar : BecalmRoute("onboarding/google-calendar")

    /** Source connection route: Outlook Calendar OAuth connection. */
    public data object OnboardingOutlookCalendar : BecalmRoute("onboarding/outlook-calendar")

    /**
     * Compatibility route: POST_NOTIFICATIONS runtime permission for Android 13+.
     *
     * On API 32 and below the permission is implicitly granted at install time, so the
     * screen self-skips in a single recomposition. First-run setup requests this
     * permission inline from [OnboardingSetup].
     */
    public data object OnboardingNotificationPerm : BecalmRoute("onboarding/notifications")

    /** Compatibility route: battery optimisation exemption (ONB-005). */
    public data object OnboardingBattery : BecalmRoute("onboarding/battery")

    /**
     * Compatibility route: former first-run cold-sync progress screen (TDY-010 / ONB-008).
     * Canonical setup now enters Today first and lets runtime sync status surface progress.
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
        }
    }

    /** Unassigned events screen: raw events where `person_ref IS NULL`. */
    public data object PersonsUnassigned : BecalmRoute("persons/unassigned")

    /** Tab 3: commitment management with filter tabs (전체 / 내가 한 / 상대가 한). */
    public data object Commitments : BecalmRoute("commitments")

    /**
     * Supersede-create commitment bottom sheet (EDIT-007).
     *
     * - EDIT-007 supersede: `supersedeOf = <uuid>` → quote + source section
     *   are pre-filled read-only from the old row; on save, the old row is
     *   soft-deleted and the new row carries `supersedes_commitment_id = old.id`.
     *
     * The `supersedeOf` query argument is required at call sites. The NavHost
     * still keeps a nullable parser so malformed legacy routes fail inside the
     * sheet with a missing-source error instead of crashing.
     *
     * Usage:
     * ```kotlin
     * // Supersede from the edit sheet's [이건 다른 약속입니다] button
     * navController.navigate(BecalmRoute.CommitmentCreate("cmt_old").path)
     * ```
     */
    public data class CommitmentCreate(public val supersedeOf: String) : BecalmRoute(
        "commitments/new?supersedeOf=$supersedeOf",
    ) {
        public companion object {
            /** NavHost destination template — declares the optional query arg. */
            public const val PATH: String = "commitments/new?supersedeOf={supersedeOf}"

            /** `navArgument` key for the optional supersede target UUID. */
            public const val ARG_SUPERSEDE_OF: String = "supersedeOf"
        }
    }

    /**
     * Commitment detail bottom sheet: full quote + source context + 5 action buttons
     * (CMT-003). Opened on card tap from [Commitments]; also the landing target for
     * the `becalm://commitments/{id}` reminder deep link registered in a future
     * commit (C5).
     *
     * Usage: `navController.navigate(BecalmRoute.CommitmentDetail("cmt_abc").path)`
     */
    public data class CommitmentDetail(public val id: String) :
        BecalmRoute("commitments/$id") {
        public companion object {
            /** NavHost destination template. */
            public const val PATH: String = "commitments/{id}"

            /** `navArgument` key for the commitment UUID. */
            public const val ARG_ID: String = "id"
        }
    }

    /**
     * Commitment edit bottom sheet (EDIT-001..008). Opened from the
     * `[편집]` button on [CommitmentDetail]. Carries the same UUID as the
     * detail sheet so back-stack pops land cleanly on the detail screen.
     *
     * Usage: `navController.navigate(BecalmRoute.CommitmentEdit("cmt_abc").path)`
     */
    public data class CommitmentEdit(public val id: String) :
        BecalmRoute("commitments/$id/edit") {
        public companion object {
            /** NavHost destination template. */
            public const val PATH: String = "commitments/{id}/edit"

            /** `navArgument` key for the commitment UUID. */
            public const val ARG_ID: String = "id"
        }
    }

    // ── Settings ───────────────────────────────────────────────────────────────

    /** Settings root — accessed via top-right icon on TodayTimelineScreen. */
    public data object Settings : BecalmRoute("settings")

    /** Settings profile surface for the user's own identity and source ownership anchors. */
    public data object SettingsIdentity : BecalmRoute("settings/identity")

    /** PIPA rights execution hub under Settings. */
    public data object PrivacyManagement : BecalmRoute("settings/privacy")

    /** Selective consent withdrawal screen. */
    public data object ConsentWithdraw : BecalmRoute("settings/privacy/consents")

    /** Processing-pause control screen. */
    public data object ProcessingPause : BecalmRoute("settings/privacy/pause")

    /** Read-only source processing status screen. */
    public data object ProcessingStatus : BecalmRoute("settings/processing-status")

    /** Two-step local account deletion flow. */
    public data object AccountDeletion : BecalmRoute("settings/privacy/delete-account")

    /** Local-only PIPA activity log. */
    public data object ActivityLog : BecalmRoute("settings/privacy/activity-log")

    /** Sources list: 6-source adapter status rows + contacts pseudo-source (ENR-008, SMG-001). */
    public data object SettingsSources : BecalmRoute("settings/sources")

    /** Settings source connection hub shared with onboarding source OAuth rows. */
    public data object SettingsSourceConnections : BecalmRoute("settings/sources/connect")

    /**
     * Contacts pseudo-source detail under Settings.
     *
     * Opened only when READ_CONTACTS is already granted. The permission-denied branch
     * routes to [OnboardingContacts] instead.
     */
    public data object ContactsSourceDetail : BecalmRoute("settings/sources/contacts")

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
        }
    }
}
