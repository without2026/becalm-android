# Worker / SMS / remove-dead-code — SMS/CallLog ingestion 전체가 스펙 외

**Branch**: `refactor/worker/sms/remove-dead-code`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 1 — Voice/Call recording ingestion (MediaStore → Room)
**Severity**: High (살아있는 dead code 는 review / CI noise + attack surface 확대)
**Type**: Dead-code (스펙에 없는 ingestion path 가 구현되어 있음)

---

## 1. Finding

앱 코드에는 SMS (`Telephony.Sms.CONTENT_URI`) 와 CallLog (`CallLog.Calls.CONTENT_URI`) 를 ContentObserver + MediaStoreWorker 로 감시하는 전체 파이프라인이 존재한다. 그러나 현재 SoT 인 `.spec/contracts/data-model.yml` 의 `raw_ingestion_events.source_type` enum 과 `data-ingestion.spec.yml`, `voice-pipeline.spec.yml` 어디에도 `sms` 또는 `call_log` 는 ingestion source 로 존재하지 않는다. 오히려 `person-enrichment.spec.yml:87` 과 `data-model.yml:483` 은 SMS·통화기록 접근을 **금지**하는 negative invariant 로 명시한다. SmsMediaStoreProbe 는 실제로도 어떤 행도 DB 에 insert 하지 않고 카운트만 관찰하며, 워커 내부에서도 `"sms"` 는 `SourceType` 이 아니라고 주석에 명시되어 있다 (`MediaStoreWorker.kt:37-39`). 즉, **기능으로서 존재하지 않지만 권한·옵저버·워커 분기는 모두 살아있는** 순수 dead code.

---

## 2. Spec Contract (무엇이어야 하는가)

- **`.spec/contracts/data-model.yml:28-32`** — `raw_ingestion_events.source_type` enum:
  > ```yaml
  > enum: [voice, call_recording, gmail, outlook_mail, naver_imap, daum_imap, google_calendar, outlook_calendar]
  > ```
  > (`sms`, `call_log` 없음)

- **`.spec/person-enrichment.spec.yml:87`** — negative invariant:
  > "EnrichmentWorker는 ContactsContract.Data만 읽는다 — SMS, 통화 기록, 기타 민감 데이터 접근 없음"

- **`.spec/contracts/data-model.yml:483`** — 스토어 심사 RISK 항목:
  > "RISK — CONTACTS 권한 부여 범위: EnrichmentWorker는 ContactsContract.CommonDataKinds.StructuredName·Nickname·Organization만 읽음. SMS·통화기록·사진 등 접근 없음. 앱 스토어 심사 시 READ_CONTACTS 사용 목적 명시 필요"

- **`.spec/data-ingestion.spec.yml` ING-001** — 인정되는 source_type 분기:
  > "source_type은 저장 경로로 구분: 'Voice Recorder/' 하위면 source_type='voice', 'Call/' 하위면 source_type='call_recording'"
  > → MediaStore Audio 경로만 정의되어 있음. SMS·CallLog 없음.

→ 스펙은 SMS/CallLog 를 **ingestion source 아님** 으로 명시하고 있음.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 Dead ingestion 관련 파일

- **`android/app/src/main/java/com/becalm/android/worker/ingestion/SmsMediaStoreProbe.kt`** (전체) — `Telephony.Sms.CONTENT_URI` 쿼리. 자체 주석 (`line 19`):
  > "SMS is not a wire `SourceType` — no `RawIngestionEventEntity` rows are inserted"
  실제로 INSERT 없음 (count 관찰 + watermark 만 advance).

- **`android/app/src/main/java/com/becalm/android/worker/ingestion/ContentObserverSms.kt`** (전체) — `Telephony.Sms.CONTENT_URI` 감시 → `MediaStoreWorker` 일회성 enqueue.

- **`android/app/src/main/java/com/becalm/android/worker/ContentObserverBootstrap.kt`**:
  - `line 19, 35, 41, 61, 67, 74, 84-87, 98-125`: SMS observer 등록/해제 로직
  - `line 62, 75, 89-92, 128-155`: CallLog observer 등록/해제 로직

- **`android/app/src/main/java/com/becalm/android/worker/ingestion/MediaStoreWorker.kt`**:
  - `line 28, 35-40, 66, 78`: SMS 경로 관련 주석
  - `line 93-98`: `smsMediaStoreProbe` 인스턴스화
  - `line 113`: `READ_SMS` 권한 체크
  - `line 123-126, 129, 134`: SMS 분기
  - `line 151, 160`: `KIND_SMS`, `SOURCE_SMS_MMS` 상수

