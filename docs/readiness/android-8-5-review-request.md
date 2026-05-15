# Android 8.5 Reviewer Request

Date: 2026-05-15 KST
Evidence document status: docs-only; does not change executable Android code.
Executable code verified at: `b78e190`

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
| Functional requirements | `Android Tests` run `25905324256`; `unit-tests`, `instrumented-tests`, and `release-smoke` jobs succeeded | Ready for reviewer |
| Core user flows | `AuthCheckpoint1E2eTest`, `OnboardingCheckpoint1E2eTest`, `SourceConnectionsCheckpoint2E2eTest`, `PeoplePipelineCheckpoint4E2eTest`, `HappyPathFullJourneyE2eTest` exist and are covered by CI emulator instrumentation | Ready for reviewer |
| Source recovery UX | Source recovery changes are in `7813678`; raw-event recovery coverage includes `RawEventDetailCheckpoint6E2eTest` | Ready for reviewer |
| Non-functional requirements | `measure_android_readiness.sh` records cold start, memory, frame rows, app skipped-frame max, and fails strict mode on unavailable or over-threshold metrics | Ready for reviewer |
| Readiness smoke result | Run `25905324256` measured cold start `1994ms`, total PSS `144766KB`, logcat fatal/ANR/OOM pass, and `readiness_failure_count=0` | Ready for reviewer |
| App install recovery | Run `25905324256` found the app missing after instrumentation, reinstalled `app-debug.apk`, and then completed readiness measurement | Ready for reviewer |
| Crash/ANR/OOM guard | `measure_android_readiness.sh` fails on logcat fatal exception, ANR, OOM, process death, and lowmemorykiller patterns | Ready for reviewer |
| Frame-jank guard | Current working tree records app-pid Choreographer `skipped_frames_max` and fails strict mode over `BECALM_MAX_SKIPPED_FRAMES`, default `60` | Ready for reviewer |
| Source/person business projection | Current working tree verifies schedule-event-link mirror changes enqueue person indexing and duplicate source participants collapse to one rendered person interaction | Ready for reviewer |
| Android 15 / Play target compatibility | App `compileSdk` and `targetSdk` are both `35`; `AndroidBuildWorkflowSpecTest` enforces app/baseline profile SDK alignment; `Android Tests` run `25905324256` passed after Robolectric `4.16.1` update | Ready for reviewer |
| Code quality | `Android Deterministic Gates` run `25905324259` succeeded with spec coverage, assert guard, secret detection, dependency-check task presence, Android lint, and size check | Ready for reviewer |
| Play policy risk control | Battery optimization onboarding uses app settings guidance and no longer declares or invokes restricted battery optimization exemption APIs | Ready for reviewer |
| Release smoke | `Android Tests` run `25905324256` succeeded for `assembleRelease lintRelease` and APK size smoke | Ready for reviewer |
| Instrumented tests | `Android Tests` run `25905324256` succeeded for API 33 emulator `connectedDebugAndroidTest` | Ready for reviewer |
| Latest staging | `Deploy Staging` run `25905319248` succeeded on executable commit `b78e190` | Ready for reviewer |
| Artifact retention | CI uploaded `android-gate-reports`, `android-unit-test-reports`, `android-instrumented-test-reports`, and `android-release-smoke-reports` | Ready for reviewer |
| Audio privacy disclosure | Onboarding/PIPA spec, Korean/English strings, and PIPA tests disclose NAVER Cloud CLOVA Speech plus Google Vertex AI transcript extraction and reject stale audio-modal copy | Ready for reviewer |
| User-facing vendor status removal | Upload and source processing copy uses product language like `내용 정리 중`; repo-wide stale Gemini audio/status grep returns no user-facing matches | Ready for reviewer |
| Firebase + Amplitude scope | `.pipeline/platform.yml` declares `firebase_crashlytics_planned` and `amplitude_planned`; readiness docs state these are separate workstream items | Ready for reviewer |
| Observability fallback | `LoggerObservabilityClientSpecTest` verifies logger-backed event capture works without Firebase runtime config and scrubs email/token values | Ready for reviewer |

Latest local verification after these QA/test hardening changes:

- `bash -n qa/emulator/scripts/measure_android_readiness.sh qa/emulator/scripts/verify_beta_readiness_qa.sh`: pass.
- `./gradlew testDebugUnitTest --tests '*ReadinessQaScriptSpecTest' --tests '*SourceRelationRefreshCoordinatorSpecTest' --tests '*PersonInteractionIndexWorkerLocalIntegrationTest' --no-daemon --console=plain`: pass.

## Known Limits For Reviewer

- Firebase App Distribution upload is wired but skipped until protected repository
  secrets are configured.
- Production Play Console deploy path is wired but was not executed because it
  requires protected release secrets and explicit dispatch.
- Firebase Crashlytics and Amplitude SDK/client instrumentation are excluded
  from this pass by user direction and should not be counted as missing in this
  specific review.

## Reviewer Decision

Reviewer should assign scores for the agreed rubric and state whether the
analytics-excluded readiness target is at least 8.5.

Use `docs/readiness/android-8-5-reviewer-scorecard.md` to record reviewer-only
scores without self-assessment.
