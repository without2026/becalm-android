# Spec Unit Test Checklist

Updated: 2026-04-22

Scope:
- This checklist tracks this round's unit-test ownership only.
- Source of truth is limited to `docs/becalm-mvp-boundary.md`, `.spec/**`, and current `*SpecTest.kt`.
- `covered` means an exact spec id appears in a unit `*SpecTest.kt` name.
- Behavior-adjacent tests without an exact spec id do not count as `covered`.
- `red` means uncovered and assigned in this round.
- `missing` means uncovered and not assigned in this round.
- `blocked(contract)` means spec/contracts conflict. None found in the current scope.

## Worker Ownership

- `W1 cold-sync`: `TDY-010`, `COLD-001..008`
- `W2 source-management-detail`: `SMG-002..005`, `ENR-008`
- `W3 source-viewer-list`: `SRC-001`, `SRC-003`, `SRC-005`, `SRC-006`, `SRC-007`, `ENR-006`
- `W4 source-viewer-detail-enrichment`: `SRC-002`, `SRC-004`, `SRC-008`, `ENR-003`, `ENR-004`, `ENR-005`
- `W5 commitments-management`: `CMT-002`, `CMT-003`, `CMT-008`, `CMT-009`, `CMT-011`
- `W6 commitments-edit-manual`: `MAN-001..004`, `MAN-006`, `EDIT-001`, `EDIT-002`, `EDIT-006`, `EDIT-007`, `EDIT-008`
- `W7 auth-onboarding`: `AUTH-004`, `AUTH-006`, `AUTH-007`, `ONB-001`, `ONB-002`, `ONB-003`, `ONB-005`, `ONB-006`, `ENR-001`, `ENR-002`
- `W8 settings-sync`: `PIPA-001..007`, `SYNC-001`, `SYNC-002`, `SYNC-004`, `SYNC-006`

## 1. Cold Sync / TDY-010

### today-timeline

| spec id | status | evidence / owner |
| --- | --- | --- |
| `TDY-001` | `covered` | `TodayViewModelSpecTest.kt` |
| `TDY-002` | `covered` | `TodayViewModelSpecTest.kt` |
| `TDY-003` | `missing` | unassigned |
| `TDY-004` | `missing` | unassigned |
| `TDY-005` | `missing` | unassigned |
| `TDY-006` | `covered` | `TodayViewModelSpecTest.kt` |
| `TDY-007` | `missing` | unassigned |
| `TDY-008` | `covered` | `TodayViewModelSpecTest.kt` |
| `TDY-009` | `covered` | `TodayViewModelSpecTest.kt` |
| `TDY-010` | `red` | `W1 cold-sync` |

### cold-sync

| spec id | status | evidence / owner |
| --- | --- | --- |
| `COLD-001` | `red` | `W1 cold-sync` |
| `COLD-002` | `red` | `W1 cold-sync` |
| `COLD-003` | `red` | `W1 cold-sync` |
| `COLD-004` | `red` | `W1 cold-sync` |
| `COLD-005` | `red` | `W1 cold-sync` |
| `COLD-006` | `red` | `W1 cold-sync` |
| `COLD-007` | `red` | `W1 cold-sync` |
| `COLD-008` | `red` | `W1 cold-sync` |

## 2. Source Management Detail

### source-management

| spec id | status | evidence / owner |
| --- | --- | --- |
| `SMG-001` | `covered` | `SourcesListViewModelSpecTest.kt` |
| `SMG-002` | `red` | `W2 source-management-detail` |
| `SMG-003` | `red` | `W2 source-management-detail` |
| `SMG-004` | `red` | `W2 source-management-detail` |
| `SMG-005` | `red` | `W2 source-management-detail` |

## 3. Source Viewer / Enrichment

### source-viewer

