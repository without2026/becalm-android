# Local UI Test Checklist

Derived from:
- `docs/becalm-mvp-boundary.md`
- `.spec/**`
- current `android/app/src/main/java/com/becalm/android/ui/**`

Scope:
- local JVM UI tests only (`src/test`)
- Compose semantics / rendering / CTA / section visibility / dialog-visible content
- excludes `androidTest` route wiring and device-only permission launcher behavior

Exit condition:
- every checklist item below has a corresponding local JVM UI test
- targeted JVM UI test task passes

## Auth
- [x] `SplashScreen` branding / signed-out gate surface
- [x] `LoginScreen` empty validation / CTA enabled state / Google CTA disabled state
- [x] `TermsScreen` notice / accept gate / decline CTA

## Today
- [x] `TodayTimelineContent` loading / empty / error / populated states
- [x] `TodayTimelineContent` processing paused banner / settings CTA
- [x] `OverallSyncIndicator` idle / syncing / synced / partial-failure labels
- [x] `SourceStatusStrip` seven-source labels and state surfacing

## Persons
- [x] `PersonsScreenContent` offline badge / unassigned section / enriched row meta
- [x] `PersonDetailScreenContent` pending/completed/history sections / action-count badge / event tap
- [x] `UnassignedEventsScreen` list / empty state
- [x] `RawEventDetailSheet` base sections / attachment count / extracted action count / body expand-collapse

## Sources
- [x] `SourcesListScreenContent` contacts pseudo-source / empty state / row tap
- [x] `SourceDetailScreenContent` reconnect / sync / disconnect / confirm dialog
- [x] `ContactsSourceDetailScreen` summary and permission recovery surfacing

## Settings / Privacy
- [x] `SettingsScreenContent` processing paused banner / sources / privacy / wipe / sign-out note
- [x] `PrivacyManagementScreenContent` export / withdraw / pause / delete / activity-log cards
- [x] `ConsentWithdrawScreen` consent toggles visibility
- [x] `ProcessingPauseScreen` pause description / switch / started-at label / confirm dialog
- [x] `AccountDeletionScreen` warning counts / email field / keyword field / delete CTA
- [x] `ActivityLogScreen` empty state / populated log rows

## Commitments
- [x] `CommitmentManagementScreenContent` loading / empty / populated states
- [x] `CommitmentManagementScreenContent` filter chips / completed-cancelled sections / FAB / detail tap
- [x] `CommitmentDetailSheet` source / history / action-state chips
- [x] `CommitmentCreateSheet` required inputs / direction / save CTA
- [x] `CommitmentEditSheet` initial values / save CTA / delete affordance

## Onboarding
- [x] `PipaThirdPartyConsentContent` disclosure bullets / agree / decline
- [x] `RecordingFolderScreen` detection summary / grant / skip copy
- [x] `ContactsPermissionScreen` rationale / PIPA note / grant / skip
- [x] `OnboardingEmailPipaConsentScreen` provider-specific disclosure and CTA copy
- [x] `GmailOAuthScreen` placeholder copy / CTA / skip
- [x] `OutlookMailOAuthScreen` placeholder copy / CTA / skip
- [x] `ImapSetupScreen` form fields / provider selector / CTA / validation copy
- [x] `GoogleCalendarOAuthScreen` placeholder copy / CTA / skip
- [x] `OutlookCalendarOAuthScreen` placeholder copy / CTA / skip
- [x] `NotificationPermissionScreen` rationale / grant / skip
- [x] `BatteryOptimizationScreen` guidance / CTA / skip
- [x] `ColdSyncScreen` in-progress and done states / skip / continue
