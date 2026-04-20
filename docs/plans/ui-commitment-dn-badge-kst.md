# UI / Commitment / dn-badge-kst — CommitmentCard D-N 배지가 KST 대신 UTC 로 계산

**Branch**: `feat/ui/commitment`
**Status**: PLAN ONLY — 구현은 별도 세션. 이 plan doc 이외의 코드 커밋 금지.
**E2E Stage**: 5 — CommitmentManagement 화면 렌더
**Severity**: High (사용자에게 잘못된 D-N 숫자가 표시됨 — UTC 자정 기준으로 KST 날짜가 하루 전/후로 roll over)
**Type**: Drift (스펙 "KST local date 기준" vs 코드 `ZoneOffset.UTC`)

---

## 1. Finding

`CommitmentCard.kt:124` 가 D-N 배지 계산 시 **`JLocalDate.now(ZoneOffset.UTC)`** 를 사용. 그러나 `.spec/commitment-management.spec.yml:40` CMT-004 는 "due_at까지 남은 일수를 **KST local date 기준**으로 CommitmentCard에 표시" 를 명시. KST 는 UTC+9 이므로 예: KST 2026-04-20 00:30 시점에 UTC 는 아직 2026-04-19 15:30 — 사용자가 "오늘" 인 약속을 "D-1 (내일)" 로 오인식하게 된다.

또한 CMT-004 요구의 **`due_is_approximate=true` 인 경우 `D~N` 형식**도 미구현. 이 항목은 별도 plan doc (`ui-commitment-due-is-approximate-badge.md`) 로 분리.

---

## 2. Spec Contract (무엇이어야 하는가)

- **`.spec/commitment-management.spec.yml:40` CMT-004**:
  > "D-N 배지 — due_at까지 남은 일수를 **KST local date** 기준으로 CommitmentCard에 표시한다. D-0(오늘), D-1, D-2 등. due_at이 경과(현재 시각 > due_at)한 pending/reminded/followed_up 상태에서는 빨간색 'D+N' 표시됨."

- **`.spec/commitment-management.spec.yml:42-43`** — precondition 예시:
  > "오늘 2026-04-18 KST. Room에 Commitment 1건(due_at=2026-04-20T10:00+09:00), 1건(due_at=2026-04-18T15:00+09:00), 1건(due_at=2026-04-12T00:00+09:00)"
  > "2026-04-20 카드: 'D-2' 표시됨. 2026-04-18 카드: 'D-0' 표시됨. 2026-04-12 카드: 'D+6' 빨간색 표시됨."

- **Today timeline (TDY 계열)** 역시 동일한 KST boundary 를 전제 → UI 전체의 날짜 경계 통일성 확보에 필수.

---

## 3. Code Reality (지금 무엇인가)

`android/app/src/main/java/com/becalm/android/ui/components/CommitmentCard.kt:122-128`:
```kotlin
val daysUntil: Int? = remember(dueDate) {
    dueDate?.let {
        val today = JLocalDate.now(ZoneOffset.UTC)   // ← UTC
        val jDate = JLocalDate.of(it.year, it.monthNumber, it.dayOfMonth)
        ChronoUnit.DAYS.between(today, jDate).toInt()
    }
}
```

→ KST 자정~오전 9시 사이 (UTC 전일 15:00~24:00) 사용자는 D-N 이 하루 밀려 보인다.

검증 grep:
```bash
grep -n "ZoneOffset\.UTC\|ZoneId\.of(\"UTC\")\|systemDefault()" \
  android/app/src/main/java/com/becalm/android/ui/components/CommitmentCard.kt
# → 1 match (line 124)
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| "오늘" 기준 시각대 | KST (`Asia/Seoul`) local date | UTC local date | **시각대 교체** |
| D-0 경계 | KST 2026-04-18 00:00 ~ 23:59 | UTC 2026-04-18 00:00 ~ 23:59 (= KST 09:00 ~ 익일 09:00) | 같은 데이터에 다른 "오늘" |
| due_is_approximate 처리 | `D~N` prefix | 무시 | 별도 plan (이 PR 범위 아님) |

---

## 5. Proposed Fix

### 5.1 Files to change
- `android/app/src/main/java/com/becalm/android/ui/components/CommitmentCard.kt`
  - import `java.time.ZoneId` 추가
  - line 124: `JLocalDate.now(ZoneOffset.UTC)` → `JLocalDate.now(ZoneId.of("Asia/Seoul"))`
  - import `ZoneOffset` 는 더 이상 불필요 → 제거
  - KDoc 갱신: "Today 은 KST (`Asia/Seoul`) 자정 경계 기준" 문구 추가

### 5.2 Files to add
없음. (KST 시각대는 프로젝트 표준 — 전용 헬퍼 함수 추출은 후속 refactor 가 필요하면 별도 plan)

### 5.3 Files to delete
없음.

### 5.4 Non-code changes
- 단위 테스트: `CommitmentCardTest` (또는 screenshot test) 에 KST boundary 케이스 추가
  - 예: dueDate=2026-04-18, "now" 를 2026-04-17T23:30+09:00 로 고정 → `D-1` 나와야 함 (UTC 기준이면 `D-0` 으로 틀리게 나옴). 현재 테스트는 `Clock.fixed` / `JLocalDate.now` injection 필요 — inject 되지 않는 구조라면 `ZoneId` 자체를 Composable 파라미터로 꺼내고 Preview 에서 `ZoneId.systemDefault()` 기본값을 주는 구조가 가장 작은 surgical fix.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -n "ZoneOffset\.UTC" android/app/src/main/java/com/becalm/android/ui/components/CommitmentCard.kt` = 0