| spec id | status | evidence / owner |
| --- | --- | --- |
| `SRC-001` | `red` | `W3 source-viewer-list` |
| `SRC-002` | `red` | `W4 source-viewer-detail-enrichment` |
| `SRC-003` | `red` | `W3 source-viewer-list` |
| `SRC-004` | `red` | `W4 source-viewer-detail-enrichment` |
| `SRC-005` | `red` | `W3 source-viewer-list` |
| `SRC-006` | `red` | `W3 source-viewer-list` |
| `SRC-007` | `red` | `W3 source-viewer-list` |
| `SRC-008` | `red` | `W4 source-viewer-detail-enrichment` |

### person-enrichment

| spec id | status | evidence / owner |
| --- | --- | --- |
| `ENR-001` | `red` | `W7 auth-onboarding` |
| `ENR-002` | `red` | `W7 auth-onboarding` |
| `ENR-003` | `red` | `W4 source-viewer-detail-enrichment` |
| `ENR-004` | `red` | `W4 source-viewer-detail-enrichment` |
| `ENR-005` | `red` | `W4 source-viewer-detail-enrichment` |
| `ENR-006` | `red` | `W3 source-viewer-list` |
| `ENR-007` | `missing` | unassigned |
| `ENR-008` | `red` | `W2 source-management-detail` |

## 4. Commitments Residual

### commitment-management

| spec id | status | evidence / owner |
| --- | --- | --- |
| `CMT-001` | `covered` | `CommitmentManagementViewModelSpecTest.kt`, `CommitmentCardFormatterSpecTest.kt` |
| `CMT-002` | `red` | `W5 commitments-management` |
| `CMT-003` | `red` | `W5 commitments-management` |
| `CMT-004` | `covered` | `CommitmentCardFormatterSpecTest.kt` |
| `CMT-005` | `covered` | `CommitmentManagementViewModelSpecTest.kt` |
| `CMT-006` | `covered` | `CommitmentManagementViewModelSpecTest.kt` |
| `CMT-007` | `covered` | `CommitmentManagementViewModelSpecTest.kt` |
| `CMT-008` | `red` | `W5 commitments-management` |
| `CMT-009` | `red` | `W5 commitments-management` |
| `CMT-010` | `covered` | `CommitmentManagementViewModelSpecTest.kt` |
| `CMT-011` | `red` | `W5 commitments-management` |
| `CMT-012` | `covered` | `CommitmentManagementViewModelSpecTest.kt` |
| `CMT-013` | `covered` | `CommitmentManagementViewModelSpecTest.kt` |

### manual-commitment

| spec id | status | evidence / owner |
| --- | --- | --- |
| `MAN-001` | `red` | `W6 commitments-edit-manual` |
| `MAN-002` | `red` | `W6 commitments-edit-manual` |
| `MAN-003` | `red` | `W6 commitments-edit-manual` |
| `MAN-004` | `red` | `W6 commitments-edit-manual` |
| `MAN-005` | `covered` | `CommitmentManualValidatorSpecTest.kt` |
| `MAN-006` | `red` | `W6 commitments-edit-manual` |

### commitment-edit

| spec id | status | evidence / owner |
| --- | --- | --- |
| `EDIT-001` | `red` | `W6 commitments-edit-manual` |
| `EDIT-002` | `red` | `W6 commitments-edit-manual` |
| `EDIT-003` | `covered` | `CommitmentEditViewModelSpecTest.kt` |
| `EDIT-004` | `covered` | `CommitmentEditValidatorSpecTest.kt` |
| `EDIT-005` | `covered` | `CommitmentEditViewModelSpecTest.kt` |
| `EDIT-006` | `red` | `W6 commitments-edit-manual` |
| `EDIT-007` | `red` | `W6 commitments-edit-manual` |
| `EDIT-008` | `red` | `W6 commitments-edit-manual` |

## 5. Auth / Onboarding / Settings / Sync Residual

### auth

