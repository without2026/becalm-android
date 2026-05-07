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

## R10 Service Quality Invariants
- [x] People list rows hide work-context snippets; person detail can still show source evidence in the original language
- [x] Source list rows render localized source/status labels, never raw `source_type` or status enum ids
- [x] Source list/detail error copy hides raw repository/network messages from primary UI
- [x] Source list/detail UI state carries only error presence for repository/network failures, not raw error strings
- [x] Source list/detail UI state uses typed `SourceSyncStatus`, never raw repository status strings
- [x] Source/list/onboarding status pills share `SourceStatePresentation` + `StatusTone`; screens do not own source-state color decisions
- [x] Today source strip chips carry `SourceSyncStatus` directly; no parallel `ChipState` enum remains
- [x] Source sync and onboarding connection presentation contracts live outside indicator/row composables
- [x] Email/call/calendar source categorization is shared across Person, Today, and raw-event UI projections
- [x] Person detail timeline commitment rows render localized labels, never raw `give/take/pending/confirmed` wire values
- [x] Person detail renders interaction history through `sourceEventCards` only; legacy `InteractionRow`/timeline fallback is removed
- [x] Commitment UI direction/status wire values are centralized in `CommitmentPresentation`; filters and person projections do not hardcode `give/take` comparisons
- [x] Person detail next actions render in the relationship recommendation panel, not buried inside individual source-event body copy
- [x] Person detail next-action labels are resource-backed; projectors return label resource ids instead of hardcoded copy
- [x] Person detail source events do not use import artifact filenames as the primary timeline title
- [x] Relationship/detail surfaces use shared role components (`RelationshipCard`, `EvidenceCard`, `RecommendationPanel`, `QuietPanel`) instead of direct default glass panels
- [x] Contact rows and danger zones use role components (`ContactRow`, `DangerZone`) instead of ad hoc row/card styling
- [x] Processing status rows use shared source presentation labels and localized phase copy, never raw source ids
- [x] Commitment detail/edit source labels avoid raw source ids; source type is carried by shared badges
- [x] Commitment detail/edit source and history labels are resource-backed; ViewModels carry resource ids + args, not fixed Korean strings
- [x] Commitment create/edit save errors are resource-backed; ViewModels no longer pin fixed Korean failure copy
- [x] Commitment create/edit validation errors are code-backed in domain and resource-backed in UI state
- [x] Settings and privacy snackbar errors are resource-backed through `UiMessage`; ViewModels do not pin raw copy for known failures
- [x] Onboarding completion and consent-write errors are resource-backed through `UiMessage`; English fallback literals are not shown for known setup failures
- [x] Auth errors are resource-backed through `UiMessage`; login snackbar resolves locale copy at the Compose boundary
- [x] Person, source detail, today, and commitment error states use `UiMessage`; screens resolve locale copy at the Compose boundary
- [x] Runtime UI code does not call `UiMessage.literal`; known auth/privacy/commitment failures stay resource-backed and fail fast on invalid message construction
- [x] Source detail differentiates healthy connected actions from recovery-only error actions
- [x] Korean locale keeps app-authored product copy in Korean while allowing provider/proper nouns
- [x] Initial sync disabled skip and transition failure copy are covered by local UI tests
- [x] Onboarding source connection state fixtures cover idle / partial / failed / skipped / connected permutations
- [x] Login keeps Google as the first provider-native action and separates email/password as a secondary path
- [x] Screenshot smoke matrix: 360x800 Korean, 430x932 Korean, tablet, font scale 1.3, dark theme. Captured under `docs/ui-smoke-screenshots/20260507-1220-ko/`.
- [x] Emulator smoke after surface/wire refactor: Today / People / Commitments render without crash on `emulator-5554`; commitments FAB uses compact icon-only form so it does not cover card text. Captured under `docs/ui-smoke-screenshots/20260507-refactor-check/`.
- [x] Emulator smoke after live QA fixes: People matching banner, source list/detail error copy, and person detail timeline render in Korean without raw source error messages or artifact filenames. Captured under `docs/ui-smoke-screenshots/20260507-live-emulator-fixes/`.
