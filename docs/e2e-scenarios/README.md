# E2E Scenario Catalog

This directory defines the user-facing E2E scenarios that should guide adb,
Compose, local integration, and backend contract verification.

The catalog intentionally focuses on critical flows, not every edge-case error
message. Each scenario must be independently runnable: it owns its test data,
does not depend on previous scenario state, and declares its automation mapping.

## Files

- `critical-user-scenarios.feature` — Gherkin scenarios for the current BeCalm
  happy paths and common error paths.

## Automation Policy

- `@happy` scenarios are the golden paths to run first during adb/device smoke.
- `@error` scenarios cover common user-visible failure paths only.
- Each scenario includes `# Automation:` with the current verification layer:
  `androidTest`, `testDebugUnitTest`, backend `pytest`, or manual `adb/logcat`.
- LLM output quality is not asserted in E2E. E2E uses fixture/contract responses
  and verifies persistence, routing, worker scheduling, and UI state.

## Current Device Smoke Order

When a device appears in `adb devices -l`, run the smoke scenarios in this order:

1. `E2E-001` auth/onboarding shell.
2. `E2E-002` unified source setup/settings parity.
3. `E2E-003` meeting manual transcript import.
4. `E2E-005` person matching confirmation.
5. `E2E-006` person memory worker enqueue/write/upload attempt.
6. `E2E-007` wipe-local-data regression.

Use logcat filters:

```bash
adb logcat -c
adb logcat | rg 'AndroidRuntime|BeCalmDatabase|PersonIndexWorker|ProfileMemoryWorker|WorkScheduler|Railway|Supabase'
```