- [ ] **Grep invariant**: `grep -n "Asia/Seoul" android/app/src/main/java/com/becalm/android/ui/components/CommitmentCard.kt` ≥ 1
- [ ] **Unit test**: KST 자정 경계 (23:30 KST vs 00:30 KST 양쪽) 에서 같은 `dueDate` 에 대해 D-N 이 **1 일만큼만** 달라지고 UTC 기준과 반대 결과가 나오지 않음을 검증
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공
- [ ] **Preview**: 기존 4 개 `@Preview` 가 모두 정상 렌더링 (KST 로 하드코딩 시 `LocalDate(2026,4,18)` 같은 예시도 그대로 "D-x" 값만 1 일 이동)

---

## 7. Out of Scope

- `due_is_approximate=true` 인 경우 `D~N` prefix — 별도 plan `docs/plans/ui-commitment-due-is-approximate-badge.md`
- `action_state` enum 정렬 (DRAFT/CONFIRMED/SCHEDULED/DONE ↔ pending/reminded/followed_up/completed/overdue/cancelled) — 별도 plan `docs/plans/ui-commitment-action-state-alignment.md`
- Today timeline 의 KST boundary — 동일 버그가 존재하면 Stage 6 audit 에서 `docs/plans/ui-today-…` 로 별도 추적
- 서버(`due_at` 저장 시각대) 정책 — Railway 는 `timestamptz` 라 timezone agnostic; 이 PR 은 클라이언트 렌더만 수정

---

## 8. Dependencies

- **Blocked by**: 없음. 독립 merge 가능.
- **Blocks**: 없음. 다른 Stage 5 plan 들과 파일 겹침 없음 (CommitmentCard.kt 는 이 plan 만 수정).

병렬 가능:
- `ui-commitment-action-state-alignment.md` (ViewModel + Card 둘 다 수정) 와는 `CommitmentCard.kt` 가 겹치므로 **순차**. 단 패치 영역이 다르므로 (D-N 계산 vs 상태 칩 레이블) 실제 충돌 가능성은 낮음.
- 그 외 Stage 5 plan 과는 병렬.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

revert 후 D-N 이 다시 UTC 기준으로 계산됨. 회귀 위험 낮음. Compose runtime 영향 범위가 `CommitmentCard` 로 한정됨.

---

## Appendix — Session handoff notes

- 이 PR 은 **가장 작은 수정** (1 파일 / import 1 줄 교체 + 함수 호출 1 줄 교체).
- 테스트 작성 시 `kotlinx.datetime.Clock.fixed` 사용 권장 — 다만 `CommitmentCard` 는 `java.time.LocalDate.now(ZoneId)` 를 직접 호출하므로 `ZoneId` 파라미터를 composable 로 꺼내서 주입하는 방식이 가장 테스트 용이.
- 대안 구현: `kotlinx.datetime` 전면 사용 (`Clock.System.todayIn(TimeZone.of("Asia/Seoul"))`) — BeCalm 은 이미 kotlinx-datetime 에 의존 중이므로 `java.time` 과 혼용 대신 전면 전환이 더 깔끔할 수 있음. 단 이는 **surgical fix 범위를 넘어서므로** 별도 refactor PR 로 분리 권장.
- 동일 버그가 `CommitmentManagementViewModel` / `TodayTimelineViewModel` / 기타 D-N 계산 지점에 있는지 grep 필요:
  ```bash
  grep -rn "JLocalDate\.now\|LocalDate\.now\|ChronoUnit\.DAYS\.between" android/app/src/main/
  ```
