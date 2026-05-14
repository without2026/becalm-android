# Android 8.5 Reviewer Request

Date: 2026-05-15 KST
Current `main`: `ee7df60`
Executable code verified at: `a70d273`

This document is intentionally not a self-score. The reviewer should assign the
score against the agreed rubric.

## Scope

Included in this readiness pass:

- Functional requirements.
- Non-functional requirements.
- Code quality.
- UX / product readiness.
- Release/CI hardening needed to support those areas.

Explicitly excluded by user direction:

- Firebase Crashlytics SDK wiring.
- Amplitude SDK/client instrumentation.

## Evidence Checklist

| Rubric Area | Evidence | Status |
|---|---|---|
| Functional requirements | `Android Tests` run `25875177207`; `unit-tests`, `instrumented-tests`, and `release-smoke` jobs succeeded | Ready for reviewer |
| Core user flows | `AuthCheckpoint1E2eTest`, `OnboardingCheckpoint1E2eTest`, `SourceConnectionsCheckpoint2E2eTest`, `PeoplePipelineCheckpoint4E2eTest`, `HappyPathFullJourneyE2eTest` exist and are covered by CI emulator instrumentation | Ready for reviewer |
| Source recovery UX | Source recovery changes are in `7813678`; raw-event recovery coverage includes `RawEventDetailCheckpoint6E2eTest` | Ready for reviewer |
| Non-functional requirements | `measure_android_readiness.sh` records cold start, memory, frame rows, and fails strict mode on unavailable or over-threshold metrics | Ready for reviewer |
| Crash/ANR/OOM guard | `measure_android_readiness.sh` fails on logcat fatal exception, ANR, OOM, process death, and lowmemorykiller patterns | Ready for reviewer |
| Code quality | `Android Deterministic Gates` run `25875177248` succeeded with spec coverage, assert guard, secret detection, dependency-check task presence, full Android lint, and size check | Ready for reviewer |
| Release smoke | `Android Tests` run `25875177207` succeeded for `assembleRelease lintRelease` and APK size smoke | Ready for reviewer |
| Instrumented tests | `Android Tests` run `25875177207` succeeded for API 33 emulator `connectedDebugAndroidTest` | Ready for reviewer |
| Artifact retention | CI uploaded `android-gate-reports`, `android-unit-test-reports`, `android-instrumented-test-reports`, and `android-release-smoke-reports` | Ready for reviewer |
| Legacy observability vendor removal | Repo-wide grep for the old vendor/runtime-key terminology returns no matches | Ready for reviewer |
| Firebase + Amplitude scope | `.pipeline/platform.yml` declares `firebase_crashlytics_planned` and `amplitude_planned`; readiness docs state these are separate workstream items | Ready for reviewer |
| Observability fallback | `LoggerObservabilityClientSpecTest` verifies logger-backed event capture works without Firebase runtime config and scrubs email/token values | Ready for reviewer |
| PR state | PR #103 was closed as superseded because main contains the useful hardening commits and keeps the stronger full `lint` gate | Ready for reviewer |

## Known Limits For Reviewer

- Firebase App Distribution upload is wired but skipped until protected repository
  secrets are configured.
- Production Play Console deploy path is wired but was not executed because it
  requires protected release secrets and explicit dispatch.
- Firebase Crashlytics and Amplitude are excluded from this pass by user
  direction and should not be counted as missing in this specific review.

## Reviewer Decision

Reviewer should assign scores for the agreed rubric and state whether the
analytics-excluded readiness target is at least 8.5.
