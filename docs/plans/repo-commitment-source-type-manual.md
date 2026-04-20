# Repo / Commitment / source-type-manual — SourceType enum 에 'manual' 상수 누락

**Branch**: `feat/repo/commitment/source-type-manual`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 4 (enum / DTO 레벨) — manual-commitment.spec.yml MAN-001..006 전제
**Severity**: Medium (수동 약속 생성 UI 구현 시점에 필요. 현재 미구현이라 즉시 실패는 없으나 Stage 5 blocker)
**Type**: Drift (data-model.yml 의 enum 확장 명시가 코드에 미반영)

---

## 1. Finding

`SourceType` 상수 객체 (`android/app/src/main/java/com/becalm/android/data/remote/dto/SourceTypes.kt`) 는 7 개 값을 선언한다:
- VOICE, GMAIL, OUTLOOK_MAIL, NAVER_IMAP, DAUM_IMAP, GOOGLE_CALENDAR, OUTLOOK_CALENDAR

그러나 `.spec/contracts/data-model.yml:166-167` 는 9 개를 명시:
> "enum: voice | **call_recording** | gmail | outlook_mail | naver_imap | daum_imap | google_calendar | outlook_calendar | **manual**"

그리고 `.spec/contracts/data-model.yml:168`:
> "'manual' = user-created via CommitmentCreateSheet (manual-commitment.spec.yml MAN-001..006). No associated raw_ingestion_events row."

`CALL_RECORDING` 은 PR #12 에서 처리. 본 PR 은 남은 한 값 `MANUAL` 만 다룸.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/contracts/data-model.yml:166-168`
```yaml
- name: source_type
  # enum: voice | call_recording | gmail | outlook_mail | naver_imap | daum_imap | google_calendar | outlook_calendar | manual
  # 'manual' = user-created via CommitmentCreateSheet (MAN-001..006). No associated raw_ingestion_events row.
```

### 2.2 `.spec/contracts/data-model.yml:485` — 마이그레이션 불필요
> "마이그레이션 DDL 변경 없음(컬럼 타입은 TEXT) — 애플리케이션 레벨 enum 검증만 업데이트."

### 2.3 `.spec/manual-commitment.spec.yml` MAN-003 invariant
> raw_ingestion_events.source_type 은 확장하지 않음 — manual 약속은 원본 이벤트가 없어 raw 테이블에 row 를 만들지 않음.

즉, `SourceType.MANUAL` 은 **commitments 테이블에서만 유효**하고 raw_ingestion_events 에서는 사용 금지.

### 2.4 Railway 측 validation 확장 (out-of-scope, 언급만)
> "Railway 는 POST /v1/commitments 에서 source_type='manual' 을 수락하도록 서버 validation 확장 필요." — Railway 팀 담당.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `SourceTypes.kt:14-46`
```kotlin
public object SourceType {
    public const val VOICE: String = "voice"
    public const val GMAIL: String = "gmail"
    public const val OUTLOOK_MAIL: String = "outlook_mail"
    public const val NAVER_IMAP: String = "naver_imap"
    public const val DAUM_IMAP: String = "daum_imap"
    public const val GOOGLE_CALENDAR: String = "google_calendar"
    public const val OUTLOOK_CALENDAR: String = "outlook_calendar"
    // MISSING: CALL_RECORDING (PR #12), MANUAL (this PR)
    public val ALL: Set<String> = setOf(/* 7 */)
}
```