- **`android/app/src/main/java/com/becalm/android/worker/WorkSchedulerImpl.kt`**:
  - `line 145, 209, 285-291, 299`: `SOURCE_SMS_CALL` dispatch key (와이어 SourceType 이 아니라고 자체 주석)
  - 해당 스케줄 경로 자체를 스펙이 요구하지 않음

### 3.2 테스트 / 매니페스트

- **`android/app/src/test/java/com/becalm/android/worker/ingestion/MediaStoreWorkerTest.kt`**: `line 99, 181, 237, 274, 298, 324, 368` — `READ_SMS` 권한 mocking 하는 테스트 분기 다수
- `android/app/src/main/AndroidManifest.xml`: 현재 `READ_SMS` / `READ_CALL_LOG` permission 선언은 grep 상 **미발견** (이미 제거됨 가능성 — 확인 필요). 선언이 남아있다면 제거.

검증 grep:
```bash
# 스펙 내 sms/call_log 검색 — negative 외 hit 없어야 함
grep -rn "sms\|call_log" .spec/ | grep -v "\.html"
# → person-enrichment 의 negative invariant 2 건만 hit
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| SMS ingestion | 없음 (negative invariant) | 전체 파이프라인 존재 | 전부 제거 |
| CallLog ingestion | 없음 (negative invariant) | ContentObserver 로 감시 | 전부 제거 |
| `KIND_SMS`, `SOURCE_SMS_MMS`, `SOURCE_SMS_CALL` 상수 | 없음 | MediaStoreWorker / WorkSchedulerImpl 에 존재 | 제거 |
| `READ_SMS` / `READ_CALL_LOG` 권한 | 명시적 금지 (스토어 심사 RISK) | 코드가 런타임 체크 | 권한 선언 + 체크 제거 |
| 테스트 SMS mocks | 없음 | 7+ 건 | 제거 or 단순화 |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술.

### 5.1 Files to change
- `android/app/src/main/java/com/becalm/android/worker/ContentObserverBootstrap.kt`
  - SMS observer 관련 필드/함수/로그 전부 제거 (`smsObserver`, `registerSmsObserver`, `unregisterSmsObserver`)
  - CallLog observer 관련 필드/함수/로그 전부 제거 (`callLogObserver`, `registerCallLogObserver`)
  - 클래스 주석 갱신: "Registers voice recordings content observer" 로 축소
  - 만약 남는 observer 가 0 개가 된다면 이 클래스 자체를 별도 PR 에서 재검토 — 현재는 voice SAF tree URI observer 이전이 다른 PR 에 있으므로 **이 PR 에서는 클래스 shell 은 유지** (빈 register() 는 no-op + 로그 1 줄)
- `android/app/src/main/java/com/becalm/android/worker/ingestion/MediaStoreWorker.kt`
  - `smsMediaStoreProbe` 인스턴스 + 호출 제거 (line 93-98, 123-129, 134)
  - `READ_SMS` 권한 체크 제거 (line 113, 123-126)
  - `KIND_SMS`, `SOURCE_SMS_MMS` 상수 제거 (line 151, 160)
  - KDoc 에서 "ING-002" / "SMS path" 서술 제거
- `android/app/src/main/java/com/becalm/android/worker/WorkSchedulerImpl.kt`
  - `SOURCE_SMS_CALL` dispatch key + 참조 전부 제거 (line 145, 209, 285-291, 299)
  - `UniqueWorkKeys.SMS_CALL` 도 삭제 or 관련 상수 정리
- `android/app/src/test/java/com/becalm/android/worker/ingestion/MediaStoreWorkerTest.kt`
  - `READ_SMS` 기반 테스트 케이스 제거. voice-only 경로로 단순화

### 5.2 Files to add
없음.

### 5.3 Files to delete (dead code)
- `android/app/src/main/java/com/becalm/android/worker/ingestion/SmsMediaStoreProbe.kt` (전체)
- `android/app/src/main/java/com/becalm/android/worker/ingestion/ContentObserverSms.kt` (전체)

### 5.4 Non-code changes
- `android/app/src/main/AndroidManifest.xml`: `READ_SMS`, `READ_CALL_LOG`, `READ_PHONE_STATE` (SMS 관련) permission 선언이 있다면 제거. **확인 결과**: 현재 grep 미발견 — 이미 제거된 상태로 보이나, 구현 세션이 재확인할 것.
- Hilt module: SmsMediaStoreProbe / ContentObserverSms 바인딩이 있다면 삭제 (현재 `internal class` 이므로 constructor 주입 → 없을 가능성 큼).
- DataStore SyncCursor: `KIND_SMS` watermark 키는 이전 사용자 디바이스에 남아있을 수 있으나 no-op — 마이그레이션 불필요.

---

## 6. Acceptance Criteria

- [ ] **File absence**: `test ! -e android/app/src/main/java/com/becalm/android/worker/ingestion/SmsMediaStoreProbe.kt` (exit 0)
- [ ] **File absence**: `test ! -e android/app/src/main/java/com/becalm/android/worker/ingestion/ContentObserverSms.kt` (exit 0)
- [ ] **Grep invariant**: `grep -rn "SmsMediaStoreProbe\|ContentObserverSms\|KIND_SMS\|SOURCE_SMS_MMS\|SOURCE_SMS_CALL" android/app/src/` 가 0 건
- [ ] **Grep invariant**: `grep -rn "Telephony\\.Sms\|CallLog\\." android/app/src/main/` 가 0 건 (스펙은 접근 금지를 요구)
- [ ] **Grep invariant**: `grep -rn "READ_SMS\|READ_CALL_LOG" android/` 가 0 건 (테스트 포함)
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공
- [ ] **Test gate**: `./gradlew :app:testDebugUnitTest` 통과
- [ ] **Manifest**: `grep -rn "READ_SMS\|READ_CALL_LOG" android/app/src/main/AndroidManifest.xml` 가 0 건

---

## 7. Out of Scope

이 PR 에서 **건드리지 말 것**:
- Voice ingestion path 자체 (MediaStore Audio, VoiceMediaStoreProbe) — `refactor/worker/voice/ingestion-realign` 담당
- SAF tree URI 로의 전환 — `refactor/worker/voice/ingestion-realign` 담당
- `CALL_RECORDING` enum 추가 — `feat/db/voice/call-recording-enum` 담당
- ContentObserverBootstrap 전체 폐기 결정 — voice observer 재배치 후 재평가
- `UniqueWorkKeys` 테이블 구조 개선 — 이 PR 은 SMS 관련 항목만 제거

---

## 8. Dependencies

- **Blocked by**: 없음 (main 에서 바로 분기 가능)
- **Blocks**: 없음 (voice/ingestion-realign 과 파일 겹침은 ContentObserverBootstrap / MediaStoreWorker 두 개뿐 — merge 충돌 가능성 있어 **순서 또는 동시 작업 세션 간 조율 필요**)

병렬 가능 여부:
- `feat/db/voice/call-recording-enum` 과 병렬 가능 (파일 겹침 없음)
- `refactor/worker/voice/ingestion-realign` 와 병렬 시 충돌 예상 → 이 PR 을 먼저 merge 권장 (dead code 제거 후 정리된 상태에서 realign)
- `feat/worker/voice/call-recording` 과 병렬 가능 (VoiceMediaStoreProbe 만 건드리므로)

---

## 9. Rollback plan

순수 삭제 refactor → revert 1 커밋. 데이터 손실 없음.

```bash
git revert <commit-sha>
```

Runtime 영향: 런타임에 이미 SMS/CallLog 이벤트는 어떤 DB 레코드도 만들고 있지 않으므로 revert 해도 회귀 없음. WorkScheduler 의 `SOURCE_SMS_CALL` 경로를 호출하는 외부 코드가 없는 것도 함께 확인.

---

## Appendix — Session handoff notes

- `ContentObserverBootstrap.registerVoiceObserver` (또는 voice SAF observer) 가 아직 없다. 이 PR 에서 SMS/CallLog 제거 후 `start()` 가 no-op 가 되는데, voice observer 는 `refactor/worker/voice/ingestion-realign` 에서 추가된다. 그래서 **이 PR merge 후 voice/ingestion-realign merge 전 사이에는 voice ingestion 이 observer 트리거 없이 정기 워커에만 의존하게 됨** — 기능적으로는 괜찮지만 세션 간 조율 필요.
- `MediaStoreWorker.doWork()` 에서 `smsMissing && audioMissing` retry 로직이 `audioMissing` 단독 retry 로 변경됨. 이 변경이 기존 테스트의 retry 경로 assertion 과 충돌할 수 있음.
- `WorkSchedulerImpl.SOURCE_SMS_CALL` 제거 시 WorkScheduler 인터페이스의 `enqueueMediaStoreOneShotNow()` 은 유지 — voice observer 가 여전히 사용 예정.
- 매니페스트 권한 확인 필수: 초기 grep 에서 찾지 못했지만, `<uses-permission>` 이 다른 manifest flavor 에 있을 수 있음. `find android -name 'AndroidManifest.xml' -exec grep -l SMS {} +` 로 재확인.
- Round 6x 리팩토링 중에도 SMS 파이프라인이 "SMS is not a wire SourceType" 주석과 함께 유지된 이유: 당시 스펙 확정 전이었던 것으로 추정. 현재 spec SoT 확정 (data-model.yml) 후 명백한 dead code.
