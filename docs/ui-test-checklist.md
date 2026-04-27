# Local UI Test Checklist

Updated: 2026-04-27

Purpose:
- Keep `src/test` local UI coverage separate from execution status.
- Mark a surface as covered only when a corresponding local JVM UI test source exists.
- Mark a command as passed only when this session actually ran it.

Scope:
- local JVM UI tests only (`android/app/src/test`)
- Compose semantics / rendering / CTA / section visibility / dialog-visible content
- excludes `androidTest` route wiring, emulator runtime, OS permission dialogs, and live provider loops

## Execution Snapshot

Executed on clean branch `qa/verification-runtime-status`.

- `PASS` `cd android && ./gradlew :app:testDebugUnitTest`
- `PASS` `cd android && ./gradlew :app:compileDebugAndroidTestKotlin`
- `PENDING` `cd android && ./gradlew :app:connectedDebugAndroidTest`
  - Not executed in this session. `adb` was not available in the execution environment.
- `LIVE COMPLETE` voice, `google_calendar`, `outlook_calendar`, `gmail`, `outlook_mail`
- `LIVE PENDING` `naver_imap`, `daum_imap`

## Local Prerequisites

- `android/local.properties` is required for `sdk.dir`
- staging/live provider smoke also needs the relevant `local.properties` keys
- `android/gradle.properties` is not required in the current setup
- `adb` and a booted emulator are required for `connectedDebugAndroidTest` and manual runtime smoke

## Coverage Checklist

Exit condition for this checklist:
- every item below has a corresponding local JVM UI test source
- `:app:testDebugUnitTest` is green on the branch being verified

### Auth
- [x] `SplashScreen` branding / signed-out gate surface
- [x] `LoginScreen` empty validation / CTA enabled state / Google CTA disabled state
- [x] `TermsScreen` notice / accept gate / decline CTA

### Today
- [x] `TodayTimelineContent` loading / empty / error / populated states
- [x] `TodayTimelineContent` processing paused banner / settings CTA
- [x] `OverallSyncIndicator` idle / syncing / synced / partial-failure labels
- [x] `SourceStatusStrip` seven-source labels and state surfacing

### Persons
- [x] `PersonsScreenContent` offline badge / unassigned section / enriched row meta
- [x] `PersonDetailScreenContent` pending/completed/history sections / action-count badge / event tap
- [x] `UnassignedEventsScreen` list / empty state
- [x] `RawEventDetailSheet` base sections / attachment count / extracted action count / body expand-collapse

### Sources
- [x] `SourcesListScreenContent` contacts pseudo-source / empty state / row tap
- [x] `SourceDetailScreenContent` reconnect / sync / disconnect / confirm dialog
- [x] `ContactsSourceDetailScreen` summary and permission recovery surfacing

### Settings / Privacy
- [x] `SettingsScreenContent` processing paused banner / sources / privacy / wipe / sign-out note
- [x] `PrivacyManagementScreenContent` export / withdraw / pause / delete / activity-log cards
- [x] `ConsentWithdrawScreen` consent toggles visibility
- [x] `ProcessingPauseScreen` pause description / switch / started-at label / confirm dialog
- [x] `AccountDeletionScreen` warning counts / email field / keyword field / delete CTA
- [x] `ActivityLogScreen` empty state / populated log rows

### Commitments
- [x] `CommitmentManagementScreenContent` loading / empty / populated states
- [x] `CommitmentManagementScreenContent` filter chips / completed-cancelled sections / FAB / detail tap
- [x] `CommitmentDetailSheet` source / history / action-state chips
- [x] `CommitmentCreateSheet` required inputs / direction / save CTA
- [x] `CommitmentEditSheet` initial values / save CTA / delete affordance

### Onboarding
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

## Automation Commands

```bash
cd android
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:connectedDebugAndroidTest
```

## Emulator Smoke Order After Black Screen Fix

1. Preflight
   - Ensure the black screen fix branch is merged/rebased into the branch under test.
   - Ensure `adb devices` shows a booted emulator.
   - Cold-start the app once and verify Splash resolves to a non-black route.
2. Automated connected suite
   - Run `cd android && ./gradlew :app:connectedDebugAndroidTest`
   - Do not mark emulator verification complete unless this command returns green.
3. Manual QA sweep
   - Auth: Splash → Terms/Login route resolution
   - Onboarding: PIPA → recording folder → contacts → email PIPA → provider connect → battery → cold sync
   - Main tabs: Today, Persons, Commitments
   - Leaf routes: Person Detail, Raw Event Detail, Sources List, Source Detail, Contacts Source Detail, Settings, Privacy subroutes
4. External provider smoke
   - Re-run only `naver_imap` and `daum_imap` as required blockers
   - Gmail / Outlook / Calendar / voice remain reference-complete unless branch changes touched those integrations

## Remaining QA Closures

- `connectedDebugAndroidTest` full green on emulator runtime
- manual emulator smoke after the black screen fix lands
- `naver_imap` live smoke
- `daum_imap` live smoke
