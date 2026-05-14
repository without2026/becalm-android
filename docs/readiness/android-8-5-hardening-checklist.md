# Android 8.5 Readiness Hardening Checklist

This checklist maps the current beta-readiness target to concrete evidence. It is intentionally product-facing: a passing unit test is not enough unless it covers a user-visible requirement.

Last updated: 2026-05-15 KST.
Executable code verified at: `e33e544`.

Scope note: Firebase Crashlytics and Amplitude product analytics SDK wiring are tracked in a separate workstream. This checklist evaluates beta readiness excluding that SDK implementation.

## Target Scores

- Functional Requirements: 8.5
- Non-Functional Requirements: 8.5
- Code Quality: 8.5
- UX / Product Readiness: 8.5

## Functional Requirements

Required evidence:

- Auth and onboarding route tests pass: `AuthCheckpoint1E2eTest`, `OnboardingCheckpoint1E2eTest`, `SourceConnectionsCheckpoint2E2eTest`.
- Source sync and recovery states pass: `SourcesCheckpoint2E2eTest`, `SourceResilienceCheckpoint6E2eTest`.
- Person-centered pipeline passes: `PeoplePipelineCheckpoint4E2eTest`, `HappyPathFullJourneyE2eTest`.
- Evidence import and meeting speaker matching are verified on emulator:
  - `qa/emulator/scripts/verify_person_rendering_qa.sh`
  - `qa/emulator/scripts/verify_meeting_speaker_matching_qa.sh`
- Calendar source-of-truth schedule linking passes Android unit tests and backend resolver tests.

Pass condition for 8.5:

- `./gradlew testDebugUnitTest` passes.
- `./gradlew connectedDebugAndroidTest` passes on a cold-booted emulator or CI emulator.
- `qa/emulator/scripts/verify_beta_readiness_qa.sh` passes against a debug build with seeded QA data.

## Non-Functional Requirements

Required evidence:

- Cold start is measured through `qa/emulator/scripts/measure_android_readiness.sh`.
- Report includes:
  - `cold_start_total_ms`
  - `total_pss_kb`
  - `frame_rows`
- Readiness measurement fails by default if cold start or PSS is unavailable, exceeds the configured threshold, or logcat contains app fatal/ANR/OOM signals.
- Large person/detail datasets remain usable through seeded emulator QA, not just small previews.

Pass condition for 8.5:

- Cold start is under 3000ms on the target emulator after app install and QA seed.
- Total PSS is under 256MB during the People/Person-detail smoke.
- No ANR, OOM, or fatal exception appears in logcat during the beta-readiness smoke.

Current CI emulator evidence:

- `Android Tests` run `25879240108` reinstalled `app-debug.apk` when the target app package was missing after instrumentation.
- `cold_start_total_ms=1969`, threshold `<=3000ms`.
- `total_pss_kb=146244`, threshold `<=262144KB`.
- `logcat_threshold=PASS no fatal/ANR/OOM patterns`.
- `readiness_failure_count=0`.

## Code Quality

Required evidence:

- Debug tests pass: `./gradlew testDebugUnitTest`.
- Debug lint passes: `./gradlew lintDebug`.
- Release smoke passes: `./gradlew assembleRelease lintRelease`.
- CI runs release smoke through `.github/workflows/android-tests.yml`.
- Source recovery copy and CTA are projected from shared source status presentation, not per-screen string branching.

Pass condition for 8.5:

- Debug and release checks pass locally or in CI.
- No source status string exposes raw provider IDs, raw error bodies, tokens, or email addresses.
- New code follows existing ViewModel/Repository/Projector boundaries.

Current main evidence:

- `Android Deterministic Gates` run `25879240085` passes spec coverage, assert guard, secret detection, dependency-check task presence, Android lint, and APK size.
- `Android Tests` run `25879240108` passes unit tests, backend optional tests, API 33 emulator instrumentation, and release smoke.
- Local focused verification passed for `AndroidBuildWorkflowSpecTest` and `ReadinessQaScriptSpecTest`.
- `bash -n qa/emulator/scripts/measure_android_readiness.sh qa/emulator/scripts/verify_beta_readiness_qa.sh` passes.
- Repo-wide legacy crash-vendor grep returns no matches.

Known limits:

- `dependencyCheckAnalyze` task exists. CI skips the full NVD-backed dependency database run unless `NVD_API_KEY` is present, and separately gates that the task is registered.

## UX / Product Readiness

Required evidence:

- Source list and detail show:
  - current status,
  - user-readable recovery copy,
  - recommended action when recovery is possible.
- Processing status uses product language. It should say "내용 정리 중", not a vendor name like Gemini.
- Global evidence import still exposes meeting audio and message screenshot entry points.
- Person list stays contact-like; work context remains in person detail.

