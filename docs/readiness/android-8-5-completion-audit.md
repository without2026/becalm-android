# Android 8.5 Completion Audit

Date: 2026-05-15 KST
Executable code verified at: `0cdd65c`

Scope: Android beta-readiness hardening excluding Firebase Crashlytics and
Amplitude SDK/client instrumentation. Those tasks are tracked in a separate
analytics/observability workstream and are not claimed here.

This audit maps the user request to concrete artifacts. It intentionally avoids
self-scoring; the final score is assigned by the reviewer.

## Success Criteria

| Requirement | Evidence |
|---|---|
| Improve beta readiness using the agreed rubric, excluding analytics SDK work | Scope note in `docs/readiness/android-8-5-hardening-checklist.md`; `.pipeline/platform.yml` declares `firebase_crashlytics_planned` and `amplitude_planned` |
| Keep Android deterministic gates green | `Android Deterministic Gates` run `25880469987`, conclusion `success` |
| Keep Android tests green | `Android Tests` run `25880469962`, conclusion `success` |
| Verify emulator instrumentation | `instrumented-tests` job in run `25880469962`, conclusion `success` |
| Verify release smoke | `release-smoke` job in run `25880469962`, conclusion `success` |
| Verify unit tests | `unit-tests` job in run `25880469962`, conclusion `success` |
| Verify backend optional tests | `backend-tests` job in run `25880469962`, conclusion `success` |
| Verify staging deploy does not block main | `Deploy Staging` run `25880464879`, conclusion `success` |
| Preserve failure evidence for CI triage | Artifacts exist for `android-gate-reports`, `android-unit-test-reports`, `android-instrumented-test-reports`, and `android-release-smoke-reports` |
| Enforce beta-readiness performance/logcat smoke criteria | `qa/emulator/scripts/measure_android_readiness.sh` fails by default on unavailable/over-threshold cold start, unavailable/over-threshold PSS, or app fatal/ANR/OOM logcat patterns |
| Verify readiness smoke survives test APK cleanup | Run `25880469962` reinstalled `app-debug.apk` when `pm path com.becalm.android` was missing, then measured launch/memory/logcat |
| Prevent long-running CI jobs from hanging indefinitely | `.github/workflows/android-tests.yml` and `.github/workflows/android-gates.yml` define job-level `timeout-minutes`; mirrored in `.pipeline/adapters/android/test.yml` and `.pipeline/adapters/android/gates.yml` |
| Keep workflow templates aligned with executable workflows | `AndroidBuildWorkflowSpecTest` checks action versions, dependency-check fallback, timeouts, and artifact uploads for both `.github/workflows` and `.pipeline/adapters/android` |
| Keep release package identity aligned | `android/app/build.gradle.kts` uses `applicationId = "com.becalm.android"`; deploy config uses `packageName: com.becalm.android` |
| Reduce Play policy review risk | Battery optimization onboarding opens app settings and no longer declares or invokes `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| Avoid reverting or hiding analytics requirement | No Firebase Crashlytics or Amplitude SDK implementation is claimed in this audit; that work remains explicitly excluded |

## Latest Run Evidence

| Workflow | Run | Commit | Result |
|---|---:|---|---|
| Android Deterministic Gates | `25880469987` | `0cdd65c` | success |
| Android Tests | `25880469962` | `0cdd65c` | success |
| Deploy Staging | `25880464879` | `0cdd65c` | success |

Android Tests job results:

| Job | Result |
|---|---|
| read-config | success |
| unit-tests | success |
| backend-tests | success |
| instrumented-tests | success |
| release-smoke | success |

Readiness smoke artifact from `Android Tests` run `25880469962`:

| Metric | Result |
|---|---:|
| App install recovery | `package_installed=missing`, then `app-debug.apk` install `Success` |
| Cold start | `2108ms`, threshold `<=3000ms`, pass |
| Total PSS | `144665KB`, threshold `<=262144KB`, pass |
| Logcat fatal/ANR/OOM scan | pass |
| Readiness failure count | `0` |

Artifact evidence:

| Artifact | Size |
|---|---:|
| android-gate-reports | 87,919 bytes |
| android-unit-test-reports | 341,779 bytes |
| android-instrumented-test-reports | 732,045 bytes |
| android-release-smoke-reports | 62,600 bytes |

## Local Verification

| Command | Result |
|---|---|
| `./gradlew testDebugUnitTest --tests '*AndroidBuildWorkflowSpecTest' --tests '*ReadinessQaScriptSpecTest' --no-daemon --console=plain` | pass |
| `./gradlew testDebugUnitTest --tests '*AndroidPlayPolicySpecTest' --tests '*OnboardingUiTest' --no-daemon --console=plain` | pass |
| `./gradlew lintDebug --no-daemon --console=plain` | pass, `0 errors, 137 warnings` |
| `GITHUB_ACTIONS=true GITHUB_SHA=$(git rev-parse HEAD) python3 .pipeline/core/spec-coverage.py` | pass |
| `python3 .pipeline/core/assert-guard.py` | pass |
| `bash -n qa/emulator/scripts/measure_android_readiness.sh qa/emulator/scripts/verify_beta_readiness_qa.sh` | pass |
| Repo-wide legacy crash-vendor grep | no matches |
| `git diff --check` | pass |

## Remaining Explicitly Excluded Work

- Firebase Crashlytics SDK wiring.
- Amplitude SDK/client instrumentation.
- Real Firebase App Distribution upload with protected environment secrets.
- Production Play Console deployment with protected release secrets.

These exclusions match the user instruction that analytics/crash observability
SDK work is handled separately.
