# UI / Commitment / action-state-alignment — SP-36 CommitmentState 와 스펙 action_state 의 dual state machine 정렬

**Branch**: `feat/ui/commitment`
**Status**: IMPLEMENTED — Wave 4 commit C2.
**E2E Stage**: 5 — CommitmentManagement 화면 렌더
**Severity**: **Critical** (UI 가 스펙 action_state 값을 표시·전이하지 않음. [리마인드]/[팔로업]/[취소] 버튼이 아예 없고 `DRAFT/CONFIRMED/SCHEDULED/DONE/DISMISSED` 라는 스펙 없는 label 이 사용자에게 노출됨)
**Type**: Drift (아키텍처 레벨 — dual state machine)

---

## 1. Finding

CommitmentEntity 는 **두 개의 상태 컬럼**을 갖고 있다:

1. `action_state` (`CommitmentEntity.kt:116-117`) — 값 `"pending"|"reminded"|"followed_up"|"completed"` — **스펙 CMT-005/006/007 align**
2. `commitment_state` (`CommitmentEntity.kt:128-129`) — 값 `DRAFT|CONFIRMED|SCHEDULED|DONE|DISMISSED` — SP-36 legacy state machine (pre-spec)

그러나 **UI 는 `commitment_state` 만 사용한다**:
- `CommitmentManagementViewModel.kt:340`: `derivedStatus = entity.commitmentState.name`
- `CommitmentManagementViewModel.kt:225-284`: 모든 액션이 `CommitmentEvent.Confirm/Schedule/MarkDone/Dismiss` → `commitmentState` 전이
- `CommitmentCard.kt:114-117, 226-231`: `DRAFT|CONFIRMED|SCHEDULED|DONE|DISMISSED` 문자열을 칩 label 로 렌더

결과: 사용자는 스펙에 없는 "CONFIRMED" / "SCHEDULED" / "DONE" 배지를 보게 되고, 스펙이 정의한 [리마인드]/[팔로업]/[완료]/[취소] 버튼 중 **아무것도 UI 에 존재하지 않는다** (`onMarkDone` 체크 아이콘 1 개만 있음 — spec CMT-007 과 유일하게 대응).

추가로 `action_state` enum 자체도 **`"overdue"` 와 `"cancelled"` 가 빠져 있어** CMT-011 (자동 overdue 전이), CMT-012 ([취소] 버튼) 를 구현할 수 없다.

---

## 2. Spec Contract

### 2.1 Action state enum (`.spec/contracts/data-model.yml:199-208`)
```
action_state ENUM = {pending, reminded, followed_up, completed, overdue, cancelled}
DEFAULT pending
```

### 2.2 State transitions (`.spec/commitment-management.spec.yml:49-133`)

| Event | Source state | Target state | Spec ID |
|-------|--------------|--------------|---------|
| [리마인드] | pending | reminded | CMT-005 |
| [팔로업] | pending / reminded | followed_up | CMT-006 |
| [완료] | pending / reminded / followed_up | completed | CMT-007 |
| [취소] | pending / reminded / followed_up / overdue | cancelled | CMT-012 |
| (system auto) | pending / reminded / followed_up @ due_at+24h | overdue | CMT-011 |
| [실행 취소] (Undo) | completed → 직전값 | cancelled → 직전값 | CMT-013 |

### 2.3 Invariants (`.spec/commitment-management.spec.yml:135-143`)
- `action_state` 변경 = Room 즉시 UPDATE + Railway PATCH /v1/commitments/{id} 비동기 호출
- PATCH 실패 시 낙관적 업데이트 유지 + `sync_status='pending'` 재큐
- overdue 는 **시스템 자동만** (수동 설정 불가)
- 불법 전이 (예: completed → reminded) → 클라이언트·서버 양쪽 **422** 거부

### 2.4 UI label contract (CMT-001, CMT-003)
- Card 배지: `direction` ("내가 한"/"상대가 한") + `action_state` 현재값 ("미확인"/"리마인드됨"/"팔로업됨"/"완료"/"놓침"/"취소됨")
- Detail sheet: `action_state` 현재값에 따라 **활성 버튼 분기**