Pass condition for 8.5:

- A user can tell whether the app is connected, syncing, blocked, failed, or waiting for review without reading logcat.
- A recoverable source or upload failure has a visible next action.
- Korean copy is consistent across first-run, source status, processing, and recovery states.

Current main evidence:

- Korean UI copy invariant tests exist and are part of `testDebugUnitTest`.
- Emulator screenshot and QA artifacts exist under `docs/ui-smoke-screenshots/` and `qa/emulator/`.
- `measure_android_readiness.sh` records cold start, PSS, frames, logcat scan, and strict pass/fail counters; `ReadinessQaScriptSpecTest` prevents regressions to warn-only measurement.
- CI emulator run `25879240108` is the latest connected Android test evidence and passed.

## Release Engineering / CI

Required evidence:

- Android deterministic gates include lint, dependency check, secret scan, spec coverage, and size check.
- Android test workflow includes unit tests, release smoke, and CI emulator instrumentation.
- Android verification workflows have bounded runtime and upload unit, release, instrumented, and gate reports as artifacts for failure triage.
- Staging workflow is green on `main`.
- Staging distribution is wired to Firebase App Distribution and does not fail main pushes when distribution secrets are not installed yet.
- Production deploy builds a signed AAB and uploads it to Play Console with fail-closed runtime/signing checks.
- GitHub Actions workflows pass static validation and do not contain untrusted PR branch interpolation in shell scripts.
- GitHub first-party actions use Node 24-compatible major versions.

Current main evidence:

- Latest `Android Deterministic Gates` run succeeded on executable code commit `e33e544`: https://github.com/without2026/becalm-android/actions/runs/25879240085
- Latest `Android Tests` run succeeded on executable code commit `e33e544`: https://github.com/without2026/becalm-android/actions/runs/25879240108
- Latest `Deploy Staging` run succeeded on executable code commit `e33e544`: https://github.com/without2026/becalm-android/actions/runs/25879172966
- Latest CI artifacts were uploaded and are not expired:
  - `android-gate-reports`
  - `android-unit-test-reports`
  - `android-instrumented-test-reports`
  - `android-release-smoke-reports`
- `.github/workflows/deploy-staging.yml` now runs `android-staging-preflight` and skips Firebase App Distribution with a warning until required Firebase/runtime/signing secrets are present.
- `.github/workflows/deploy-production.yml` builds Android AAB through `adapter-build.yml` and uploads to Play Console using `r0adkll/upload-google-play@v1`.
- `adapter-build.yml` runs `verifyReleaseRuntimeConfigured verifyReleaseSigningConfigured` before protected Android release builds.
- `adapter-build.yml`, `adapter-gates.yml`, and `adapter-tests.yml` no longer reference missing electron/web reusable workflows in this Android repo.
- `ci-scenario-gen.yml` passes `github.head_ref` via `PR_HEAD_REF` env before shell use.
- First-party actions are upgraded to `checkout@v6`, `setup-java@v5`, `setup-python@v6`, `upload-artifact@v7`, `download-artifact@v8`, and `cache@v5`.
- Android Tests and Android Deterministic Gates have job-level timeouts and preserve Gradle lint/test/report artifacts with `if: always()`.

Known limits:

- Firebase App Distribution is not actually uploading until these repository/environment secrets are configured: `BECALM_API_BASE_URL`, `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `GOOGLE_WEB_CLIENT_ID`, `BECALM_RELEASE_*`, `FIREBASE_APP_ID`, `FIREBASE_SERVICE_ACCOUNT_JSON`, `FIREBASE_TESTER_GROUPS`.
- Production Play Console deploy path is wired but was not executed because protected production deploy requires release secrets and an explicit workflow dispatch.

## Commands

Run these before claiming this checklist is satisfied:

```bash
cd android
./gradlew testDebugUnitTest --no-daemon --console=plain
./gradlew lint --no-daemon --console=plain
./gradlew dependencyCheckAnalyze --no-daemon --console=plain
./gradlew assembleRelease lintRelease --no-daemon --console=plain
```

From the repo root:

```bash
actionlint .github/workflows/*.yml
python3 .pipeline/core/assert-guard.py
python3 .pipeline/core/spec-coverage.py
detect-secrets scan --baseline .secrets.baseline
```

With an emulator:

```bash
qa/emulator/scripts/verify_beta_readiness_qa.sh
qa/emulator/scripts/run_source_instrumentation_smoke.sh
```

`dependencyCheckAnalyze` requires the OWASP dependency-check Gradle plugin. CI should provide `NVD_API_KEY` when available; the task still exists without the secret, but the first database update can be slow or rate-limited.
