# UI / Commitment / detail-sheet — CommitmentCard 탭 시 상세 시트가 존재하지 않음

**Branch**: `feat/ui/commitment`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 plan doc 이외의 코드 커밋 금지.
**E2E Stage**: 5 — CommitmentManagement 화면 / 상세 시트
**Severity**: High (CMT-003 은 action_state 전이 [리마인드]/[팔로업]/[완료] 진입점이자 EDIT-001 의 편집 버튼 호스트. 상세 시트가 없으면 CMT-005/6/7/12/13 및 EDIT-* 전체가 도달 불가.)
**Type**: Gap (스펙에서 요구하는 `CommitmentDetailSheet` 화면과 `CommitmentCard` 탭 핸들러가 둘 다 부재)

---

## 1. Finding

`CommitmentManagementScreen.kt:114` 에서 `CommitmentCard(onClick = {})` 로 no-op 람다가 전달되어 카드 탭이 무시된다. 또한 `android/app/src/main/java/com/becalm/android/ui/commitments/` 하위에 `CommitmentDetailSheet.kt` 파일 자체가 존재하지 않는다.

`.spec/commitment-management.spec.yml:27-35` CMT-003 은 "CommitmentCard 탭 시 약속 상세 시트가 열린다 — quote 전문 + 출처 정보(source_event_title, source_event_occurred_at) + person_ref 표시명 + action_state 변경 버튼" 을 요구. 상세 시트는 [리마인드](CMT-005) / [팔로업](CMT-006) / [완료](CMT-007) / [취소](CMT-012) / [편집](EDIT-001) 모든 단일 약속 행동의 호스트이므로, 이 진입점 없이는 Stage 5 UX 전체가 닫혀 있다.

---

## 2. Spec Contract (무엇이어야 하는가)

- **`.spec/commitment-management.spec.yml:27-35` CMT-003**:
  > "CommitmentCard 탭 시 약속 상세 시트가 열린다 — quote 전문 + 출처 정보(source_event_title, source_event_occurred_at) + person_ref 표시명 + action_state 변경 버튼"
  > precondition: "CommitmentManagementScreen에 Commitment 1건 표시됨(source_event_title='2024-03-15 팀 미팅', quote='다음 주까지 보고서 제출')"
  > expected: "바텀 시트 열림. quote='다음 주까지 보고서 제출' 전문 표시됨. '출처: 2024-03-15 팀 미팅' 표시됨. source_event_occurred_at 표시됨. action_state 변경 버튼([리마인드]/[팔로업]/[완료]) 표시됨"

- **`.spec/commitment-management.spec.yml:50` CMT-005 screen**:
  > "screen: CommitmentDetailSheet"
  → [리마인드] 버튼 호스트.

- **`.spec/commitment-management.spec.yml:60` CMT-006 screen** / **`:71` CMT-007 screen** / **`:118` CMT-012 screen**: 모두 `CommitmentDetailSheet` 를 전제.

- **`.spec/commitment-edit.spec.yml:19-22` EDIT-001**:
  > "CommitmentDetailSheet 하단에 [편집] 버튼이 표시되며 탭 시 CommitmentEditSheet가 열린다."
  > "action_state='cancelled' 또는 deleted_at != null인 commitment는 [편집] 버튼 비활성(읽기 전용)"

- **`.spec/commitment-edit.spec.yml:88-92` EDIT-008**:
  > "CommitmentDetailSheet에 last_edited_at != null인 경우 '마지막 수정: {M/d HH:mm} (본인)' 보조 라벨이 표시된다."

- **`.spec/commitment-management.spec.yml:136` invariant**:
  > "CommitmentManagementScreen은 Room(로컬) 데이터를 primary source로 사용한다"
  → 상세 시트 역시 Room 의 단일 commitment id 를 반응형으로 관찰해야 함.

---

## 3. Code Reality (지금 무엇인가)

- **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt:103-115`**:
  ```kotlin
  items(items = state.items, key = { it.id }) { row ->
      CommitmentCard(
          title = row.title,
          direction = row.direction,
          derivedStatus = row.derivedStatus,
          dueDate = row.dueDate,
          counterpartyDisplayName = row.counterpartyDisplayName,
          modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 4.dp),
          onMarkDone = { viewModel.onMarkDone(row.id) },
          onClick = {},
      )
  }
  ```
  → `onClick = {}` — 탭 이벤트 소비 후 아무 동작도 하지 않음.

- **`android/app/src/main/java/com/becalm/android/ui/components/CommitmentCard.kt:92-101`**: `onClick: (() -> Unit)? = null` 파라미터는 받아 놓았으나 상위에서 no-op 을 넘김 → `Role.Button` 의미론만 부여되고 실제 화면 전환 없음.

- **`android/app/src/main/java/com/becalm/android/ui/commitments/` 디렉토리 목록**:
  ```
  CommitmentManagementScreen.kt
  CommitmentManagementViewModel.kt
  ```
  → `CommitmentDetailSheet.kt`, `CommitmentDetailViewModel.kt` 없음.

- **`android/app/src/main/java/com/becalm/android/ui/navigation/Routes.kt:143-144`**:
  ```kotlin
  public data object Commitments : BecalmRoute("commitments")
  ```
  → `CommitmentDetail(commitmentId)` 라우트 엔트리 부재.

- **`android/app/src/main/java/com/becalm/android/ui/navigation/BecalmNavHost.kt:180-182`**:
  ```kotlin
  composable(route = BecalmRoute.Commitments.path) {
      CommitmentManagementScreen()
  }
  ```
  → 상세 시트용 composable 등록 없음.

검증 grep:
```bash
grep -rn "CommitmentDetailSheet\|CommitmentDetailViewModel" android/app/src/main/java/
# → empty (0 matches)

