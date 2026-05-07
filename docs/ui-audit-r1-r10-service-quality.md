# BeCalm Android UI Audit: R1-R10 Service Quality Plan

Date: 2026-05-07 KST
Scope: auth, onboarding, source connection/status, People, Person Detail, Today, Commitments, Settings, shared components, verification
Target quality bar: Pickle.ai-level warmth, Granola-level calm information hierarchy, Dex-like person memory, without CRM/dashboard clutter.

## Audit Position

The current UI problem is not only visual polish. The deeper issue is that unrelated surfaces use the same visual grammar:

- A legal consent card, a source status row, a person row, a timeline event, and a settings item all look like equivalent glass cards.
- People list exposes work context before the user opens a person, which violates the contact-list mental model and privacy boundary.
- Source health, onboarding connection state, sync state, and action state are modeled separately and rendered inconsistently.
- Several screens still expose raw wire labels, hardcoded Korean copy, or process-oriented system language.

The quality target is a calm relationship assistant:

- First screen should feel person-first, not source-first.
- Contact list should behave like contacts: identity only, no work content.
- Detail screens should carry context, evidence, commitments, and next actions.
- Source and sync health should be visible but quiet unless user action is needed.
- Color should express state consistently and never be the only carrier of state.

## Global Non-Negotiables

1. People list privacy boundary: no snippets, work summaries, commitment titles, email bodies, call summaries, or meeting content in list rows.
2. Raw source strings must not reach user-facing labels. All source/provider labels come from one resource-backed mapping.
3. State must be centralized: connection, sync, source health, urgency, and disabled/skipped states need shared presentation tokens.
4. Glass is not the default row style. Reserve glass for relationship cards, detail evidence cards, and focused action panels.
5. Color cannot be the only status signal. Every state needs label and icon/dot/position.
6. Korean copy must be natural business Korean, not raw enum translations.
7. Auth/onboarding should move users to the main app quickly while maintaining consent clarity.
8. Main surfaces should not narrate internal processing unless the user is repairing a source or waiting on a selected action.
9. App chrome, labels, CTAs, status, empty states, errors, and helper text are Korean-first in the Korean market build. Original user/source content keeps its original language. English remains only for proper nouns, provider names, protocol terms, and original source evidence.

## R1: Auth And Legal Entry

### Current Problems

- `TermsScreen` still contains debug logging (`TermsDebug`) and layout logging. This should never ship in a polished product surface.
- Legal content is presented as one large notice block. It is technically present but not scannable.
- Login is a generic form card. It does not set the relationship-memory expectation before asking for credentials.
- Google login is rendered as a generic secondary BeCalm button, not the recognizable Google sign-in card/button pattern users already trust.
- Auth surfaces use the same glass-card treatment as app content, so legal/login surfaces feel like yet another settings panel.

### Code Pointers

- `android/app/src/main/java/com/becalm/android/ui/auth/TermsScreen.kt`
- `android/app/src/main/java/com/becalm/android/ui/auth/LoginScreen.kt`
- `android/app/src/main/res/values-ko/strings.xml`

### Required UI Changes

- Remove all auth UI debug logs and layout logs.
- Replace the generic Google login button with a Google-standard sign-in card/button treatment:
  - Google "G" mark at the leading edge.
  - White/neutral card surface with Google-style spacing.
  - Korean label such as "Google로 계속하기".
  - Disabled/loading state that still preserves provider identity.
  - Accessibility label that says Google login clearly.
- Replace the single legal paragraph block with a compact consent checklist:
  - Required: terms and privacy.
  - Required: local-first processing summary.
  - Expandable: detailed PIPA notice.
- Keep one primary CTA and one quiet decline action.
- Add a one-sentence Korean product framing on login:
  - Example direction: "사람별 대화와 약속을 조용히 정리합니다."
- Keep auth visually quieter than main content. Use a plain constrained surface, not heavy repeated glass.

### Acceptance Criteria

- New user can understand why the app exists before signing in.
- Legal text is scannable within 10 seconds.
- No debug log strings remain in auth UI.
- Login has one visual focus: credential action.
- Google login looks provider-native enough that users recognize it as the trusted Google account path, not an app-made secondary action.
- Screenshot review on 360dp and 430dp widths shows no cramped legal controls.

## R2: Onboarding Setup Flow

### Current Problems