| spec id | status | evidence / owner |
| --- | --- | --- |
| `AUTH-001` | `covered` | `AuthViewModelSpecTest.kt` |
| `AUTH-002` | `covered` | `AuthViewModelSpecTest.kt` |
| `AUTH-003` | `covered` | `AuthViewModelSpecTest.kt` |
| `AUTH-004` | `red` | `W7 auth-onboarding` |
| `AUTH-005` | `covered` | `AuthViewModelSpecTest.kt` |
| `AUTH-006` | `red` | `W7 auth-onboarding` |
| `AUTH-007` | `red` | `W7 auth-onboarding` |
| `AUTH-008` | `covered` | `BeCalmDatabaseNamingSpecTest.kt` |

### onboarding

| spec id | status | evidence / owner |
| --- | --- | --- |
| `ONB-001` | `red` | `W7 auth-onboarding` |
| `ONB-PIPA` | `covered` | `OnboardingViewModelSpecTest.kt` |
| `ONB-002` | `red` | `W7 auth-onboarding` |
| `ONB-003` | `red` | `W7 auth-onboarding` |
| `ONB-CONTACTS` | `covered` | `OnboardingViewModelSpecTest.kt` |
| `ONB-004` | `covered` | `OnboardingViewModelSpecTest.kt` |
| `ONB-005` | `red` | `W7 auth-onboarding` |
| `ONB-006` | `red` | `W7 auth-onboarding` |
| `ONB-007` | `covered` | `OnboardingViewModelSpecTest.kt` |
| `ONB-008` | `covered` | `OnboardingViewModelSpecTest.kt` |

### pipa-rights

| spec id | status | evidence / owner |
| --- | --- | --- |
| `PIPA-001` | `red` | `W8 settings-sync` |
| `PIPA-002` | `red` | `W8 settings-sync` |
| `PIPA-003` | `red` | `W8 settings-sync` |
| `PIPA-004` | `red` | `W8 settings-sync` |
| `PIPA-005` | `red` | `W8 settings-sync` |
| `PIPA-006` | `red` | `W8 settings-sync` |
| `PIPA-007` | `red` | `W8 settings-sync` |

### backend-sync

| spec id | status | evidence / owner |
| --- | --- | --- |
| `SYNC-001` | `red` | `W8 settings-sync` |
| `SYNC-002` | `red` | `W8 settings-sync` |
| `SYNC-003` | `covered` | `UploadBackoffSpecTest.kt` |
| `SYNC-004` | `red` | `W8 settings-sync` |
| `SYNC-005` | `covered` | `UploadBackoffSpecTest.kt` |
| `SYNC-006` | `red` | `W8 settings-sync` |

## Adjacent But Not Counted Yet

- `AuthInterceptorSpecTest.kt` exercises refresh/retry paths adjacent to `AUTH-007`, but no exact spec id is locked in the test names.
- `SettingsViewModelSpecTest.kt` exercises PIPA toggle behavior adjacent to `PIPA-003`, `PIPA-006`, and voice-consent state transitions, but no exact `PIPA-*` id is locked.
- `SourceDetailViewModelSpecTest.kt` exercises source-detail behavior adjacent to `SMG-002..005`, but no exact `SMG-*` id is locked.
- `PersonsViewModelSpecTest.kt`, `PersonDetailViewModelSpecTest.kt`, and `RawEventDetailViewModelSpecTest.kt` exercise `SRC-*` / `ENR-*`-adjacent behavior, but no exact ids are locked.
- `CommitmentCreateViewModelSpecTest.kt` exercises manual/supersede-adjacent behavior, but no exact `MAN-*` or `EDIT-007` id is locked.
- `CommitmentEditViewModelSpecTest.kt` has unlabeled edit-load/delete paths adjacent to `EDIT-001`, `EDIT-006`, and `EDIT-008`; only exact-id tests count as `covered`.

## Contract Check

- No `blocked(contract)` items found in the current scope.
- `TDY-010` and `COLD-001..008` are consistent with `contracts/ui-map.yml` cold-sync routing/types.
- `MAN-*` and `EDIT-*` are consistent with `contracts/data-model.yml` and `contracts/api-contract.yml`; no schema conflict remains in current source-of-truth.
