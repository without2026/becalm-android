# E2E Scenario Catalog

This directory defines the user-facing E2E scenarios that should guide adb,
Compose, local integration, and backend contract verification.

The catalog intentionally focuses on critical flows, not every edge-case error
message. Each scenario must be independently runnable: it owns its test data,
does not depend on previous scenario state, and declares its automation mapping.

## Files

- `critical-user-scenarios.feature` — Gherkin scenarios for the current BeCalm
  full user journey catalog. It intentionally includes more scenarios than are
  currently automated so missing coverage can be tracked by stable E2E IDs.
- `checkpoints.md` — implementation checkpoints for adding runnable coverage
  without turning the catalog into one fragile test suite.

## Automation Policy

- `@happy` scenarios are the golden paths to run first during adb/device smoke.
- `@error` scenarios cover common user-visible failure paths only.
- Each scenario includes `# Automation:` with the current verification layer:
  `androidTest`, `testDebugUnitTest`, backend `pytest`, or manual `adb/logcat`.
- LLM output quality is not asserted in E2E. E2E uses fixture/contract responses
  and verifies persistence, routing, worker scheduling, and UI state.

## Scenario Groups

The current catalog contains 73 independent scenarios:

- `E2E-001..008` — auth, first-run, sign-out, local data wipe.
- `E2E-009..014` — onboarding, PIPA, contacts, notifications, source setup skip.
- `E2E-015..023` — source connection, source settings parity, disconnect.
- `E2E-024..029` — cold sync, foreground refresh, batch/single upload.
- `E2E-030..038` — evidence import: meeting audio, screenshot, voice, and blocked transcript/manual-text bypasses.
- `E2E-039..045` — source-normalized extraction pipeline.
- `E2E-046..052` — person matching, people list, person detail, raw source detail.
- `E2E-053..060` — commitments, edit/CRUD, Today.
- `E2E-061..064` — notifications and person memory.
- `E2E-065..072` — privacy, settings, source management, deep links, process death.
- `E2E-073` — meeting audio speaker review, person matching, and local memory smoke.

## Current Device Smoke Order

When a device appears in `adb devices -l`, run the smoke scenarios in this order:

1. `E2E-001` first-run Korean terms shell.
2. `E2E-002` terms acceptance to login.
3. `E2E-009` compact onboarding setup shell.
4. `E2E-014` skip-all-sources app entry.
5. `E2E-022` source setup/settings parity.
6. `E2E-030`, `E2E-033` evidence import entry points, plus blocked-path checks for transcript/manual text.
7. `E2E-073` meeting speaker review and person memory smoke via
   `qa/emulator/scripts/verify_meeting_speaker_matching_qa.sh`.
8. `E2E-049`, `E2E-050`, `E2E-053`, `E2E-058` main tab rendering.
9. `E2E-008` wipe-local-data regression.

Use logcat filters:

```bash
adb logcat -c
adb logcat | rg 'AndroidRuntime|BeCalmDatabase|PersonIndexWorker|ProfileMemoryWorker|WorkScheduler|Railway|Supabase'
```