- The compact onboarding direction is correct, but the screen still reads like a long checklist of equivalent cards.
- Required, recommended, and optional items have similar visual weight.
- Permission rows and source rows share almost the same visual grammar, even though they mean different things.
- Completed/skipped rows keep disabled actions visible, making the screen feel unfinished.
- Per-row skip/connect repetition increases decision fatigue.
- Initial sync / cold sync is a fragile first-run moment. The current screen is mostly a spinner and one button; it does not show per-source progress, why a button is disabled, or what happened after a button tap.
- The current cold sync button path can feel non-responsive: `onSkipForNow` returns early while disabled, transition failures only log warnings, and the UI does not surface a pressed/transitioning/failure state.

### Code Pointers

- `android/app/src/main/java/com/becalm/android/ui/onboarding/OnboardingSetupScreen.kt`
- `android/app/src/main/java/com/becalm/android/ui/onboarding/OnboardingSourcesScreen.kt`
- `android/app/src/main/java/com/becalm/android/ui/onboarding/OnboardingSourceConnectionModels.kt`
- `android/app/src/main/java/com/becalm/android/ui/onboarding/ColdSyncScreen.kt`
- `android/app/src/main/java/com/becalm/android/ui/today/ColdSyncViewModel.kt`

### Required UI Changes

- Split setup content into three visual densities:
  - Required summary: compact, non-card band, already done.
  - Recommended permissions: small rows with clear "권장" status and short copy.
  - Optional sources: connection rows with provider identity and stronger CTA.
- Collapse terminal rows:
  - Connected: compact row with check icon and "연결됨".
  - Skipped: compact row with muted icon and "나중에 설정".
  - Failed: expanded row with recovery CTA.
- Keep the main completion CTA sticky at the bottom where possible.
- Use one sentence per item; move legal detail into expandable text or secondary detail.
- Replace generic `AssistChip` with state-aware setup/source status pill.
- Redesign initial sync as a service-quality transition screen:
  - Headline: "처음 데이터를 준비하고 있어요".
  - Explain that the user can enter the app while longer sync continues.
  - Show per-source rows: connected/syncing/failed/skipped/user profile.
  - Show disabled skip reason during the first 5 seconds: "곧 앱으로 이동할 수 있어요".
  - On tap, immediately show pressed/loading/transitioning state.
  - On transition failure, show inline error and retry/continue option instead of only logging.
  - Never leave the user with a button that appears tappable but produces no visible result.

### Acceptance Criteria

- User can reach Today after 1-2 decisions if they choose to skip optional setup.
- Connected/skipped rows no longer show disabled primary buttons.
- Recommended permissions do not visually compete with source OAuth rows.
- Failed source clearly shows what to do next without reading snackbar history.
- Initial sync button taps always produce visible feedback: loading, navigation, disabled reason, or inline error.
- Initial sync per-source progress matches `.spec/cold-sync.spec.yml` rather than a single undifferentiated spinner.

## R3: Unified State And Color System

### Current Problems

- `SourceConnectionState`, `StepStatus`, `SourceSyncStatus`, and source status strings are mapped in separate places.
- `SYNCING` currently maps to healthy `Ok`, which hides active work.
- `NEVER_CONNECTED` maps to `Unknown`, which is semantically wrong for user UI.
- Onboarding state chips use default Material styling, while source management uses a dot indicator.
- State color semantics are underused and inconsistent.

### Code Pointers

- `android/app/src/main/java/com/becalm/android/ui/components/SourceStatusIndicator.kt`
- `android/app/src/main/java/com/becalm/android/ui/onboarding/OnboardingSourcesScreen.kt`
- `android/app/src/main/java/com/becalm/android/ui/main/MainTabSyncState.kt`
- `android/app/src/main/java/com/becalm/android/ui/theme/BecalmColors.kt`

### Required UI Changes

- Create a single presentation layer for source/connect state:
  - `Connected`
  - `Syncing`
  - `NeedsReconnect`
  - `Stale`
  - `Disconnected`
  - `Skipped`
  - `ConsentRequired`
  - `Disabled`
- Each state owns:
  - Korean label.
  - Icon or dot.
  - Fill color.
  - Border color.
  - Text color.
  - Recommended CTA label.
  - Accessibility description.
- Update source sync mapping:
  - `CONNECTED` -> Connected
  - `SYNCING` -> Syncing
  - `ERROR` -> NeedsReconnect
  - `NEVER_CONNECTED` -> Disconnected
  - stale timestamp/cursor state -> Stale
- Reserve red for action-required failures, amber for stale/attention, green only for explicit success.

### Acceptance Criteria

- Same state looks the same in onboarding, settings source list, source detail, Today header, and status strip.
- `SYNCING` is never shown as healthy connected.
- `NEVER_CONNECTED` is never shown as unknown.
- Every state is understandable in grayscale through label/icon.

## R4: Source Identity Badges

