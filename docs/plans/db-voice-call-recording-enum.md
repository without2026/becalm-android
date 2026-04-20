# DB / Voice / call-recording-enum — SourceTypes에 CALL_RECORDING 누락

**Branch**: `feat/db/voice/call-recording-enum`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 1 — Voice/Call recording ingestion (MediaStore → Room)
**Severity**: High (blocks Stage 1 call_recording surfacing and downstream Stages 3–5)
**Type**: Gap (스펙 enum 요구는 있으나 앱 측 enum 상수 없음)

---

## 1. Finding

`data-model.yml` (contracts SoT) 는 `raw_ingestion_events.source_type` 과 `commitments.source_type` enum 값에 `call_recording` 을 포함한다. 그러나 Android 앱의 enum/상수 모음 파일(`SourceTypes.kt`)에는 `CALL_RECORDING` 상수가 없고, `ALL` 집합에도 포함되어 있지 않다. 결과적으로 call 녹음 ingestion 경로가 타입-세이프하게 표현되지 않으며, Stage 1 워커·Stage 4 Room DAO·Stage 5 UI 가 이 값을 참조할 수 없다. 또한 `commitments` 전용인 `manual` 값도 enum 상수로 존재하지 않는다(out of scope — 별도 PR에서 다룸).

---

## 2. Spec Contract (무엇이어야 하는가)

- **`.spec/contracts/data-model.yml:28-32`** — `raw_ingestion_events.source_type` enum:
  > ```yaml
  > source_type:
  >   type: text
  >   not_null: true
  >   enum: [voice, call_recording, gmail, outlook_mail, naver_imap, daum_imap, google_calendar, outlook_calendar]
  > ```

- **`.spec/contracts/data-model.yml:160-163`** — `commitments.source_type` enum:
  > ```yaml
  > source_type:
  >   type: text
  >   not_null: true
  >   enum: [voice, call_recording, gmail, outlook_mail, naver_imap, daum_imap, google_calendar, outlook_calendar, manual]
  > ```

- **`.spec/data-ingestion.spec.yml` ING-001** — source_type 분기 규칙:
  > "source_type은 저장 경로로 구분: 'Voice Recorder/' 하위면 source_type='voice', 'Call/' 하위면 source_type='call_recording'"

---

## 3. Code Reality (지금 무엇인가)

- **`android/app/src/main/java/com/becalm/android/data/remote/dto/SourceTypes.kt`** — 현재 정의된 상수:
  - `VOICE`, `GMAIL`, `OUTLOOK_MAIL`, `NAVER_IMAP`, `DAUM_IMAP`, `GOOGLE_CALENDAR`, `OUTLOOK_CALENDAR`
  - **누락**: `CALL_RECORDING`, `MANUAL`
  - `ALL` 집합에도 `call_recording` 미포함

검증 grep:
```bash
# 0 matches — call_recording 는 android/ 소스 어디에도 존재하지 않음
grep -rn "call_recording" android/app/src/main/
# 0 matches — CALL_RECORDING 상수 부재
grep -rn "CALL_RECORDING" android/app/src/main/
```

참고: MediaStoreWorker / VoiceMediaStoreProbe 는 현재 `KIND_VOICE = "voice"` 만 사용하고 call 녹음 분기 자체가 없다 (→ 별도 PR `feat/worker/voice/call-recording` 에서 처리).

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| `raw_ingestion_events.source_type` enum | voice / call_recording / … 8개 | 앱 상수 7개 (call_recording 없음) | `CALL_RECORDING` 상수 + ALL 집합 entry 필요 |
| 워커가 `source_type='call_recording'` 로 INSERT 할 수 있는가 | 가능해야 함 | 불가 (상수 자체 없음) | enum 상수 먼저 추가해야 worker PR 가능 |
| `commitments.source_type` enum | voice / call_recording / … / manual 9개 | 상수 7개 (call_recording, manual 없음) | `MANUAL` 은 이 PR out of scope |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술.

