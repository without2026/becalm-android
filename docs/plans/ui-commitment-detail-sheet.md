# UI / Commitment / Detail-Sheet — CMT-003 + EDIT-008 host composable

**Branch**: `feat/ui/commitment` (umbrella)
**Status**: PLAN ONLY — Wave 4 commit C4
**E2E Stage**: 5 — Commitment Management
**Severity**: High
**Type**: Gap

---

## 1. Finding

Spec CMT-003 requires tapping a commitment card to open `CommitmentDetailSheet` showing full quote + source context + action buttons. Spec EDIT-008 requires the sheet to show `마지막 수정` timestamp, disputed badge, supersede backlink. The component **does not exist** — `CommitmentCard.onClick` is currently an empty lambda (`CommitmentManagementScreen.kt:117`) and the codebase has no `ModalBottomSheet` usage. This is the **host composable** for every action in CMT-005/006/007/012 + EDIT-001/005/006/007 + MAN-004 (source line).

---

## 2. Spec Contract

- **`.spec/commitment-management.spec.yml:27-35`** — CMT-003:
  > "카드 탭 → `CommitmentDetailSheet` (bottom sheet) 열림. 표시: `quote` 전문 (원문 보존, 스크롤 가능), `source_event_title`, `source_event_occurred_at` (KST), `person_ref` display name, 액션 버튼 `[리마인드] [팔로업] [완료] [취소] [편집]` (현 `action_state` 기반 enable/disable)."

- **`.spec/commitment-edit.spec.yml:86-94`** — EDIT-008:
  > "`CommitmentDetailSheet` 하단에 `마지막 수정: {M/d HH:mm} (본인)` (if `last_edited_at != null`). 이의 제기된 경우 `⚠ 원문 이의 제기됨 ({M/d HH:mm})` 배지. `supersedes_commitment_id != null` → `이전 약속 보기` backlink (MVP 기간 비활성 — 눈에 보이나 클릭 안됨)."

- **`.spec/manual-commitment.spec.yml:46-54`** — MAN-004:
  > "수동 commitment 의 source section: `사용자 직접 추가 {created_at KST}` 렌더 + `📝 수동 추가` 배지."

- **`.spec/contracts/data-model.yml:119-123`** — quote invariant:
  > "quote MUST-NOT be modified after insertion (legally evidentiary)."

---

## 3. Code Reality