### Current Problems

- Email providers all look nearly identical. Gmail, Outlook, Naver, and Daum share the same icon and same container color.
- Source detail titles use raw source strings with `replaceFirstChar`.
- Source labels are spread across screens instead of a single source presentation map.
- Provider identity is either too weak or too raw.

### Code Pointers

- `android/app/src/main/java/com/becalm/android/ui/components/EventSourceBadge.kt`
- `android/app/src/main/java/com/becalm/android/ui/sources/SourcesListScreen.kt`
- `android/app/src/main/java/com/becalm/android/ui/sources/SourceDetailScreen.kt`
- `android/app/src/main/java/com/becalm/android/ui/components/SourceStatusStrip.kt`

### Required UI Changes

- Build `SourcePresentation` as the only UI mapping for source type:
  - Display name.
  - Short label.
  - Icon.
  - Tiny identity mark color.
  - Family: email, calendar, call, meeting, contact, manual.
- Use provider marks sparingly:
  - Gmail: small red mark.
  - Outlook: small blue mark.
  - Naver: small green mark.
  - Daum: small amber/yellow mark.
  - Voice: mic.
  - Call recording: phone.
  - Calendar: calendar icon with provider mark.
- Replace raw title generation in source list/detail with resource-backed presentation.
- Keep badge fill neutral; only the tiny mark/icon carries provider identity.

### Acceptance Criteria

- No user-facing source title uses raw `sourceType`.
- Source badges are distinguishable at a glance without turning the UI into a color-coded dashboard.
- Unknown source renders safe fallback but is visibly "알 수 없는 출처".
- KO/EN labels come from resources.

## R5: People Tab As Contacts

### Current Problems

- People row currently renders `lastInteractionSnippet`, which exposes work context in the list.
- Pending commitment badge makes the list feel like a task feed.
- The block action is always visible, which is too destructive and visually noisy.
- Sections like pending/recent push the screen toward a CRM/task dashboard rather than contacts.
- Person identity fields are compressed into a single headline string.

### Code Pointers

- `android/app/src/main/java/com/becalm/android/ui/persons/PersonsScreen.kt`
- `android/app/src/main/java/com/becalm/android/ui/persons/PersonsViewModel.kt`
- `android/app/src/main/java/com/becalm/android/ui/persons/PersonsScreenProjectionPort.kt`
- `android/app/src/main/java/com/becalm/android/ui/persons/PersonsUiProjector.kt`

### Required UI Changes

- Remove work context from People list:
  - Do not render `lastInteractionSnippet`.
  - Do not render commitment titles.
  - Do not render email/call/meeting content.
- Row structure:
  - Avatar.
  - Name.
  - Company/title or email/phone fallback.
  - Tiny metadata line: email/phone availability, last interaction date, source count.
  - Optional quiet attention dot/count for open work, no content text.
- Move block action into overflow or person detail settings.
- Default grouping should feel like contacts:
  - Recent first or alphabetical.
  - Search includes all contact identities.
  - Attention indicators should not reorder the whole mental model unless explicitly filtered.
- Keep unassigned matching banner, but make it a quiet inbox-style banner, not a warning-heavy error state unless matching is blocking extraction quality.

### Acceptance Criteria

- A screenshot of People list reveals no work content.
- A person row can be understood as a contact row.
- Opening a row is the only way to see relationship/work context.
- Destructive actions are not visible by default.
- Tests assert `lastInteractionSnippet` is not rendered in People list.

## R6: Person Detail As Relationship Brief

### Current Problems

- Person detail has the right information but lacks clear hierarchy.
- Source event cards render source, snippet, give/take/schedule, extraction count, and next action at nearly the same level.
- Next action is inline text (`"다음:"`) rather than a recommendation panel.
- Some Korean copy is hardcoded in composables.
- The timeline filter row appears before the user sees the most important current relationship summary.

### Code Pointers

- `android/app/src/main/java/com/becalm/android/ui/persons/PersonDetailScreen.kt`
- `android/app/src/main/java/com/becalm/android/ui/persons/PersonHeader.kt`
- `android/app/src/main/java/com/becalm/android/ui/persons/InteractionHistoryRow.kt`
- `android/app/src/main/java/com/becalm/android/ui/persons/PersonDetailProjector.kt`

### Required UI Changes

- Reframe detail as a relationship brief:
  - Header: identity + contact info + interaction counts.
  - Open loops: what user needs to do, what counterpart owes, schedules.
  - Recommended next action panel, if available.
  - Timeline by source event.
