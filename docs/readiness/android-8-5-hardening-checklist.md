# Android 8.5 Readiness Hardening Checklist

This checklist maps the current beta-readiness target to concrete evidence. It is intentionally product-facing: a passing unit test is not enough unless it covers a user-visible requirement.

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
- Large person/detail datasets remain usable through seeded emulator QA, not just small previews.

Pass condition for 8.5:

- Cold start is under 3000ms on the target emulator after app install and QA seed.
- Total PSS is under 256MB during the People/Person-detail smoke.
- No ANR, OOM, or fatal exception appears in logcat during the beta-readiness smoke.

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

## Commands

Run these before claiming this checklist is satisfied:

```bash
cd android
./gradlew testDebugUnitTest --no-daemon --console=plain
./gradlew lintDebug --no-daemon --console=plain
./gradlew assembleRelease lintRelease --no-daemon --console=plain
```

With an emulator:

```bash
qa/emulator/scripts/verify_beta_readiness_qa.sh
```
