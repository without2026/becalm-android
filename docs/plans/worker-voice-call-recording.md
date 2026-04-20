# Worker / Voice / call-recording — Call/ 서브폴더 분기 + E.164 person_ref 추출

**Branch**: `feat/worker/voice/call-recording`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 1 — Voice/Call recording ingestion (MediaStore → Room)
**Severity**: High (call_recording source 가 ingestion 파이프라인에 존재하지 않음 — 스펙의 절반이 dead)
**Type**: Gap (스펙이 요구하는 call_recording 분기 + E.164 person_ref 추출이 구현 안 됨)

---

## 1. Finding

`data-ingestion.spec.yml` ING-001 은 SAF grant 된 `Recordings/` 상위 하에 두 서브디렉토리를 경로로 구분한다 — `Voice Recorder/` → `source_type='voice'`, `Call/` → `source_type='call_recording'`. 나아가 ING-001 은 통화 녹음의 경우 MediaStore 메타데이터에서 추출한 상대 번호를 **E.164 정규화** 하여 `person_ref` 에 저장하도록 요구한다. `voice-pipeline.spec.yml` VOI-001/002 는 이후 Vertex AI 파이프라인을 두 source 가 공유하되 `source_type` 을 UI 배지/필터/증거성 용도로 보존한다고 선언. 현재 android 구현은 `VoiceMediaStoreProbe.ingestVoiceRecordings` 가 `source_type="voice"` 단일 값을 하드코딩하고, `person_ref` 를 항상 null 로 둔다. Call/ 서브디렉토리 인식도 없다. 따라서 통화 녹음 경로 전체가 raw_ingestion_events 에 들어가지 않으며, person 연결도 불가능.

---

## 2. Spec Contract (무엇이어야 하는가)

- **`.spec/data-ingestion.spec.yml` ING-001** (line 17, 20) — 경로별 source_type + person_ref 규칙:
  > "상위 Recordings/ 폴더의 단일 SAF URI grant 하에 두 서브디렉토리('Voice Recorder/'=음성 메모, 'Call/'=통화 녹음)를 동시에 감시한다. source_type은 저장 경로로 구분: 'Voice Recorder/' 하위면 source_type='voice', 'Call/' 하위면 source_type='call_recording'"
  > "Room raw_ingestion_events에 {source_type:'voice'|'call_recording', sync_status:'pending', retry_count:0, person_ref: 통화 녹음의 경우 MediaStore 메타데이터에서 추출 가능한 상대 번호를 E.164 정규화하여 설정(없으면 null), 음성 메모는 null, event_title: MediaStore TITLE, duration_seconds: MediaStore DURATION/1000, commitments_extracted_count:0} 레코드 1건 INSERT됨"

- **`.spec/voice-pipeline.spec.yml` VOI-001** (line 15-16) — 파이프라인 트리거:
  > "VoiceUploadWorker가 source_type IN ('voice','call_recording'), sync_status='pending'인 raw_ingestion_event에 대해 Railway `POST /v1/voice/transcribe_extract`로 오디오 파일을 multipart 업로드하고 …"
  > "Room raw_ingestion_events에 source_type IN ('voice','call_recording'), sync_status='pending' 레코드 삽입됨"

- **`.spec/contracts/data-model.yml:32`** — 두 source 의 관계 명시:
  > "voice = Samsung 음성 녹음(Voice Recorder), call_recording = Samsung 통화 녹음(Call). 두 소스는 동일한 Vertex AI Gemini 파이프라인(VOI-001)에서 처리되며, UI·필터링·법적 증거성 표시에서만 구분됨"

- **`RawIngestionEventEntity.kt:96-99`** — person_ref 정규화 규약 (code-level SoT):
  > "Canonicalized counterparty identifier. Precedence: E.164 phone > lowercase email > normalized display name. Null for events with no identifiable counterparty"

---

## 3. Code Reality (지금 무엇인가)

### 3.1 source_type 단일값 하드코딩