- Move `nextAction` out of each card body into a recommendation panel when meaningful.
- Keep source evidence inspectable but secondary.
- Replace hardcoded strings with resources:
  - "내가 해야 할 일"
  - "상대가 해야 할 일"
  - "다음: ..."
- Use card density:
  - Brief/recommendation: elevated glass.
  - Timeline event: simple row/card.
  - Evidence chips: neutral.

### Acceptance Criteria

- User can answer "what should I do with this person?" within 5 seconds.
- Timeline still allows source inspection.
- No hardcoded Korean user-facing copy remains in Person Detail composables.
- Next action has a clear approve/edit/dismiss affordance when actionable.

## R7: Today And Commitments

### Current Problems

- Today and Commitments both show source/sync/system header elements that can compete with the actual daily work.
- Commitment filters are functional but can feel like database filters rather than work recovery modes.
- Commitment cards are source/context-heavy and can overload a mobile scan.
- Pull-to-refresh and sync indicators risk exposing process noise on the primary surface.

### Code Pointers

- `android/app/src/main/java/com/becalm/android/ui/today/TodayTimelineScreen.kt`
- `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt`
- `android/app/src/main/java/com/becalm/android/ui/components/CommitmentCard.kt`
- `android/app/src/main/java/com/becalm/android/ui/components/MainTabHeader.kt`

### Required UI Changes

- Today should become a compact "things to handle today" timeline:
  - Time/date.
  - Person.
  - Action type: 내가 할 일 / 상대가 할 일 / 일정.
  - One short title.
  - Due/urgency.
- Source evidence should be one small chip, not first-class visual content.
- Commitments tab should feel like a recovery feed:
  - Latest/overdue first.
  - Person visible.
  - Action state visible.
  - Source evidence secondary.
- Replace database-like filters with user modes:
  - 전체
  - 내가 할 일
  - 기다리는 일
  - 일정
  - 완료/보류
- Sync/source headers should collapse to a quiet status row unless attention is required.

### Acceptance Criteria

- Today can be scanned while walking.
- Commitment cards do not expose raw direction/status values.
- Source evidence is available but visually secondary.
- Failed/stale source warnings appear only when actionable.

## R8: Source Management And Settings

### Current Problems

- Source list/detail feels like infrastructure rather than a calm recovery path.
- Detail status card mixes status, last sync, count, error, reconnect, sync, and disconnect in one panel.
- Disconnect is too close to routine recovery actions.
- Source detail titles can expose raw source ids.
- Settings surfaces overuse card rows, reducing distinction between privacy, account, sources, and dangerous actions.

### Code Pointers

- `android/app/src/main/java/com/becalm/android/ui/sources/SourcesListScreen.kt`
- `android/app/src/main/java/com/becalm/android/ui/sources/SourceDetailScreen.kt`
- `android/app/src/main/java/com/becalm/android/ui/settings/SettingsScreen.kt`
- `android/app/src/main/java/com/becalm/android/ui/settings/PrivacyManagementScreen.kt`

### Required UI Changes

- Source list row hierarchy:
  - Provider name.
  - Current state.
  - Last successful sync.
  - Recovery CTA only when needed.
- Source detail structure:
  - Status summary panel.
  - Recovery action panel, only if broken/stale.
  - Manual sync/import controls.
  - Danger zone separated at bottom.
- Disconnect must be in a danger zone, not near sync/reconnect.
- Settings should use sectioned plain lists, with glass only for critical privacy/account panels.
- Privacy management copy should emphasize control:
  - Local data.
  - Source archive deletion.
  - Consent withdrawal.
  - Account deletion.

### Acceptance Criteria

- Healthy sources feel quiet.
- Broken sources make the next action obvious.
- Disconnect is visually separated and confirmation remains explicit.
- Settings no longer looks like a wall of equivalent cards.

## R9: Design System And Shared Components

### Current Problems

- `glassPanel` is used as a general-purpose container across too many screens.
- Button variants, chips, badges, status indicators, and cards do not yet form a complete service-level system.
- Source, state, direction, urgency, and approval colors live in several component-specific choices.
- Text hierarchy is broadly good but often used mechanically rather than semantically.

### Code Pointers

- `android/app/src/main/java/com/becalm/android/ui/theme/Color.kt`
- `android/app/src/main/java/com/becalm/android/ui/theme/BecalmColors.kt`
- `android/app/src/main/java/com/becalm/android/ui/theme/Type.kt`
- `android/app/src/main/java/com/becalm/android/ui/theme/GlassModifiers.kt`
- `android/app/src/main/java/com/becalm/android/ui/components/`

### Required UI Changes