- **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt:117`** — `onClick = {}` (empty lambda).
- **`android/app/src/main/java/com/becalm/android/ui/navigation/Routes.kt:144`** — only `data object Commitments : BecalmRoute("commitments")`, no parameterized detail route.
- **`android/app/src/main/java/com/becalm/android/ui/navigation/BecalmNavHost.kt:180-182`** — `composable(Commitments.path) { CommitmentManagementScreen() }` with no navController param.
- **`android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepository.kt`** — no `observeById(id: String): Flow<CommitmentEntity?>` — need to add.
- **`android/app/src/main/java/com/becalm/android/data/local/db/dao/CommitmentDao.kt:155`** — has `findById(id)` suspend but no Flow variant.

```bash
grep -rn "ModalBottomSheet\|CommitmentDetailSheet" android/app/src/main/java/
# current: 0
```

---

## 4. Gap

| 측면 | Spec 요구 | Code 현실 | 차이 |
|---|---|---|---|
| Detail sheet composable | ModalBottomSheet | 없음 | 신규 생성 |
| 진입 경로 | 카드 탭 → 시트 | onClick 빈 람다 | wiring |
| Nav route | `/commitments/{id}` | 없음 | Routes.kt 추가 |
| DB observe by id | Flow 기반 reactive | suspend 단발 | DAO + Repo Flow 추가 |
| 액션 버튼 | 5개 (리마인드/팔로업/완료/취소/편집) | 없음 | 시트 내부 |
| `마지막 수정` 라벨 | EDIT-008 | N/A | 시트 footer |
| disputed 배지 | EDIT-008 | N/A | 시트 quote 섹션 |
| supersede backlink | EDIT-008 disabled | N/A | 시트 footer (disabled link) |
| manual source line | MAN-004 | N/A | 시트 source 섹션 |

---

## 5. Proposed Fix

### 5.1 Files to change

- `android/app/src/main/java/com/becalm/android/data/local/db/dao/CommitmentDao.kt`
  — add `@Query("SELECT * FROM commitments WHERE id = :id AND deleted_at IS NULL") fun observeById(id: String): Flow<CommitmentEntity?>`.
- `android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepository.kt` + `CommitmentRepositoryImpl.kt`
  — add `fun observeById(id: String): Flow<CommitmentEntity?>`.
- `android/app/src/main/java/com/becalm/android/ui/navigation/Routes.kt`
  — add `data class CommitmentDetail(val id: String)` with path `commitments/{id}` and arg builder.
- `android/app/src/main/java/com/becalm/android/ui/navigation/BecalmNavHost.kt`
  — register `composable(CommitmentDetail.PATH, listOf(navArgument("id"){type=NavType.StringType}))`.
- `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt`
  — `onClick = { onOpenDetail(row.id) }` (new screen-level lambda forwarded to nav).

### 5.2 Files to add

- `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentDetailSheet.kt`
  — Material3 `ModalBottomSheet`-backed composable. Renders quote (scrollable card, disputed badge overlay), source section (title/occurred_at KST or manual line), person_ref display (looked up via PersonEnrichmentRepository), direction chip, action button strip (5 buttons; enabled per current `action_state` per spec transition matrix), `마지막 수정` footer, supersede backlink (disabled). Accepts `onRemind/onFollowUp/onComplete/onCancel/onEdit` callbacks.
- `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentDetailViewModel.kt`
  — Hilt VM. `init { savedState.getStateFlow("id").flatMapLatest{ repo.observeById(it) }.stateIn }`. State: `DetailUiState(entity, personDisplayName, loading, error)`. Actions proxy to existing VM (shared with management) or duplicate shallow call-sites.
- `android/app/src/test/java/com/becalm/android/ui/commitments/CommitmentDetailViewModelTest.kt`
  — covers flow emission, null-entity error path.

### 5.3 Files to delete
- none.

### 5.4 Non-code changes
- `strings.xml` additions: `commitment_detail_title`, `commitment_detail_last_edited_fmt`, `commitment_detail_disputed_badge`, `commitment_detail_superseded_link`, `commitment_detail_manual_source_fmt`, button labels `commitment_action_remind/followup/complete/cancel/edit`.

---

## 6. Acceptance Criteria

- [ ] **Grep**: `grep -rn "CommitmentDetailSheet" android/app/src/main/java/` ≥ 2.
- [ ] **Unit test**: `CommitmentDetailViewModelTest` — flow emission + null case pass.
- [ ] **Compile**: `:app:assembleDebug` green.
- [ ] **Nav route grep**: `grep -n "commitments/{id}" android/app/src/main/java/com/becalm/android/ui/navigation/` ≥ 1.
- [ ] **Quote read-only**: 시트에 `TextField` 로 quote 를 감싸는 코드가 없음 (`grep -n "quote.*TextField\|TextField.*quote" android/app/src/main/java/com/becalm/android/ui/commitments/` == 0).
- [ ] **Manual**: 카드 탭 → 시트 열림 → 5 버튼 중 현재 `action_state` 기반 enable 상태 확인.

---

## 7. Out of Scope

- Edit sheet 실제 구현 (별도 `ui-commitment-edit-sheet`).
- Manual add sheet (별도 `ui-commitment-manual-sheet`).
- Reminder 알람 시간 계산 (별도 `ui-commitment-reminder-due-gate`).
- Cancel 실제 repository wiring (별도 `ui-commitment-cancel-action`).
- Completed section / undo snackbar (별도 `ui-commitment-completed-section-undo`).
- Quote dispute dialog (별도 edit-sheet).
- 실제 state transition 코드 — 이 plan 은 **host composable + 진입 경로**만 wire-up. 버튼 클릭 시 VM 핸들러는 일단 pass-through / TODO 주석.

---

## 8. Dependencies

- **Blocked by**: `ui-commitment-action-state-alignment` (commit C2) — 시트 버튼이 새 6-value enum 기반이어야 함.
- **Blocks**: C5 reminder-due-gate, C6 completed-section-undo, C7 cancel-action, C8 edit-sheet, C9 manual-sheet (모두 시트에서 진입).

Umbrella linear: C4 는 C2 뒤. 같은 브랜치 내 순차 commit.

---

## 9. Rollback plan

단일 revert — 시트 composable + VM + nav route + DAO Flow 한 방에 원복.

---

## Appendix — Session handoff notes

- Material3 `ModalBottomSheet` stable 이후 API 로 진행. `rememberModalBottomSheetState(skipPartiallyExpanded = false)`.
- Detail sheet 의 VM 을 Management VM 과 공유하지 **않는다** — DI separation 이 깔끔. Actions 는 Repo 에 직접 delegate 해도 되고, 또는 후속 커밋에서 공용 UseCase 로 묶어도 됨 (refactor phase 후보).
- `person_ref` 표시 이름 lookup: `PersonEnrichmentRepository.observeByPersonRef` 를 쓰거나, entity 의 `counterparty_raw` 를 fallback.
- 알람 deep link (`becalm://commitments/{id}`) 는 C5 에서 manifest intent filter 로 연결. 이 plan 은 nav route 만 만들고 scheme 처리는 C5 소관.
