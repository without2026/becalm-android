# Repo / Voice / commitment-source-type-inherit — VoiceUploadMappers 가 commitment.source_type 을 voice 로 하드코딩

**Branch**: `fix/repo/voice/commitment-source-type-inherit`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 2 — VoiceUploadWorker → Railway multipart → commitments INSERT
**Severity**: High (call_recording 녹음으로부터 생성된 commitments 가 UI/필터/증거성 표시에서 "voice" 로 잘못 보인다 — 스펙이 명시적으로 금지한 동작)
**Type**: Drift (스펙의 "원본 source_type 상속" 요구와 하드코딩 불일치)

---

## 1. Finding

`VoiceUploadMappers.toCommitmentEntity` 가 extracted `CommitmentEntity.sourceType` 을 **항상 `SourceType.VOICE`** 로 세팅한다. 그러나 VOI-001 은 "추출된 commitment 의 source_type 은 원본 raw event 의 source_type 을 그대로 상속한다" 를 명시. `data-model.yml:32` 도 "voice = Samsung 음성 녹음(Voice Recorder), call_recording = Samsung 통화 녹음(Call). 두 소스는 동일한 Vertex AI Gemini 파이프라인(VOI-001)에서 처리되며, UI·필터링·법적 증거성 표시에서만 구분됨" 이라고 declarative 하게 강제. 현재 구현은 Stage 5 CommitmentManagement UI 에서 통화 녹음 기반 약속을 "voice" 배지로 노출 — 스펙 위배 + 법적 증거성 왜곡.

---

## 2. Spec Contract (무엇이어야 하는가)

- **`.spec/voice-pipeline.spec.yml:18` VOI-001**:
  > "추출된 commitment의 source_type은 원본 raw event의 source_type을 그대로 상속한다"

- **`.spec/contracts/data-model.yml:32`** — 두 source 의 관계:
  > "voice = Samsung 음성 녹음(Voice Recorder), call_recording = Samsung 통화 녹음(Call). 두 소스는 동일한 Vertex AI Gemini 파이프라인(VOI-001)에서 처리되며, UI·필터링·법적 증거성 표시에서만 구분됨"

- **`.spec/contracts/data-model.yml:160-163`** — `commitments.source_type` enum 에 call_recording 포함.

---

## 3. Code Reality (지금 무엇인가)

- **`android/app/src/main/java/com/becalm/android/worker/VoiceUploadMappers.kt:87`**:
  ```kotlin
  sourceType = SourceType.VOICE,
  ```
  → raw event 가 `source_type='call_recording'` 여도 commitment 는 `"voice"` 로 저장됨.

- `toCommitmentEntity` signature (line 62-70) — `RawIngestionEventEntity.sourceType` 을 파라미터로 받지 않음. 호출부 `VoiceUploadWorker.kt:192-202` 도 `entity.sourceType` 을 전달하지 않음.