grep -n "onClick = {}" android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt
# → 1 match (line 114)
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| 카드 탭 액션 | `CommitmentDetailSheet` 열기 | no-op 람다 | 핸들러 본체 작성 |
| Detail Sheet 파일 | 별도 Composable + VM | 파일 자체 없음 | 2 파일 신규 |
| quote 전문 표시 | verbatim 전체 문자열 | 목록 카드에는 미표시 | Sheet 에서만 표시 |
| 출처 라벨 | `출처: {source_event_title}` + source_event_occurred_at | 미표시 | Sheet 전용 섹션 |
| action_state 버튼 | [리마인드]/[팔로업]/[완료]/[취소]/[편집] | Card 의 체크 아이콘만 존재 | Sheet 내 버튼 군 |
| EDIT-008 "마지막 수정" 라벨 | `last_edited_at != null` 시 표시 | 미표시 | Sheet 에서 처리 |
| 네비게이션 라우트 | `commitments/{commitment_id}` 또는 `ModalBottomSheet` | 없음 | Route 또는 State-based sheet |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술.

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt`**
   - `onClick = {}` → 선택된 commitment id 를 보관하는 local state 로 교체.
   - 화면 하단에 `CommitmentDetailSheet(commitmentId = ..., onDismiss = { ... })` 를 조건부 렌더.
   - Sheet open/close 는 `rememberModalBottomSheetState` + `Scaffold` level state 로 관리 (Route 추가 대신 in-screen sheet 로 단순화 — 스펙이 "바텀 시트" 를 명시 → ModalBottomSheet 선택).

2. **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt`**
   - 기존 VM 은 list-level 책임만 유지 (변경 최소화).
   - 단일 약속 reactive 관찰은 별도 `CommitmentDetailViewModel` 에서 수행 — list VM 에 상태 주입 금지 (Surgical Changes 원칙).

### 5.2 Files to add

1. **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentDetailSheet.kt`**
   - Composable. `ModalBottomSheet` (Material3) 사용.
   - 섹션 레이아웃:
     1. direction 배지 + title
     2. quote 전문 (읽기 전용, 긴 문단 지원 — `Text` + scrollable)
     3. "출처: {source_event_title}" + source_event_occurred_at (KST 포맷, `ui-commitment-dn-badge-kst.md` 와 동일 zone)
     4. person_ref 표시명 (ViewModel 이 `PersonEnrichmentRepository` 조인하여 전달)
     5. action_state 버튼 Row — [리마인드](CMT-005) / [팔로업](CMT-006) / [완료](CMT-007) / [취소](CMT-012) / [편집](EDIT-001)
     6. EDIT-008 보조 라벨: `last_edited_at != null` → "마지막 수정: {M/d HH:mm} (본인)", `quote_disputed=true` → "⚠️ 이의 제기됨"
   - accessibility: 각 버튼은 48 dp min touch target, `contentDescription` 한국어 명시.

2. **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentDetailViewModel.kt`**
   - `@HiltViewModel`. 생성자: `CommitmentRepository`, `PersonEnrichmentRepository`, `ReminderScheduler`, `Logger`.
   - `observeById(id)` → `CommitmentEntity` + enrichment 조인하여 `CommitmentDetailUiState` 방출.
   - 액션: `onRemind()`, `onFollowUp()`, `onComplete()`, `onCancel()`, `onEdit()` (EDIT-001 sheet open 은 local state; VM 은 id 방출만).
   - 기존 `CommitmentManagementViewModel.launchAction` 패턴 재사용하여 일관된 에러 핸들링.

### 5.3 Files to delete (dead code)
없음.

### 5.4 Non-code changes