---

## 3. Code Reality

### 3.1 Legacy SP-36 state machine
- `android/app/src/main/java/com/becalm/android/domain/commitment/CommitmentState.kt` — `DRAFT/CONFIRMED/SCHEDULED/DONE/DISMISSED` enum
- `CommitmentStateMachine.kt:41-75` — transition 함수 (legal edges 13-22 라인)
- `CommitmentEvent.kt` — `Confirm/Schedule/MarkDone/Dismiss` 이벤트
- `TransitionError.kt` — `IllegalTransition` / `MissingSchedule`

### 3.2 Entity column 는 둘 다 존재
- `CommitmentEntity.kt:116`: `actionState: String = "pending"` ✅
- `CommitmentEntity.kt:129`: `commitmentState: CommitmentState = CommitmentState.DRAFT` ← UI 가 읽는 건 이거

### 3.3 UI 는 legacy 만 사용
- VM: `CommitmentManagementViewModel.kt:259-284` — 4 개 액션 핸들러가 모두 `CommitmentEvent` 기반
- Repository: `CommitmentRepository.transitionState()` 는 `CommitmentEvent` 만 수용, `action_state` 직접 UPDATE 는 `updateActionState()` 로 별도 존재
- Card: `CommitmentCard.kt:114` — `derivedStatus.uppercase()` 가 `"DRAFT"|"CONFIRMED"|"SCHEDULED"|"DONE"` expect

### 3.4 스펙 버튼 미구현
- [리마인드] / [팔로업] / [취소] : 코드 0 matches
- 'followed_up' / 'overdue' / 'cancelled' label : 코드에 존재하지 않음

검증 grep:
```bash
grep -rn "CommitmentEvent\.\(Confirm\|Schedule\|MarkDone\|Dismiss\)" android/app/src/main/
grep -rn "\"reminded\"\|\"followed_up\"\|\"overdue\"\|\"cancelled\"" android/app/src/main/
```

---

## 4. Gap

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| primary state | `action_state` | `commitment_state` (SP-36) | UI 읽기 경로 변경 |
| state values | pending/reminded/followed_up/completed/overdue/cancelled | DRAFT/CONFIRMED/SCHEDULED/DONE/DISMISSED | 레이블·전이 재매핑 |
| enum 완전성 | 6 values | entity 컬럼에 4 values 만 예상 | `overdue`, `cancelled` 추가 |
| [리마인드] 버튼 | CommitmentDetailSheet 에 존재 | 없음 | UI 신규 추가 |
| [팔로업] 버튼 | CommitmentDetailSheet 에 존재 | 없음 | UI 신규 추가 |
| [취소] 버튼 | CommitmentDetailSheet 에 존재 | 없음 | UI 신규 추가 |
| overdue 자동 전이 | OverdueSweepWorker 6 h 주기 | 없음 | 별도 plan (`worker-commitment-overdue-sweep.md`) |
| Undo Snackbar | 5 s window | 없음 | 별도 plan (`ui-commitment-undo-snackbar.md`) |

---

## 5. Proposed Fix

### 5.1 Files to change
- `android/app/src/main/java/com/becalm/android/domain/commitment/CommitmentState.kt`
  - **대체**: `pending | reminded | followed_up | completed | overdue | cancelled` 6-value enum
  - 기존 `DRAFT/CONFIRMED/SCHEDULED/DONE/DISMISSED` 는 **삭제** (spec 에 근거 없음)
- `android/app/src/main/java/com/becalm/android/domain/commitment/CommitmentEvent.kt`
  - 대체: `Remind / FollowUp / Complete / Cancel` (+ `MarkOverdue` 는 system-only internal — sealed `out` 로 표시)
- `android/app/src/main/java/com/becalm/android/domain/commitment/CommitmentStateMachine.kt`
  - transition matrix 재작성 — spec table (위 2.2) 그대로 이식
