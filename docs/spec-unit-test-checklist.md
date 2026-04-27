# Spec Test Coverage Status

Updated: 2026-04-27

Purpose:
- Replace the old worker-ownership checklist with a current traceability audit.
- Separate exact spec-id linkage from runtime execution status.
- Keep only verifiable facts from the present codebase.

Rules:
- `covered` means the exact spec id appears in current test source (`src/test` or `src/androidTest`).
- `missing exact-id` means grep-traceability is not locked to a current test name.
- `PASS` is used only for commands that this session actually executed.
- `PENDING` is used for commands not executed in this session.

## Execution Snapshot

Executed on clean branch `qa/verification-runtime-status`.

- `PASS` `cd android && ./gradlew :app:testDebugUnitTest`
- `PASS` `cd android && ./gradlew :app:compileDebugAndroidTestKotlin`
- `PENDING` `cd android && ./gradlew :app:connectedDebugAndroidTest`
  - Not executed in this session because the environment had no `adb`.

## Exact-ID Coverage Summary

Exact spec ids found in current test source:
- `103 / 137` covered
- `34 / 137` missing exact-id traceability

| module | covered | total | missing exact-id |
| --- | ---: | ---: | --- |
| `auth` | 9 | 10 | `AUTH-009` |
| `backend-sync` | 5 | 6 | `SYNC-002` |
| `cold-sync` | 8 | 8 | none |
| `commitment-edit` | 8 | 8 | none |
| `commitment-management` | 13 | 13 | none |
| `data-ingestion` | 1 | 15 | `ING-002..015` |
| `email-pipeline` | 5 | 7 | `EMAIL-004`, `EMAIL-007` |
| `error-states` | 0 | 9 | `ERR-001..009` |
| `manual-commitment` | 5 | 6 | `MAN-002` |
| `onboarding` | 10 | 10 | none |
| `person-enrichment` | 7 | 8 | `ENR-007` |
| `pipa-rights` | 5 | 7 | `PIPA-001`, `PIPA-007` |
| `source-management` | 5 | 5 | none |
| `source-viewer` | 8 | 8 | none |
| `today-timeline` | 10 | 10 | none |
| `voice-pipeline` | 4 | 7 | `VOI-001`, `VOI-005`, `VOI-007` |

## Missing Exact-ID Set

- `AUTH-009`
- `EMAIL-004`
- `EMAIL-007`
- `ENR-007`
- `ERR-001`
- `ERR-002`
- `ERR-003`
- `ERR-004`
- `ERR-005`
- `ERR-006`
- `ERR-007`
- `ERR-008`
- `ERR-009`
- `ING-002`
- `ING-003`
- `ING-004`
- `ING-005`
- `ING-006`
- `ING-007`
- `ING-008`
- `ING-009`
- `ING-010`
- `ING-011`
- `ING-012`
- `ING-013`
- `ING-014`
- `ING-015`
- `MAN-002`
- `PIPA-001`
- `PIPA-007`
- `SYNC-002`
- `VOI-001`
- `VOI-005`
- `VOI-007`

## Interpretation

- This file is a grep-traceability audit, not a product-open-items list.
- UI verification closure is tracked primarily in [docs/ui-verification-map.html](./ui-verification-map.html) and [docs/ui-test-checklist.md](./ui-test-checklist.md).
- For the current UI/QA pass, the remaining runtime blockers are:
  - `connectedDebugAndroidTest` full green on an emulator
  - manual emulator smoke after the black screen fix lands
  - `naver_imap` live smoke
  - `daum_imap` live smoke
