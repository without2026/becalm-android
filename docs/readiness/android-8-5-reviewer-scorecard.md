# Android 8.5 Reviewer Scorecard

Date: 2026-05-15 KST
Evidence document status: reviewer re-check after analytics/Crashlytics changes.
Executable code verified at: current `becalm-android` working tree on top of
`074ec85`; `becalm-backend` at `22e9752`.
Reviewer update: previous CI evidence is stale for the current working tree.

This scorecard is for reviewer scoring only. Do not fill the score column from
implementation intent or self-assessment. Assign each score from the linked
evidence and the agreed rubric.

## Review Scope

Included:

- Functional requirements.
- Non-functional requirements.
- Architecture and maintainability signals from Android and backend code.
- Android/backend test evidence.
- Code quality.
- UX / product readiness.
- Security/privacy, release engineering, CI, and observability support that
  directly affects a controlled 10-user / 5-day beta.
- Backend `/v1/*` API, Supabase persistence contract, migrations, and local
  backend test suite.

Previously excluded, but now present in current Android code:

- Firebase Crashlytics SDK wiring.
- Amplitude SDK/client instrumentation.

## Scorecard

| Rubric Area | Evidence To Inspect | Reviewer Score | Pass At 8.5? |
|---|---|---:|---|
| Functional Requirements | Previous CI flow evidence exists, but current `./gradlew testDebugUnitTest` fails 4 foreground catch-up/source sync tests. Backend `/v1` contract passes locally with `130 passed, 8 skipped`. | 7.8 | No |
| Non-Functional Requirements | Previous CI readiness smoke measured cold start `1994ms`, total PSS `144766KB`, fatal/ANR/OOM pass, failure count `0`; however current telemetry/Crashlytics code has not been re-measured on emulator. | 7.4 | No |
| Architecture Criteria | Android now has separate product analytics and observability abstractions, Composite clients, backend mirror, Crashlytics port, and PII validation. Some telemetry calls sit in Activity/AuthRepository/ViewModel/Receiver boundaries, not deep domain logic. | 8.3 | No |
| Design Patterns | MVVM/repository/state-holder/reactive patterns remain. Analytics uses facade + composite + bounded channel; backend analytics validation is split into `analytics_contract.py`. | 8.3 | No |
| Testing Criteria | Backend tests pass. Android focused workflow/readiness tests pass, but full `testDebugUnitTest` fails: `ForegroundCatchUpSchedulerLocalIntegrationTest` has 4 failures. No dedicated analytics/Crashlytics unit tests were found. | 6.2 | No |
| Code Quality | `lintDebug` passes. Current code has stale readiness claims, target SDK mismatch (`compileSdk = 35`, `targetSdk = 34` while docs claim target 35), and failing unit tests. | 6.8 | No |
| Security / Privacy | Keystore/HTTPS/PIPA/RLS controls remain. Analytics adds property PII filtering, Crashlytics sanitization, telemetry opt-out, and backend property size/depth checks. Needs explicit test coverage and policy verification. | 8.2 | No |
| Release Engineering | Firebase/Amplitude dependencies are wired and local debug builds can compile with missing Amplitude key. Protected release still depends on secrets, current full tests fail, and targetSdk documentation is inconsistent with build config. | 6.5 | No |
| Observability | Crashlytics and Amplitude are now implemented via abstractions, with backend `product_events` mirror and PMF tables. No local dashboard proof, no analytics-specific unit tests, and current docs still describe SDK work as excluded. | 7.4 | No |
| UX / Product Readiness | UX copy/readiness evidence largely remains, but source catch-up test failures affect the freshness guarantee behind source-driven user journeys. | 8.0 | No |

Weighted readiness score for a controlled 10-user / 5-day beta: **7.3 / 10**.

## Reviewer Decision

Decision: **no-go** for a controlled 10-user / 5-day beta until the current
working tree is made internally consistent and green.

- Functional Requirements score: 7.8
- Non-Functional Requirements score: 7.4
- Architecture Criteria score: 8.3
- Design Patterns score: 8.3
- Testing Criteria score: 6.2
- Code Quality score: 6.8
- Security / Privacy score: 8.2
- Release Engineering score: 6.5
- Observability score: 7.4
- UX / Product Readiness score: 8.0
- Controlled 10-user / 5-day beta accepted: no
- Public/open beta accepted: no

Blockers before inviting testers:

- Fix `ForegroundCatchUpSchedulerLocalIntegrationTest` failures and restore a
  passing full `./gradlew testDebugUnitTest`.
- Reconcile target SDK evidence: either set `targetSdk = 35` or update docs and
  reviewer claims to match `targetSdk = 34`.
- Add focused tests for product analytics validation, bounded queue/drop behavior,
  backend mirror failure isolation, Crashlytics PII sanitization, and user opt-out.
- Re-run emulator readiness smoke after Firebase/Amplitude wiring and record new
  cold start, PSS, skipped-frame, and fatal/ANR/OOM evidence.
- Update readiness docs so they no longer claim Firebase/Amplitude SDK work is
  excluded when current code ships those SDKs.

## Evidence Links

- Completion audit: `docs/readiness/android-8-5-completion-audit.md`
- Hardening checklist: `docs/readiness/android-8-5-hardening-checklist.md`
- Review request: `docs/readiness/android-8-5-review-request.md`
- Android Deterministic Gates: https://github.com/without2026/becalm-android/actions/runs/25905324259
- Android Tests: https://github.com/without2026/becalm-android/actions/runs/25905324256
- Executable-code Deploy Staging: https://github.com/without2026/becalm-android/actions/runs/25905319248
