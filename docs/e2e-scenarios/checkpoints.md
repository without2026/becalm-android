# E2E Checkpoints

This file is the rollout plan for turning the 72 scenario catalog into runnable
tests. A checkpoint is small enough to keep green independently and broad enough
to cover one product surface end to end.

## Checkpoint 1 — Auth, First Run, Onboarding Shell

Scenarios: `E2E-001..014`

Primary layers:
- `androidTest`: terms, login, splash routing, compact onboarding setup, permission-copy shells.
- `testDebugUnitTest`: auth route resolver, onboarding state projection, bootstrap scheduling.
- `manual adb`: real Google sign-in cancellation/success, runtime permission grant/deny.

Exit criteria:
- First-run Korean terms can be shown and gated.
- Terms acceptance routes to login.
- Signed-in route resolution bypasses auth correctly.
- Compact onboarding can complete or skip optional setup without blocking main app entry.
- Permission grant/deny paths leave the app in a recoverable state.

## Checkpoint 2 — Sources And Settings Parity

Scenarios: `E2E-015..023`

Primary layers:
- `androidTest`: source list, source detail, settings source actions.
- `testDebugUnitTest`: source connection projection, source sync port, disconnect behavior.
- `manual adb`: real Gmail/Outlook/Calendar OAuth and Naver IMAP credential smoke.

Exit criteria:
- Each source can be represented as connected, skipped, failed, or disconnected.
- Onboarding source state and Settings source state match.
- Disconnect/reconnect actions map to the correct source-specific path.

## Checkpoint 3 — Sync And Evidence Import

Scenarios: `E2E-024..038`

Primary layers:
- `androidTest`: global evidence import sheet and supported file entry points.
- `testDebugUnitTest`: cold sync state machine, source import repository, upload worker state machine.
- `manual adb`: MediaStore call recording fixture, screenshot picker fixture.

Exit criteria:
- Cold sync starts, can be skipped, and recovers from backend timeout.
- Batch and single extraction paths use the same normalized source input contract.
- Meeting audio, screenshot, and voice inputs enqueue the correct workers. Transcript/manual-text bypasses remain unavailable.

## Checkpoint 4 — Extraction Pipeline And Person Graph

Scenarios: `E2E-039..052`

Primary layers:
- `testDebugUnitTest`: source extraction input adapter, person identity resolver, person detail projector.
- `local integration`: backend mail participant pipeline, structured extraction persister, manual match pipeline.
- `androidTest`: People list, person detail, raw source detail.

Exit criteria:
- Email, calendar, meeting, screenshot, manual text, and voice converge into one source-neutral extraction shape.
- Participants and commitments persist atomically.
- People list stays contact-like while person detail shows work context.
- Decision context enriches memory without becoming primary feed clutter.

## Checkpoint 5 — Commitments, Today, Notifications

Scenarios: `E2E-053..062`

Primary layers:
- `androidTest`: commitments feed, edit sheets, Today timeline.
- `testDebugUnitTest`: commitment state machine, edit validator, reminder scheduling, overdue sweep.
- `manual adb`: notification post/tap smoke.

Exit criteria:
- Feed filters and card actions are stable.
- Edits are local source-of-truth and mirror through REST CRUD.
- Today shows due work correctly.
- Exact-time reminders fire; no-time items do not.

## Checkpoint 6 — Memory, Privacy, Resilience

Scenarios: `E2E-063..072`

Primary layers:
- `local integration`: person memory generation/upload, retention sweep, privacy exporter.
- `androidTest`: privacy screens, source management screens, deep-link routing shell.
- `manual adb`: process-death and WorkManager retry smoke.

Exit criteria:
- Person memory remains local-first and retryable when upload fails.
- Raw originals can be deleted by date without breaking projections.
- Privacy exports/logs are readable and local-safe.
- Deep links and process death recover without duplicate source events.
