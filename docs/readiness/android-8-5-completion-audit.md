# Android 8.5 Completion Audit

Date: 2026-05-15 KST
Executable code verified at: `a70d273`

Scope: Android beta-readiness hardening excluding Firebase Crashlytics and
Amplitude SDK/client instrumentation. Those observability SDK tasks are tracked
in a separate workstream.

This audit maps the user request to concrete artifacts. It intentionally avoids
self-scoring; the final score is assigned by the reviewer.

## Success Criteria

| Requirement | Evidence |
|---|---|
| Improve beta readiness using the agreed rubric, excluding analytics SDK work | Scope note in `docs/readiness/android-8-5-hardening-checklist.md`; `.pipeline/platform.yml` declares `firebase_crashlytics_planned` and `amplitude_planned` |
| Keep Android deterministic gates green | `Android Deterministic Gates` run `25875177248`, conclusion `success` |
| Keep Android tests green | `Android Tests` run `25875177207`, conclusion `success` |
| Verify emulator instrumentation | `instrumented-tests` job in run `25875177207`, conclusion `success` |
| Verify release smoke | `release-smoke` job in run `25875177207`, conclusion `success` |
| Verify unit tests | `unit-tests` job in run `25875177207`, conclusion `success` |
| Verify backend optional tests | `backend-tests` job in run `25875177207`, conclusion `success` |
| Verify staging deploy does not block main | `Deploy Staging` run `25875168351`, conclusion `success` |
| Preserve failure evidence for CI triage | Artifacts exist for `android-gate-reports`, `android-unit-test-reports`, `android-instrumented-test-reports`, and `android-release-smoke-reports` |
| Enforce beta-readiness performance/logcat smoke criteria | `qa/emulator/scripts/measure_android_readiness.sh` fails by default on unavailable/over-threshold cold start, unavailable/over-threshold PSS, or app fatal/ANR/OOM logcat patterns |
| Prevent long-running CI jobs from hanging indefinitely | `.github/workflows/android-tests.yml` and `.github/workflows/android-gates.yml` define job-level `timeout-minutes`; mirrored in `.pipeline/adapters/android/test.yml` and `.pipeline/adapters/android/gates.yml` |
| Keep workflow templates aligned with executable workflows | `AndroidBuildWorkflowSpecTest` checks action versions, dependency-check fallback, timeouts, and artifact uploads for both `.github/workflows` and `.pipeline/adapters/android` |
| Keep release package identity aligned | `android/app/build.gradle.kts` uses `applicationId = "com.becalm.android"`; deploy config uses `packageName: com.becalm.android` |
| Avoid reverting or hiding analytics requirement | No Firebase/Amplitude SDK implementation is claimed in this audit; remaining SDK instrumentation is explicitly excluded |

## Latest Run Evidence

| Workflow | Run | Commit | Result |
|---|---:|---|---|
| Android Deterministic Gates | `25875177248` | `a70d273` | success |
| Android Tests | `25875177207` | `a70d273` | success |
| Deploy Staging | `25875168351` | `a70d273` | success |

Android Tests job results:

| Job | Result |
|---|---|
| read-config | success |
| unit-tests | success |
| backend-tests | success |
| instrumented-tests | success |
| release-smoke | success |

Artifact evidence:

| Artifact | Size |
|---|---:|
| android-gate-reports | 94,243 bytes |
| android-unit-test-reports | 340,153 bytes |
| android-instrumented-test-reports | 898,708 bytes |
| android-release-smoke-reports | 68,947 bytes |

## Local Verification

| Command | Result |
|---|---|
| `./gradlew lint testDebugUnitTest assembleRelease lintRelease --no-daemon --console=plain` | pass |
| `./gradlew assembleDebugAndroidTest --no-daemon --console=plain` | pass |
| `./gradlew testDebugUnitTest --tests '*LoggerObservabilityClientSpecTest' --no-daemon --console=plain` | pass |
| `./gradlew testDebugUnitTest --tests '*AndroidBuildWorkflowSpecTest' --no-daemon --console=plain` | pass |
| `./gradlew testDebugUnitTest --tests '*ReadinessQaScriptSpecTest' --no-daemon --console=plain` | pass |
| `GITHUB_ACTIONS=true GITHUB_SHA=$(git rev-parse HEAD) python3 .pipeline/core/spec-coverage.py` | pass |
| `actionlint .github/workflows/android-tests.yml .github/workflows/android-gates.yml` | pass |
| `bash -n qa/emulator/scripts/measure_android_readiness.sh qa/emulator/scripts/verify_beta_readiness_qa.sh` | pass |
| `git diff --check` | pass |

## Remaining Explicitly Excluded Work

- Firebase Crashlytics SDK wiring.
- Amplitude SDK/client instrumentation.
- Real Firebase App Distribution upload with protected environment secrets.
- Production Play Console deployment with protected release secrets.

These exclusions match the user instruction that analytics/crash observability
SDK work is handled separately.