- **`android/app/src/main/java/com/becalm/android/worker/ingestion/VoiceMediaStoreProbe.kt:298`** — INSERT 시:
  ```kotlin
  sourceType = SourceType.VOICE,   // 항상 "voice"
  ```
- Call/ 서브폴더 여부에 따른 분기 없음.

### 3.2 person_ref 미설정

- `RawIngestionEventEntity(... personRef = null ...)` — `VoiceMediaStoreProbe.kt:294-303` 어디에도 personRef 를 세팅하는 경로 없음. 해당 필드는 기본값 null 로 유지. 검증:
  ```bash
  grep -n "personRef" android/app/src/main/java/com/becalm/android/worker/ingestion/VoiceMediaStoreProbe.kt
  # → 0 matches
  ```

### 3.3 Call 폴더 상수 부재

- `MediaStoreWorker.kt:144-175` 의 companion 에 `RECORDER_FOLDER_SAMSUNG`, `RECORDER_FOLDER_STOCK` 은 있지만 `CALL_FOLDER` 없음. (Blocked PR `refactor/worker/voice/ingestion-realign` 이 상수만 추가하는 단계.)

### 3.4 CALL_RECORDING enum 부재

- `android/app/src/main/java/com/becalm/android/data/remote/dto/SourceTypes.kt` 에 `CALL_RECORDING` 상수 없음. (Blocked PR `feat/db/voice/call-recording-enum` 이 담당.)

### 3.5 MediaStore 메타데이터에서 상대 번호 추출 경로 없음

- Samsung One UI 6.x 통화 녹음 파일명/메타 규약 — 대체로 filename 에 전화번호가 포함됨 (예: `Call_010-1234-5678_20250415_0830.m4a`). 현재 `VoiceMediaStoreProbe.readVoiceRow` 는 `DISPLAY_NAME` 을 읽지만 그 안에서 번호 파싱 시도 없음.
- E.164 변환 유틸 (`libphonenumber` 또는 자체 코드) 존재 여부:
  ```bash
  grep -rn "libphonenumber\|PhoneNumberUtil\|toE164" android/
  # 결과: libphonenumber 의존성 / 유틸 class 존재 여부 확인 필요
  ```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| `source_type` per-path | `Voice Recorder/` → voice, `Call/` → call_recording | 항상 voice | path-aware 분기 추가 |
| `Call/` 폴더 인식 | SAF tree 자식 traversal 시 하위 디렉토리 이름 검사 | 인식 안 됨 | ingestion-realign PR 이 만든 상수 위에서 구현 |
| `person_ref` (call_recording) | MediaStore 메타 → 전화번호 → E.164 정규화 | null 고정 | 추출 + 정규화 로직 |
| `person_ref` (voice) | null (스펙 명시) | null 고정 | 변화 없음 |
| Downstream 파이프라인 | source_type IN (voice, call_recording) 를 VoiceUploadWorker 가 처리 | "pending" + voice 만 처리 | VoiceUploadWorker 조건에 call_recording 추가 |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술.

### 5.1 Files to change