- `android/app/src/main/java/com/becalm/android/data/local/db/entity/CommitmentEntity.kt`
  - `commitmentState` 필드 **제거**. `actionState` 를 `CommitmentState` enum 타입으로 바꾸고 @ColumnInfo 는 `action_state` 로 유지
  - `commitment_state` 컬럼은 마이그레이션으로 **DROP** — db/commitment 브랜치 (PR #20 후속) 에 `docs/plans/db-commitment-drop-commitment-state-column.md` 신규 plan doc 필요
- `android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepository.kt` + `Impl`
  - `transitionState(id, event)` 시그니처 유지하되 새 `CommitmentEvent` sealed 로 교체
  - `updateActionState()` 는 `transitionState` 내부 구현으로 통합 (또는 delete — UI 는 `transitionState` 만 사용)
- `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt`
  - 4 개 액션 핸들러 (`onConfirm/onSchedule/onMarkDone/onDismiss`) → 4 개 새 핸들러 (`onRemind/onFollowUp/onComplete/onCancel`) 로 교체
  - `CommitmentRow.derivedStatus` → `actionState` 값 기반 label (이 값은 localizable 한국어/영어 — 리소스 ID 로)
- `android/app/src/main/java/com/becalm/android/ui/components/CommitmentCard.kt`
  - `derivedStatus` 파라미터명은 유지하되 accepted values 를 spec enum 으로 변경
  - `stateColors` 매핑 재작성 (pending/reminded/overdue 색상 테마 이미 `BecalmStateColors` 에 존재 — 재사용)
- `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt`
  - Card `onMarkDone` 대신 **CommitmentDetailSheet 경로** — 별도 plan (`ui-commitment-detail-sheet.md`) 에서 처리

### 5.2 Files to add
- `android/app/src/main/res/values/strings.xml` (또는 ko/en 분리) — 새 label keys:
  - `commitment_state_pending` = "미확인"
  - `commitment_state_reminded` = "리마인드됨"
  - `commitment_state_followed_up` = "팔로업됨"
  - `commitment_state_completed` = "완료"
  - `commitment_state_overdue` = "놓침"
  - `commitment_state_cancelled` = "취소됨"

### 5.3 Files to delete
- 현재 없음 (deprecation은 별도 step — 먼저 alignment 후, 후속 cleanup PR 에서 `commitment_state` 컬럼 drop)

### 5.4 Non-code changes
- 테스트: `CommitmentStateMachineTest` 재작성 (legal edges 6 쌍 + illegal 거부 케이스)
- 테스트: `CommitmentManagementViewModelTest` — 4 개 신규 액션이 올바른 이벤트로 VM → Repository 호출하는지
- DB 마이그레이션: `commitment_state` 컬럼 DROP 은 **별도 plan** (`db-commitment-drop-commitment-state-column`) — 이 PR 범위 아님.
  - 이 PR 단계에선 `commitment_state` 컬럼을 유지한 채로 UI 만 `action_state` 로 switch. legacy 컬럼은 dead column 으로 남고 Room upsert 시 계속 기본값 (`"DRAFT"`) 이 들어감 → 무시 안전.

---

## 6. Acceptance Criteria

- [ ] `grep -rn "CommitmentState\.\(DRAFT\|CONFIRMED\|SCHEDULED\|DONE\|DISMISSED\)" android/app/src/` = 0
- [ ] `grep -rn "CommitmentEvent\.\(Confirm\|Schedule\|MarkDone\|Dismiss\)" android/app/src/` = 0
- [ ] `grep -rn "\"pending\"\|\"reminded\"\|\"followed_up\"\|\"completed\"\|\"overdue\"\|\"cancelled\"" android/app/src/main/java/com/becalm/android/data/local/db/entity/CommitmentEntity.kt` ≥ 6 (KDoc 또는 default)
- [ ] `CommitmentStateMachineTest` — spec 2.2 표의 6 개 legal edge + illegal 거부 3+ 케이스 전부 녹색
- [ ] `CommitmentManagementViewModelTest` — `onRemind/onFollowUp/onComplete/onCancel` 각 호출 시 Repository 에 올바른 event 전달
- [ ] **Compile gate**: `./gradlew :app:assembleDebug :app:testDebugUnitTest` 성공

---

## 7. Out of Scope

- **`commitment_state` 컬럼 DROP**: 별도 plan `db-commitment-drop-commitment-state-column.md` (PR #20 와 같은 `db/commitment` 브랜치에 commit 누적). 이 PR 은 컬럼을 남겨둔 채 UI 만 이전.
- **[리마인드] 버튼 + AlarmManager 연동** (CMT-005, CMT-008): 별도 plan `domain-commitment-alarm-manager-reminder.md` + `worker-commitment-alarm-receiver.md`. 본 plan 은 enum·전이·라벨 정렬만.
- **OverdueSweepWorker** (CMT-011): 별도 plan `worker-commitment-overdue-sweep.md`.
- **Undo Snackbar** (CMT-013): 별도 plan `ui-commitment-undo-snackbar.md`.
- **CommitmentDetailSheet** 컴포넌트 (CMT-003): 별도 plan `ui-commitment-detail-sheet.md`. 본 plan 은 `CommitmentCard` 의 label 만 변경.
- **Railway PATCH endpoint 계약 확인**: 서버는 이미 새 enum 수용한다고 가정. 불일치 시 `.spec/backend-sync.spec.yml` 갱신 후 Railway 측 별도 PR.

---

## 8. Dependencies

- **Blocked by**: 없음 (column 은 이미 존재, 값만 미사용). Implementation 세션 시작 시점에 PR #20 merge 여부 무관.
- **Blocks**: 
  - `ui-commitment-detail-sheet.md` (본 PR 의 새 event/state 계약을 소비)
  - `domain-commitment-alarm-manager-reminder.md` (Remind event 가 정의돼야 AlarmManager hook 가능)
  - `worker-commitment-overdue-sweep.md` (MarkOverdue 내부 이벤트 필요)
  - `ui-commitment-undo-snackbar.md` (이전 action_state 복원에 새 state machine 의 직전값 전이 필요)

병렬 가능:
- `ui-commitment-dn-badge-kst.md` (`CommitmentCard.kt` 겹치나 패치 영역 다름 — 순차 권장, 같은 branch 이므로 자동 linear)

---

## 9. Rollback plan

- 이 PR 은 enum·이벤트·라벨을 **대체**하므로 revert 하면 UI 는 다시 SP-36 label 표시. 구현 완료 시 반드시 다음 두 세션 단위 검증:
  1. `git revert <sha>` 테스트 — revert 후 compile 성공 + 기존 SP-36 테스트 통과
  2. Room 데이터 무결성 — 기존 Room 에 `commitment_state=DRAFT` 인 row 가 있어도 revert 후 정상 로드

---

## Appendix — Session handoff notes

- 이 plan 은 **가장 큰 UI-layer 드리프트** 를 다루지만 column 이 이미 존재하므로 DB 마이그레이션은 **필요 없다**. 구현 세션은 domain + UI 만 건드린다.
- 6 개 action_state label 의 한국어 문구는 product 팀 합의 필요 — 위 5.2 제안값은 draft. 최종은 PR 리뷰어가 확정.
- `CommitmentStateMachine` 의 legacy `DRAFT→CONFIRMED` 는 "LLM 추출 직후 사용자 확인 전" 개념이었으나, 스펙은 추출 즉시 `pending` 으로 노출 (`data-model.yml:199-208`, default='pending'). 즉 DRAFT 개념 자체가 spec 에 없음 — 완전 삭제가 맞다.
- 기존 `CommitmentEvent.Schedule(at: Instant)` 의 `at` 파라미터는 AlarmManager hook 시각이었음. 새 `Remind` 에서 비슷한 용도 필요 시 — 단 **alarm 시각은 due_at - 1h 로 계산 가능** → 별도 파라미터 불필요. 따라서 `Remind` 는 parameterless 이벤트.
- 전체 구현 규모 추정: ~10 파일, ~400 줄 변경. 1 세션 내 완주 가능. 테스트 포함하면 2 세션.