### 5.1 Files to change
- `android/app/src/main/java/com/becalm/android/data/remote/dto/SourceTypes.kt`
  - `public const val CALL_RECORDING: String = "call_recording"` 상수 추가
  - `ALL` 집합에 `CALL_RECORDING` 항목 추가 (기존 원소들과 동일한 위치 규칙 따름)
  - KDoc 또는 한 줄 주석: "Matches `data-model.yml:28-32` enum. 통화 녹음 (Samsung One UI `Recordings/Call/` 하위)"

### 5.2 Files to add
없음.

### 5.3 Files to delete (dead code)
없음.

### 5.4 Non-code changes
- DB migration: **필요 없음** — Supabase 측 enum 검증은 Railway API/DB 레이어가 담당. 앱은 문자열 리터럴만 보냄. 이 PR 는 클라이언트 상수 정리만.
- Permission 변경: 없음.
- Config / manifest: 없음.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "\"call_recording\"" android/app/src/main/java/com/becalm/android/data/remote/dto/SourceTypes.kt` 가 1 이상이다
- [ ] **Grep invariant**: `grep -rn "CALL_RECORDING" android/app/src/main/java/com/becalm/android/data/remote/dto/SourceTypes.kt` 가 2 이상이다 (선언 1 + ALL 집합 1)
- [ ] **Unit test**: `SourceTypesTest` (신규 또는 기존) — `SourceTypes.ALL contains "call_recording"` 테스트 통과
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin` 성공
- [ ] **Cross-ref**: `data-model.yml:28-32` 의 enum 8개 값 ⊆ `SourceTypes.ALL` (단, 이 PR 뒤에 `manual` 만 미포함 상태로 남음 — 별도 task)

---

## 7. Out of Scope

이 PR 에서 **건드리지 말 것**:
- `MANUAL` enum 상수 추가 — 별도 task (commitments 전용, Stage 5 CMT 관련)
- 워커 레벨 call_recording 분기 로직 — `feat/worker/voice/call-recording` PR 이 담당
- MediaStoreWorker 의 `KIND_*` 상수 (SourceType 과 별개 개념 — 워커 내부 라벨링)
- DAO / Room entity 변경 — source_type 은 이미 TEXT 저장이라 스키마 수정 불필요
- 파일 경로 매핑("Voice Recorder/" vs "Call/") — `refactor/worker/voice/ingestion-realign` PR 이 담당

---

## 8. Dependencies

- **Blocked by**: 없음 (main 에서 바로 분기 가능)
- **Blocks**: `feat/worker/voice/call-recording` — 워커 분기가 `SourceTypes.CALL_RECORDING` 상수를 참조해야 함

Merge 순서: 이 PR 먼저 → `feat/worker/voice/call-recording` 후속.

병렬 가능 여부: `refactor/worker/sms/remove-dead-code` 와 병렬 가능 (파일 겹침 없음). `refactor/worker/voice/ingestion-realign` 와도 병렬 가능 (VoiceMediaStoreProbe 건드리지 않음).

---

## 9. Rollback plan

단일 파일 수정이므로 commit revert 한 줄로 원복.

```bash
git revert <commit-sha>
```

이미 `source_type='call_recording'` 문자열이 서버에 올라간 상태에서 revert 하면 상수 참조만 사라지고 문자열 리터럴은 남음 → 기능은 유지됨. 역방향 회귀 없음.

---

## Appendix — Session handoff notes

- `SourceTypes.kt` 는 순수 상수 holder — companion object 가 아닌 top-level `object SourceTypes` 로 추정됨. 기존 코드 스타일 그대로 따를 것.
- 네이밍 참고: 현재 `OUTLOOK_MAIL`, `GOOGLE_CALENDAR` 처럼 SCREAMING_SNAKE_CASE 상수 + 소문자 snake_case 값. `CALL_RECORDING = "call_recording"` 이 같은 패턴.
- 검토한 대안: enum class 로 전환 — 기각. 나머지 상수들이 string const 이므로 단일 enum 추가는 스타일 불일치 유발. Scope 창피.
- 함정: `ALL` 이 `Set<String>` 인지 `List<String>` 인지 파일 확인 필요. 순서 가정하는 호출부 있으면 회귀 유의.
- 검증 필수 호출부: `remote/dto/` 내 DTO 의 `source_type` 필드 validator 가 `ALL` 을 참조한다면 새 값 자동 허용됨. 참조 없으면 이 PR 은 순수 추가.