- `android/app/src/main/java/com/becalm/android/worker/ingestion/VoiceMediaStoreProbe.kt`
  - `readVoiceRow` 반환 `VoiceRow` 에 새 필드 추가: `val isCallRecording: Boolean`, `val rawCounterpartyNumber: String?`
  - SAF traversal (PR #3 후속 상태) 에서 각 document 의 부모 디렉토리 이름을 읽어 `"Call"` 이면 `isCallRecording=true`, `"Voice Recorder"` 면 false
  - `insertVoiceRow` 에서:
    - `sourceType = if (row.isCallRecording) SourceTypes.CALL_RECORDING else SourceTypes.VOICE`
    - `personRef = if (row.isCallRecording) toE164OrNull(row.rawCounterpartyNumber) else null`
  - 새 private helper `extractCounterpartyNumber(displayName: String): String?` — 파일명 패턴 매칭
  - 새 private helper `toE164OrNull(raw: String?): String?` — libphonenumber 기반 정규화 (default region KR 가정, 스펙 해석 필요)

- `android/app/src/main/java/com/becalm/android/worker/ingestion/MediaStoreWorker.kt`
  - KDoc 갱신: ING-003 설명에 call_recording source 포함
  - `KIND_VOICE` 를 공유하거나 `KIND_CALL_RECORDING` 을 분리할지 결정 (**권장: KIND_VOICE 공유** — 둘 다 같은 watermark cursor 에서 관리하면 단순)

- `android/app/src/main/java/com/becalm/android/worker/VoiceUploadWorker.kt`
  - 쿼리 조건이 `sourceType = "voice"` 단일값이면 `sourceType IN ("voice", "call_recording")` 로 확장. 이미 ID 기반 직접 dispatch 이면 수정 불요 — **현재 코드 검증 필요**.
  - KDoc 정리.

- `android/app/src/main/java/com/becalm/android/data/local/db/dao/RawIngestionEventDao.kt`
  - `source_type` 필터 쿼리가 있다면 `voice` + `call_recording` 모두 포함하도록 변경. DAO 가 source-agnostic 이면 수정 불요.

### 5.2 Files to add
- `android/app/src/main/java/com/becalm/android/core/util/PhoneNumberUtils.kt` (또는 기존 util 에 함수 추가) — `toE164OrNull(raw: String, defaultRegion: String = "KR"): String?` 공용 유틸. libphonenumber 의존성 없으면 추가 (build.gradle).

### 5.3 Files to delete (dead code)
없음.

### 5.4 Non-code changes
- `android/app/build.gradle(.kts)` — `com.google.i18n.phonenumbers:libphonenumber` 의존성 추가 (사이즈 ~1MB — 이미 포함되어 있으면 no-op)
- Permission: **변경 없음**. 통화 녹음 파일 메타데이터 (DISPLAY_NAME/TITLE) 는 READ_MEDIA_AUDIO + SAF grant 로 충분. `READ_CALL_LOG` 는 **요구하지 않음** (스펙이 금지).
- 테스트 fixture: `android/app/src/test/resources/fixtures/` 에 fake SAF tree 구조 (`Recordings/Call/Call_010-1234-5678_....m4a`) 추가 고려.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -n "SourceTypes.CALL_RECORDING\|SourceType.CALL_RECORDING" android/app/src/main/java/com/becalm/android/worker/ingestion/VoiceMediaStoreProbe.kt` ≥ 1
- [ ] **Grep invariant**: `grep -n "personRef =" android/app/src/main/java/com/becalm/android/worker/ingestion/VoiceMediaStoreProbe.kt` ≥ 1
- [ ] **Unit test**: `VoiceMediaStoreProbeTest — fake SAF row under "Call/" yields sourceType='call_recording' + personRef E.164`
- [ ] **Unit test**: `PhoneNumberUtilsTest.toE164OrNull("010-1234-5678", "KR") == "+821012345678"`
- [ ] **Unit test**: `VoiceMediaStoreProbeTest — fake SAF row under "Voice Recorder/" yields sourceType='voice' + personRef=null`
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공
- [ ] **Manual (Samsung device)**: 실기기에서 통화 녹음 1 건 후 pull-to-refresh → `raw_ingestion_events` 에 `source_type='call_recording'` + `person_ref='+82...'` 행 생성 확인

---

## 7. Out of Scope

이 PR 에서 **건드리지 말 것**:
- `SourceTypes.CALL_RECORDING` 상수 자체 추가 — `feat/db/voice/call-recording-enum` 담당 (블록킹 dep)
- SAF tree URI 전환 / 폴더명 drift 수정 — `refactor/worker/voice/ingestion-realign` 담당 (블록킹 dep)
- VoiceUploadWorker 전체 재작성 — `source_type` 필터 한 줄 확장만. Multipart 포맷, Railway 계약은 손대지 않음.
- commitments insert 로직 (Stage 4) — Railway 응답 처리는 별도 Stage.
- UI 배지 (`give/take` 대신 `phone` 아이콘 등 call_recording 차별화) — `.spec/commitment-management.spec.yml` 읽고 CMT Stage 5 PR 에서 처리.
- `person_enrichment` 와의 연결 (`person_ref` 가 있으면 Enrichment 트리거) — Stage 8 Enrichment PR 담당.

---

## 8. Dependencies

- **Blocked by**:
  - `feat/db/voice/call-recording-enum` (PR #12) — `SourceTypes.CALL_RECORDING` 상수 필요
  - `refactor/worker/voice/ingestion-realign` (PR #14) — SAF tree traversal + `"Voice Recorder"` 공백 수정 + `CALL_FOLDER` 상수 필요
- **Blocks**: 없음 (하지만 Stage 2 VoiceUploadWorker 및 Stage 4/5 downstream 이 이 PR 의 데이터를 소비)

병렬 가능 여부:
- blocking dep PR 들이 모두 merge 된 **이후에만** 시작 가능. 그 전까지는 plan doc 으로만 존재.
- `refactor/worker/sms/remove-dead-code` 와는 파일 겹침 없음 → 이 PR 구현 세션은 sms 제거 PR 과 무관하게 진행.

---

## 9. Rollback plan

Revert 1 커밋. 이미 `call_recording` 행이 서버에 업로드된 상태에서 revert 하면 client 에서 해당 source_type 의 신규 insert 만 멈춤 — 기존 데이터는 남음. Railway 측 스펙은 이미 `call_recording` 를 수용하므로 스키마 이슈 없음.

```bash
git revert <commit-sha>
```

**주의**: 이 PR revert 후에도 `refactor/worker/voice/ingestion-realign` 의 Call/ 폴더 traversal 은 남음 → Call/ 파일이 "voice" 로 잘못 분류되는 상태로 돌아감 (pre-PR 상태와 동일). 회귀 위험 낮음.

---

## Appendix — Session handoff notes

- Samsung One UI 6.x 통화 녹음 파일명 규약: 실기기 샘플 수집 필요. 대략 `Call recording John Doe_01234567_250415_0830.m4a` 또는 `010-1234-5678_...m4a` 형태. 구현 전 실기기 2-3 건 관찰 권장.
- `libphonenumber` 는 이미 server 측 (Python) 에서 사용 중 — client 추가 시 region 기본값 `KR` 일관. 스펙은 defaultRegion 을 명시하지 않음 → 사용자 locale 참조 vs `KR` 하드코딩 선택 필요. **권장: `KR` 하드코딩** (B2B 국내 타겟).
- 전화번호 추출 실패 시: spec 이 "없으면 null" 이라 명시 — 예외 던지지 말고 null 반환. 이 경우 call_recording 으로 분류되되 person_ref=null → Enrichment 연결은 수동 입력으로 보완.
- `displayName` 정규화: Samsung 은 공백/특수문자/한글 이름 포함 가능. 정규식보다 libphonenumber 의 `findNumbers()` 가 robust.
- VoiceUploadWorker 의 현재 쿼리 확인: `RawIngestionEventDao.findPending` 류 함수가 source_type 필터 없이 sync_status 만으로 동작하면 이 PR 의 DAO 변경 불필요 — **구현 세션이 먼저 확인**.
- 테스트 작성 시: SAF tree URI mocking 은 `refactor/worker/voice/ingestion-realign` 세션이 만든 helper 를 재사용. 구현 세션 간 helper 공유 체크 필요.
- 기존 `RawIngestionEventEntity` 는 `personRef: String? = null` 이 이미 있으므로 엔티티/스키마 수정 불요. DAO 또한 `personRef` 를 필드로 그대로 노출.
- UI 층 영향: TodayTimelineScreen (TDY-001) 이 Commitment 카드에 person_ref 기반 표시명을 쓰므로, 이 PR 이후 통화 녹음으로부터 추출된 `+82...` 가 표시됨. Enrichment (Stage 8) 이 있으면 이름으로 치환되나 Enrichment 전에는 번호 그대로. UI 측 "숫자면 electric 아이콘 표시" 같은 치장은 별도.