- Define component roles:
  - `RelationshipCard`
  - `ContactRow`
  - `EvidenceCard`
  - `RecommendationPanel`
  - `StatusPill`
  - `SourceBadge`
  - `DangerZone`
- Reduce glass usage:
  - Contact rows: flatter, lower shadow.
  - Timeline events: light container or hairline row.
  - Recommendation panels: elevated glass.
  - Legal/settings rows: mostly plain sections.
- Add semantic state tokens:
  - Success/connected.
  - Syncing/progress.
  - Stale/attention.
  - Error/reconnect.
  - Skipped/disabled.
  - Consent required.
- Create screenshot previews or preview parameter sets for every stateful component.

### Acceptance Criteria

- Designers/developers can choose a component by role, not by manually stacking modifiers.
- No new screen should directly create status colors.
- No new screen should directly map source ids to display labels.
- Glass hierarchy is visible from screenshots: not everything floats equally.

## R10: Verification, Tests, And Quality Gates

### Current Problems

- UI tests cover compile and some behavior, but not enough visual/privacy invariants.
- There is no screenshot-based review gate for the new service-quality bar.
- State permutations are not systematically tested.
- Korean localization quality is not validated as part of UI acceptance.
- The app lacks an explicit language-boundary test: original source content may stay English, but app-authored UI around it should be Korean in the Korean market build.
- Initial sync currently lacks UI tests that prove buttons produce visible feedback and transition failures are surfaced.

### Code Pointers

- `android/app/src/androidTest/java/com/becalm/android/ui/`
- `android/app/src/test/java/com/becalm/android/`
- `docs/ui-test-checklist.md`
- `docs/ui-verification-map.html`

### Required UI Changes

- Add UI invariants:
  - People list does not render work snippets.
  - Raw source ids do not render in source list/detail.
  - App-authored UI strings on Korean locale are Korean except approved proper nouns/provider names.
  - Original source evidence preserves original language and is not machine-translated by presentation components.
  - Syncing and connected states render differently.
  - Disconnected and unknown states render differently.
  - Failed source has recovery CTA.
  - Healthy source has no aggressive warning CTA.
  - Initial sync disabled skip button explains why it is disabled.
  - Initial sync transition failure renders an inline error or retry path.
- Add screenshot review matrix:
  - 360x800 Korean.
  - 430x932 Korean.
  - Tablet width.
  - Font scale 1.3.
  - Dark mode smoke only, not canonical.
- Add state fixtures:
  - Auth empty/error/loading.
  - Onboarding all idle/partial/failed/all skipped/all connected.
  - Sources healthy/stale/error/disconnected/syncing.
  - People with contact-only rows.
  - Person detail with no data, open loops, many sources, failed source evidence.
  - Today empty, overloaded, overdue, source warning.
- Add copy review checklist:
  - No raw enum text.
  - No process-first internal language.
  - No hardcoded Korean in composables.
  - No English fallback on Korean locale for core routes.
  - Original source content is not translated in the UI layer.
  - Provider/proper nouns such as Google, Gmail, Outlook, Naver, Daum, Vertex AI remain as approved names.

### Acceptance Criteria

- `compileDebugAndroidTestKotlin` remains green.
- Unit tests enforce privacy and source-label invariants.
- Screenshot review catches overlap, cramped buttons, and unreadable status colors.
- Every round has before/after screenshots attached to PR description.

## Recommended Implementation Order

1. R3 first: create centralized state presentation. This prevents every later round from inventing colors.
2. R4 next: create centralized source presentation. This removes raw labels and enables consistent badges.
3. R5 next: fix People privacy boundary. This is the most product-critical UI bug.
4. R2 next: onboarding becomes calmer once state/source components exist.
5. R6 next: person detail becomes the service-quality relationship brief.
6. R7 and R8 after that: Today/Commitments/Sources benefit from the shared components.
7. R1 can run independently but should happen before external user testing.
8. R9 should be done incrementally while implementing R2-R8.
9. R10 should be active from R3 onward, not saved until the end.

## Definition Of Done For The Full Audit

- People list is contact-only and privacy-safe.
- Person detail is the only place where relationship/work context appears by default.
- Source health has one coherent state language across onboarding, Today, Settings, and detail.
- Source badges distinguish provider identity without dominating the UI.
- Auth/onboarding reaches main app with fewer internal full-screen steps and clearer Korean copy.
- Main app feels like a relationship assistant, not a source dashboard.
- All user-visible labels are resource-backed.
- All critical states have visual, textual, and accessibility carriers.
- UI tests and screenshot review protect the new boundaries.
- Initial sync screen has visible, test-covered feedback for every button state.
