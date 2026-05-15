# Android 8.5 Reviewer Scorecard

Date: 2026-05-15 KST
Evidence document status: docs-only; does not change executable Android code.
Executable code verified at: `e6fb1cb`
Reviewer update: includes backend scope and latest docs-only Deploy Staging run
`25904877962`, conclusion `success`.

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

Excluded by user direction:

- Firebase Crashlytics SDK wiring.
- Amplitude SDK/client instrumentation.

## Scorecard

| Rubric Area | Evidence To Inspect | Reviewer Score | Pass At 8.5? |
|---|---|---:|---|
| Functional Requirements | `Android Tests` run `25884778885`; unit, backend, API 33 instrumented, and release-smoke jobs succeeded. Core flow tests named in `android-8-5-review-request.md`. Backend `/v1` contract and `python3 -m pytest -q` pass: `128 passed, 8 skipped`. |  |  |
| Non-Functional Requirements | `readiness-20260514T210124Z.txt` from run `25884778885`: cold start `1997ms`, total PSS `143379KB`, fatal/ANR/OOM pass, failure count `0`. Firebase Crashlytics SDK wiring is a separate workstream and is not claimed here. |  |  |
| Architecture Criteria | Android has UI/data/domain/core/worker separation, Hilt modules, repositories, Room, StateFlow/SharedFlow, and secure local stores. Backend has FastAPI `/v1`, service modules, DB migrations, and explicit contract docs. |  |  |
| Design Patterns | MVVM, repository, state-holder, observer/reactive, WorkManager, DTO/entity/domain separation, and backend service-contract patterns are consistently present. |  |  |
| Testing Criteria | Android CI passed unit, release smoke, and API 33 instrumentation. Backend local tests passed. External live provider tests are present but skipped unless env is configured. |  |  |
| Code Quality | `Android Deterministic Gates` run `25884778863`: spec coverage, assert guard, secret detection, dependency-check task presence, Android lint, and APK size passed. Local lint/release evidence in `android-8-5-completion-audit.md`; release lint still has warnings. |  |  |
| Security / Privacy | Android Keystore/EncryptedSharedPreferences, HTTPS default, backup disabled, PIPA copy, no full email original persistence, backend RLS migrations, token-auth middleware. |  |  |
| Release Engineering | Release smoke and staging deploy succeeded. Firebase App Distribution and Play upload paths are wired, but real Firebase distribution and production Play deploy depend on protected secrets and were not proven as completed uploads. |  |  |
| Observability | Backend `product_events` and PMF tables exist; Android has vendor-neutral `ObservabilityClient` logger binding with PII redaction. Firebase Crashlytics and Amplitude SDKs are explicitly planned in a separate workstream, not shipped or scored here. |  |  |
| UX / Product Readiness | PIPA/CLOVA copy alignment, Korean processing copy, source recovery UX, user-facing vendor status removal, settings/privacy surfaces, and emulator screenshot evidence. |  |  |

Weighted readiness score for a controlled 10-user / 5-day beta: reviewer to fill.

## Reviewer Decision

Decision: reviewer to fill.

- Functional Requirements score:
- Non-Functional Requirements score:
- Architecture Criteria score:
- Design Patterns score:
- Testing Criteria score:
- Code Quality score:
- Security / Privacy score:
- Release Engineering score:
- Observability score:
- UX / Product Readiness score:
- Controlled 10-user / 5-day beta accepted:
- Public/open beta accepted:

Conditions before inviting testers:

- Configure the protected staging/runtime/signing/Firebase secrets or use Play
  Console Internal Testing with the same signed release artifact.
- Keep a daily beta ops loop: crash/user report intake, Supabase/backend error
  review, source-status review, and issue triage.
- Treat Firebase Crashlytics and Amplitude as the separate observability
  workstream before any beta beyond this controlled 10-user / 5-day cohort.
- Run at least one real-provider smoke for the tester source mix: Google/Outlook
  mail/calendar and one audio path if those are in the beta script.

## Evidence Links

- Completion audit: `docs/readiness/android-8-5-completion-audit.md`
- Hardening checklist: `docs/readiness/android-8-5-hardening-checklist.md`
- Review request: `docs/readiness/android-8-5-review-request.md`
- Android Deterministic Gates: https://github.com/without2026/becalm-android/actions/runs/25884778863
- Android Tests: https://github.com/without2026/becalm-android/actions/runs/25884778885
- Executable-code Deploy Staging: https://github.com/without2026/becalm-android/actions/runs/25884772086
- Latest docs-only Deploy Staging: https://github.com/without2026/becalm-android/actions/runs/25904877962
