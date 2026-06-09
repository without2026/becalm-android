# Refactor Notes

## Current Architecture Summary

- Android app implementation lives under `android/app/src/main`.
- The selected slice is `domain/email`, which is pure Kotlin domain logic used by ingestion and person matching projections.
- `EmailSnippetBuilder` creates deterministic email snippets from plain body, HTML body, or subject fallback.
- `OutgoingEmailSalutationExtractor` derives conservative review-only recipient-name signals from SENT-folder email salutations.
- Callers outside this slice own IO, Room/DataStore writes, worker scheduling, UI state, and metrics side effects.

## Main Code Smells

- `EmailSnippetBuilder.buildSnippet` repeats `SnippetResult` construction and interleaves fallback selection with result assembly.
- `OutgoingEmailSalutationExtractor.extractNames` performs folder gating, opening-line extraction, salutation segmentation, regex matching, normalization, filtering, de-duplication, and truncation in one pipeline.
- Small behavior-critical parsing rules are embedded in chained expressions, making future edits risky.

## High-Risk Files

- `android/app/src/main/java/com/becalm/android/domain/email/EmailSnippetBuilder.kt`: high contract sensitivity because `sourceKind` and `parseFailed` drive ingestion metrics and persistence.
- `android/app/src/main/java/com/becalm/android/domain/email/OutgoingEmailSalutationExtractor.kt`: high false-positive risk because extracted names affect person review signals.
- Current dirty worktree contains many unrelated modified files; this slice must not touch them.

## Refactor Goals

- Preserve public functions, data classes, enum names, package names, and call sites.
- Extract small private helpers around fallback/result construction and opening-line/name parsing.
- Make each branch name the domain decision it represents.
- Keep regexes, constants, fallback order, normalization order, distinct ordering, and max counts unchanged.

## Behavior-Preservation Strategy

- No public API shape changes.
- No UI, navigation, DB, API DTO, analytics, auth, worker scheduling, coroutine, retry, or persistence changes.
- Preserve null, blank, parse-failure, ordering, de-duplication, and truncation behavior.
- Keep HTML parse failure as graceful subject fallback with `parseFailed = true`.
- Use existing unit specs as regression gates for the selected pure-domain behavior.

## Intended Changed Files

- `REFACTOR_NOTES.md`
- `android/app/src/main/java/com/becalm/android/domain/email/EmailSnippetBuilder.kt`
- `android/app/src/main/java/com/becalm/android/domain/email/OutgoingEmailSalutationExtractor.kt`

## Test/Build Commands To Run

- `./gradlew testDebugUnitTest --tests com.becalm.android.unit.domain.email.EmailSnippetBuilderSpecTest --tests com.becalm.android.unit.domain.email.OutgoingEmailSalutationExtractorSpecTest`
- `./gradlew testDebugUnitTest`
- `./gradlew lintDebug`
- `./gradlew assembleDebug`

## Rollback Strategy

- Revert only the three files listed above.
- Do not reset the repo or touch unrelated dirty files.
- If validation fails from this slice, restore the touched Kotlin files to their pre-refactor shape and keep notes on the failure.

## Explicit Non-Changes

- No product behavior changes.
- No API request/response changes.
- No database schema, migration, or Room entity semantics changes.
- No UI copy, navigation, layout, accessibility, or interaction changes.
- No analytics event or metric key changes.
- No auth/session behavior changes.
- No worker timing, retry, idempotency, or scheduling changes.
- No external dependency changes.
- No cleanup of unrelated dirty worktree files.

## Behavior Issues Discovered But Not Changed

- `./gradlew testDebugUnitTest` currently fails outside this refactor slice:
  - `com.becalm.android.unit.ui.UiWordingQualityTest`: existing Korean string resources contain blocked developer-facing wording (`타임라인`, `팔로업`).
  - `com.becalm.android.integration.local.ui.persons.PersonsUiTest`: two existing persons UI expectations fail because expected components are not displayed.
- These failures are in unrelated dirty UI/resource/test files and were not changed in this behavior-preserving email-domain refactor.

## Implementation Summary

- `EmailSnippetBuilder.buildSnippet` now delegates repeated result creation and fallback branches to private helpers.
- `OutgoingEmailSalutationExtractor.extractNames` now delegates folder gating, opening-line extraction, salutation segmentation, name extraction, and normalization to private helpers.
- Public APIs, constants, regex semantics, fallback ordering, parse-failure behavior, result ordering, and truncation limits are unchanged.

## Validation Results

- Passed before refactor: `./gradlew testDebugUnitTest --tests com.becalm.android.unit.domain.email.EmailSnippetBuilderSpecTest --tests com.becalm.android.unit.domain.email.OutgoingEmailSalutationExtractorSpecTest`
- Passed after refactor: `./gradlew testDebugUnitTest --tests com.becalm.android.unit.domain.email.EmailSnippetBuilderSpecTest --tests com.becalm.android.unit.domain.email.OutgoingEmailSalutationExtractorSpecTest`
- Failed outside slice: `./gradlew testDebugUnitTest`
- Passed: `./gradlew lintDebug`
- Passed: `./gradlew assembleDebug`