- `strings.xml` — "출처", "마지막 수정", "이의 제기됨", 버튼 라벨 키 추가.
- 라우트 추가 여부: 현재는 in-screen sheet 로 충분. 향후 딥링크(CMT-008 notification 탭)에서 직접 상세 진입이 필요하면 별도 plan doc 로 `BecalmRoute.CommitmentDetail(id)` 추가 — 본 PR 범위 아님.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -n "onClick = {}" android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt | wc -l` = 0
- [ ] **Grep invariant**: `grep -rn "CommitmentDetailSheet" android/app/src/main/java/ | wc -l` ≥ 3 (파일 본문 + import + 호출)
- [ ] **File exists**: `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentDetailSheet.kt`
- [ ] **File exists**: `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentDetailViewModel.kt`
- [ ] **Unit test**: `CommitmentDetailViewModelTest — observeById emits entity joined with enrichment displayName`
- [ ] **UI test (Compose)**: `CommitmentDetailSheetTest — quote, source_event_title, source_event_occurred_at 모두 노드로 존재`
- [ ] **UI test**: 카드 탭 시 Sheet 가 열리고 tag "commitment_detail_sheet" 노드가 `onNodeWithTag` 에서 찾힘
- [ ] **Manual**: EDIT-001 비활성 조건 — `action_state='cancelled'` commitment 에서 [편집] 버튼이 disabled (alpha 감소 + clickable 차단)
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin` 성공

---

## 7. Out of Scope

- [리마인드] 버튼의 AlarmManager 게이트/채널 변경 — `ui-commitment-reminder-due-gate.md` 담당
- [취소] 액션 본체 (action_state 전이 + AlarmManager.cancel) — `ui-commitment-cancel-action.md` 담당
- Undo Snackbar (5초 복원) — `ui-commitment-completed-section-undo.md` 담당
- [편집] 버튼이 열어줄 `CommitmentEditSheet` — `ui-commitment-edit-sheet.md` 담당
- pull-to-refresh, 완료 섹션 접힘 — 각각 `ui-commitment-pull-refresh.md`, `ui-commitment-completed-section-undo.md`
- CMT-008 notification → 상세 시트 딥링크 — 별도 plan doc (필요 시)
- `CommitmentDetail` NavRoute 추가 — 본 PR 은 in-screen ModalBottomSheet 로 한정

---

## 8. Dependencies

- **Blocked by**: 없음 (현재 스키마로 CMT-003 핵심 UI 는 구현 가능. EDIT-008 "마지막 수정" 라벨은 `feat/db/commitment/edit-delete-dispute-supersede` (PR #20) 의 `last_edited_at` 컬럼을 읽음 — PR #20 미머지 상태에서는 해당 라벨만 conditional 렌더링 생략 가능. 구현 세션에서 PR #20 머지 여부 재확인 후 라벨 포함 여부 결정).
- **Blocks**:
  - `ui-commitment-reminder-due-gate.md` — [리마인드] 버튼의 호스트 화면
  - `ui-commitment-cancel-action.md` — [취소] 버튼 호스트
  - `ui-commitment-completed-section-undo.md` — Undo Snackbar 트리거 지점
  - `ui-commitment-edit-sheet.md` — [편집] 버튼 호스트
- **병렬 가능**: 같은 `feat/ui/commitment` 브랜치 (PR #22) 의 다른 logic unit 과 commit 레벨 누적. Sheet 컴포저블 파일 자체는 다른 plan 과 겹치지 않음.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

Sheet 파일 신규 추가 + `onClick` 람다만 수정 — Revert 시 원래 no-op 상태로 복귀, DB/네트워크 영향 없음. Safe to rollback.

---

## Appendix — Session handoff notes

- Sheet 구현 시 `ModalBottomSheet` 와 `rememberModalBottomSheetState(skipPartiallyExpanded = true)` 조합이 긴 quote 문단 렌더에 안정적. `scaffoldState` 를 쓰면 system back 처리가 자연스러움.
- `CommitmentDetailViewModel` 의 reactive 관찰은 `CommitmentRepository` 인터페이스에 `observeById(id: String)` 가 현재 없음 — PR #17/20 merge 후 DAO `findById` 가 Flow 형태로 제공되는지 재확인. 없으면 본 plan 구현 세션에서 `CommitmentRepository.observeById` 를 추가해야 함 (소규모 확장).
- quote 전문 렌더는 `SelectionContainer` 로 감싸 복사 가능하게. 법적 증거 성격 (`.spec/commitment-edit.spec.yml:97` invariant) 이므로 사용자 인용 편의성 보장.
- EDIT-008 "마지막 수정" 라벨의 KST 포맷은 `ui-commitment-dn-badge-kst.md` 의 `Asia/Seoul` zone 재사용 — 별도 util 로 추출하는 것은 후속 refactor plan 으로 분리.
- `[편집]` 버튼 disabled 로직: `action_state == "cancelled"` OR `deleted_at != null` (PR #20 후) — PR #20 미머지 상태에서는 cancelled 조건만 체크. 구현 시 주석으로 근거 명시.