### 3.2 `ALL` validation set 도 7 개만 포함
(PR #12 가 CALL_RECORDING 추가 후 본 PR 이 MANUAL 추가)

### 3.3 CommitmentCreateSheet 코드 0 건
```bash
grep -rn "CommitmentCreateSheet\|SourceType.MANUAL\|\"manual\"" android/app/src/main/ 2>/dev/null
# → empty
```
→ 이 PR 은 enum 값만 추가하고 UI 구현은 Stage 5 PR 에서.

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| enum 상수 수 (commitments) | 9 | 7 | +1 (MANUAL) — CALL_RECORDING 은 PR #12 |
| ALL set 멤버 | 9 개 포함 | 7 개 | set 갱신 |
| 사용처 | CommitmentCreateSheet (MAN-001) 에서 commitment INSERT 시 | 미구현 | UI PR 의존 |

---

## 5. Proposed Fix

### 5.1 Files to change

**`android/app/src/main/java/com/becalm/android/data/remote/dto/SourceTypes.kt`**

```kotlin
/**
 * User-created commitment via CommitmentCreateSheet. No associated raw_ingestion_events row.
 * See manual-commitment.spec.yml MAN-001..006.
 *
 * Valid ONLY on [CommitmentEntity.sourceType] — raw_ingestion_events never uses "manual"
 * (MAN-003 invariant).
 */
public const val MANUAL: String = "manual"
```

그리고 `ALL` set 에 MANUAL 추가 (PR #12 도 CALL_RECORDING 추가 필요 — 본 PR 은 MANUAL 만).

### 5.2 Files to add
없음.

### 5.3 Files to delete
없음.

### 5.4 Non-code changes
- Railway `/v1/commitments` POST validation 확장 — 서버 팀 별도 세션.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -n "MANUAL.*=.*\"manual\"" android/app/src/main/java/com/becalm/android/data/remote/dto/SourceTypes.kt` = 1
- [ ] **Grep invariant**: `grep -n "MANUAL" android/app/src/main/java/com/becalm/android/data/remote/dto/SourceTypes.kt` ≥ 2 (선언 + ALL set)
- [ ] **Unit test**: `SourceTypesTest — ALL contains MANUAL and is consistent with data-model.yml enum list`
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공

---

## 7. Out of Scope

- CommitmentCreateSheet UI (MAN-001..006) — 별도 `feat/ui/commitment/manual-create` PR
- Railway 서버 측 validation — 서버 팀
- `CALL_RECORDING` 추가 — PR #12
- raw_ingestion_events.source_type 에 MANUAL 사용 금지 invariant enforcement — Stage 5 UI PR 에서 guard

---

## 8. Dependencies

- **Blocked by**: 없음 (완전 독립, 1 파일 ~3 줄)
- **Blocks**: `feat/ui/commitment/manual-create` (미래 PR)
- **병렬 가능**:
  - 모든 다른 열린 PR (#12, #13, #14, #15, #16, #17, #18, #19, #20)
  - 단 PR #12 도 동일 파일 `SourceTypes.kt` 를 수정 — 둘 중 하나가 먼저 머지 후 rebase 필요

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

단순 enum 상수 추가라 rollback 위험 매우 낮음. Revert 후 manual 타입을 사용하던 UI 가 있다면 컴파일 에러가 날 뿐 기존 동작 회귀 없음. 이미 저장된 `"manual"` 값의 row 는 TEXT 컬럼이라 DB 에 그대로 존재 — 다음 배포에서 다시 enum 이 재도입되면 복구됨.

---

## Appendix — Session handoff notes

- **가장 작은 PR 중 하나** (1 파일, 2-3 줄). 빠른 머지 추천.
- `ALL` set 은 `setOf(VOICE, ..., MANUAL)` 로 갱신 — 순서는 spec 문자열 순서와 동일 유지.
- PR #12 와 같은 파일 수정이므로 머지 순서에 따라 간단한 rebase (single-line) 필요. 먼저 머지되는 쪽이 다른 상수 선언 아래에 새 줄 삽입.
- Validation guard: `require(entity.sourceType in SourceType.ALL)` 같은 검증이 코드베이스에 있는지 grep 해서 확인 — 있으면 해당 호출부가 자동으로 MANUAL 허용.
- 관련 lint/spec-check 스크립트 (`/.github/scripts/` 아래) 가 enum 리스트를 하드코딩하고 있지 않은지 확인.