검증 grep:
```bash
grep -n "sourceType\s*=\s*SourceType\.VOICE" android/app/src/main/java/com/becalm/android/worker/VoiceUploadMappers.kt
# → 1 match (line 87)
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| commitment.source_type 결정 | raw event.source_type 상속 | `SourceType.VOICE` 하드코딩 | 원본 전달 + 사용 |
| call_recording 구분 | UI 배지 / 필터 / 증거성 에서 노출 | 모두 "voice" 로 집계됨 | 고쳐야 함 |

---

## 5. Proposed Fix

### 5.1 Files to change
- `android/app/src/main/java/com/becalm/android/worker/VoiceUploadMappers.kt`
  - `toCommitmentEntity` 파라미터 목록에 `sourceType: String` 추가 (RawIngestionEventEntity.sourceType 을 받음)
  - 함수 본문의 `sourceType = SourceType.VOICE` → `sourceType = sourceType` (인자로 받은 값)
  - KDoc 갱신: "Inherited from raw event per VOI-001"
- `android/app/src/main/java/com/becalm/android/worker/VoiceUploadWorker.kt`
  - `mapIndexed { index, dto -> dto.toCommitmentEntity(... ) }` 호출부 (line 192-202) 에 `sourceType = entity.sourceType` 추가

### 5.2 Files to add
없음.

### 5.3 Files to delete
없음.

### 5.4 Non-code changes
- DB migration: **필요 없음** — `CommitmentEntity.sourceType` 컬럼은 이미 존재. 단, 기존 rows 에 `"voice"` 로 잘못 저장된 call_recording 데이터의 backfill 전략은 별도 결정 (권장: **backfill 안 함**. 사용자에게 영향 미미 + race window 가 개발 기간 내부로 제한).
- 테스트: `VoiceUploadMappersTest` 에 call_recording 케이스 추가.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -n "sourceType\\s*=\\s*SourceType\\.VOICE" android/app/src/main/java/com/becalm/android/worker/VoiceUploadMappers.kt` = 0
- [ ] **Grep invariant**: `grep -n "sourceType\\s*=\\s*sourceType\\b\\|sourceType = entity\\.sourceType" android/app/src/main/java/com/becalm/android/worker/` ≥ 2 (mapper + caller)
- [ ] **Unit test**: `VoiceUploadMappersTest — toCommitmentEntity inherits sourceType from raw event (voice/call_recording 둘 다)`
- [ ] **Unit test**: `VoiceUploadWorkerTest — call_recording raw event 업로드 후 inserted CommitmentEntity.sourceType == "call_recording"`
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공

---

## 7. Out of Scope

- `CALL_RECORDING` enum 상수 추가 — Stage 1 PR `feat/db/voice/call-recording-enum` (PR #12) 담당
- 워커 레벨 call_recording 분기 로직 — `feat/worker/voice/call-recording` (PR #15) 담당
- commitment schema (`due_at`/`due_hint`/`due_is_approximate`) 확장 — 별도 PR `feat/db/commitment/due-at-hint-approximate`
- 기존 Room DB 에 잘못 저장된 "voice" 행 backfill

---

## 8. Dependencies

- **Blocked by**:
  - `feat/db/voice/call-recording-enum` (PR #12) — `SourceTypes.CALL_RECORDING` 상수 필요 (테스트용)
  - `feat/worker/voice/call-recording` (PR #15) — call_recording source_type 이 실제로 raw event 에 들어와야 이 PR 이 의미를 가짐. 단, 이 PR 자체는 **독립 merge 가능** (모든 source_type 에 대해 동작 — `"voice"` 입력 시에도 `"voice"` 출력으로 backward compatible)
- **Blocks**: Stage 5 CommitmentManagement 의 source_type 필터 / 배지 관련 behavior

병렬 가능:
- `feat/db/commitment/due-at-hint-approximate` 와 병렬 가능 (같은 파일 수정이지만 다른 필드)
- `fix/worker/voice/pipa-insert-status` 와 병렬 가능 (파일 겹침 없음)

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

revert 후 신규 commitment 는 다시 "voice" 로 저장됨. 회귀 위험 낮음. 이미 "call_recording" 로 저장된 행은 그대로 유지 (DB 스키마 enum 이 이미 수용).

---

## Appendix — Session handoff notes

- 이 PR 은 **가장 작은 수정** (두 파일 / 3-5 줄). blocker dep 체인의 끝에서 merge 하면 의미가 완성되지만, 독립 merge 도 안전.
- 테스트 작성 시 `RawIngestionEventEntity(sourceType = "call_recording", ...)` 를 직접 주입 — enum 상수 부재 시 문자열 리터럴 사용 가능.
- `CommitmentEntity.sourceType` 의 KDoc (line 53-54) 도 갱신 필요: enum 에 `call_recording` 포함 명시.
- 호출부 외에도 `toCommitmentEntity` 를 사용하는 다른 callsite 가 있으면 모두 `sourceType` 인자 추가 필요. grep 으로 확인:
  ```bash
  grep -rn "toCommitmentEntity\\s*(" android/
  ```
