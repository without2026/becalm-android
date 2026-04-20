# Worker / Voice / ingestion-realign — MediaStore 직접 쿼리 + 폴더명 drift 를 SAF + 올바른 경로로 정렬

**Branch**: `refactor/worker/voice/ingestion-realign`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 1 — Voice/Call recording ingestion (MediaStore → Room)
**Severity**: Critical (현재 Samsung One UI 6.x 표준 경로를 놓치므로 voice ingestion 자체가 0 건)
**Type**: Drift (스펙과 구현이 다른 경로·다른 메커니즘을 사용)

---

## 1. Finding

ONB-002/003 스펙은 Samsung One UI 6.x 기본 경로 `/storage/emulated/0/Recordings/` 의 SAF tree URI grant 를 요구하며, 이 상위 URI 한 번의 grant 로 하위 `Voice Recorder/` (음성 메모, source_type='voice') 와 `Call/` (통화 녹음, source_type='call_recording') 두 디렉토리를 동시에 커버한다. 현재 구현은 (1) SAF tree URI 가 아니라 `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` 를 직접 쿼리하며, (2) 폴더 필터가 `"VoiceRecorder"` (공백 없음) + `"Recordings"` 로 Samsung 표준 `"Voice Recorder/"` (공백 있음) 와 불일치, (3) `Call/` 서브디렉토리 분기가 아예 없다. 결과적으로 **Samsung 실기기에서 Voice Recorder 녹음 파일의 RELATIVE_PATH 가 `"Recordings/Voice Recorder/"` 여서 LIKE 조건에 매치되지 않아 ingestion 0 건**. ONB-003 의 "SAF grant는 상위 'Recordings/' 단일 URI" invariant 도 위배.

---

## 2. Spec Contract (무엇이어야 하는가)

- **`.spec/onboarding.spec.yml:29-33` ONB-002** — 자동 탐색 우선순위 + 경로 구조:
  > "자동 탐색 우선순위: (1) '/storage/emulated/0/Recordings/' (Samsung One UI 6.x 표준), (2) '/storage/emulated/0/VoiceRecorder/' (일부 구형/변형 기종 fallback). 발견 시 경로 텍스트 + 인식된 서브폴더('Voice Recorder/' 음성 메모, 'Call/' 통화 녹음) 상태가 표시됨"

- **`.spec/onboarding.spec.yml:37-44` ONB-003** — SAF tree URI 단일 grant 규칙:
  > "SAF grant는 상위 'Recordings/' 단일 URI에 부여되며 하위 'Voice Recorder/'(source_type='voice')와 'Call/'(source_type='call_recording') 두 서브디렉토리를 동시에 커버한다 — 사용자는 한 번의 폴더 선택으로 두 소스를 모두 활성화한다"
  > "grant된 URI는 tree permission으로 하위 서브디렉토리 traverse를 포함하므로 음성/통화 두 폴더를 별도 grant 없이 감지한다"

- **`.spec/data-ingestion.spec.yml` ING-001** — source_type 분기 규칙:
  > "source_type은 저장 경로로 구분: 'Voice Recorder/' 하위면 source_type='voice', 'Call/' 하위면 source_type='call_recording'"

- **`.spec/voice-pipeline.spec.yml` VOI-005** — 권한 게이트:
  > "READ_MEDIA_AUDIO 권한 미부여 시 ContentObserver가 등록되지 않고 VoiceUploadWorker도 enqueue되지 않는다"

→ 요구: **SAF tree URI `DocumentFile`/`DocumentsContract` 기반 traversal** (MediaStore Audio 쿼리 아님), 상위 `Recordings/` 단일 URI 지속(takePersistableUriPermission), Samsung 공식 폴더명 `"Voice Recorder/"` (공백 포함) 매칭, `Call/` 분기.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 폴더명 drift

- **`android/app/src/main/java/com/becalm/android/worker/ingestion/MediaStoreWorker.kt:167`**:
  ```kotlin
  public const val RECORDER_FOLDER_SAMSUNG: String = "VoiceRecorder"   // ← 공백 없음
  ```
- **`android/app/src/main/java/com/becalm/android/worker/ingestion/MediaStoreWorker.kt:174`**:
  ```kotlin
  public const val RECORDER_FOLDER_STOCK: String = "Recordings"         // ← 이건 '상위' 폴더, 음성 recorder 가 아님
  ```
- **`android/app/src/main/java/com/becalm/android/worker/ingestion/VoiceMediaStoreProbe.kt:93-98`** — LIKE 패턴 생성:
  ```kotlin
  Pair("${MediaStoreWorker.RECORDER_FOLDER_SAMSUNG}/%", "${MediaStoreWorker.RECORDER_FOLDER_STOCK}/%")
  // → "VoiceRecorder/%" OR "Recordings/%" — Samsung 실제 경로 "Recordings/Voice Recorder/..." 에 매치 안됨
  ```

### 3.2 MediaStore 직접 쿼리 (SAF 아님)

- **`android/app/src/main/java/com/becalm/android/worker/ingestion/VoiceMediaStoreProbe.kt:111`**:
  ```kotlin
  uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
  ```
- `audioUri` 도 MediaStore content URI 로 조립 (`VoiceMediaStoreProbe.kt:266-269`):
  ```kotlin
  val audioUri = ContentUris.withAppendedId(
      MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId,
  ).toString()
  ```

### 3.3 SAF 지속 코드 부재

- 온보딩 `RecordingFolderScreen.kt` 및 `OnboardingViewModel.kt` 에 `ACTION_OPEN_DOCUMENT_TREE`, `takePersistableUriPermission`, `DocumentsContract.buildChildDocumentsUriUsingTree` 호출이 전부 **없음**.
- Grep:
  ```bash
  grep -rn "openDocumentTree\|takePersistableUriPermission\|DocumentsContract" android/app/src/main/
  # → 0 matches
  ```
- 결과: ONB-003 의 SAF tree URI grant 흐름이 UI 레벨에서도 구현 안 됨.

### 3.4 Call/ 분기 부재

- VoiceMediaStoreProbe 의 어떤 분기도 `Call/` vs `Voice Recorder/` 를 구별하지 않음. 모두 `SourceType.VOICE` 로 insert.
- (`call_recording` 상수 자체가 SourceTypes.kt 에 없음 — 별도 PR `feat/db/voice/call-recording-enum` 의 의존성)

### 3.5 ContentObserver 부재 (voice 전용)

- **`android/app/src/main/java/com/becalm/android/worker/ContentObserverBootstrap.kt`** 는 SMS + CallLog observer 만 등록. `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` 또는 SAF tree URI 에 대한 observer 없음.
- VOI-005 가 요구하는 "ContentObserver 가 등록되고 VoiceUploadWorker 가 enqueue 된다" 는 **MediaStoreWorker 주기 실행 경로로만** 성립. 즉시-감지 경로는 없음.

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| 접근 메커니즘 | SAF tree URI (DocumentsContract) | MediaStore Audio 직접 쿼리 | 전면 재작성 |
| 상위 폴더 | `/storage/emulated/0/Recordings/` (Samsung One UI 6.x) | 코드 상수 `"Recordings"` 존재하지만 하위 순회 아님 | 상위 tree URI 지속 + 자식 traversal |
| Samsung 서브폴더명 | `"Voice Recorder/"` (공백 포함) | `"VoiceRecorder"` (공백 없음) | 스펙 표기로 수정 |
| Call 서브폴더 | `"Call/"` → source_type='call_recording' | 분기 없음 | 분기 추가 (**단, 실제 insert 는 PR `feat/worker/voice/call-recording` 가 담당** — 이 PR 은 폴더 인식까지만) |
| SAF 지속 | `takePersistableUriPermission` | 호출 없음 | 추가 |
| ContentObserver | 폴더 트리에 대한 observer | SMS/CallLog 에만 붙어있음 | voice 전용 observer 재배치 |
| 자동 탐색 우선순위 | (1) Recordings/ (2) VoiceRecorder/ fallback | 두 LIKE 를 OR 로 뭉침 | UI 에서 순차 탐색 + fallback |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술. 이 PR 은 **folder-name drift 수정 + MediaStore → SAF 전환의 설계 hook 작성** 까지 범위. 실제 SAF tree URI 호출부 구현은 onboarding 레이어 PR (별도, 이 PR 의 후행) 이 담당. 이 PR 은 worker 레이어 entry point 를 준비.

### 5.1 Files to change
- `android/app/src/main/java/com/becalm/android/worker/ingestion/MediaStoreWorker.kt`
  - `RECORDER_FOLDER_SAMSUNG` 값을 `"Voice Recorder"` (공백 포함) 로 수정 + doc 갱신
  - `RECORDER_FOLDER_STOCK` 을 `"Recordings"` → 상위 이름임을 명확히 하거나 제거. 스펙은 Samsung One UI 6.x 상위 `Recordings/` 밑에 `Voice Recorder/` + `Call/` 가 있다고 명시 — stock AOSP 는 별도 fallback 이므로 이름/용도 분리 필요
  - 새 상수 `CALL_FOLDER: String = "Call"` 추가 (단, 실제 분기 처리는 `feat/worker/voice/call-recording` 가 담당 — 이 PR 은 상수만 둔다)
  - KDoc 전체 정리: "reads MediaStore.Audio.Media" → "reads SAF tree URI rooted at Recordings/" 로 의도 수정

- `android/app/src/main/java/com/becalm/android/worker/ingestion/VoiceMediaStoreProbe.kt`
  - **파일명 + 클래스명 그대로 유지** (의미상 이미 MediaStore 전용이지만 refactor scope creep 방지 위해 rename 은 다음 PR)
  - `uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` → SAF tree URI (`UserPrefsStore.observeRecordingTreeUri().first()` 또는 유사 source) 로 교체
  - LIKE 패턴을 `DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getTreeDocumentId(parent))` + 자식 순회로 전환
  - Samsung 폴더 매칭 케이스를 `"Voice Recorder"` (공백) 로 수정
  - `ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)` 로 만든 `audioUri` 를 SAF 자식 document URI 로 교체. `VoiceUploadWorker` 가 `contentResolver.openInputStream(uri)` 로 읽는 경로가 SAF URI 와 호환되는지 확인 필요
  - DATE_ADDED 기반 watermark → `DocumentsContract.Document.COLUMN_LAST_MODIFIED` 기반 watermark 로 변경

- `android/app/src/main/java/com/becalm/android/worker/ContentObserverBootstrap.kt`
  - (PR `refactor/worker/sms/remove-dead-code` 가 SMS/CallLog 섹션 제거한 이후에) voice 전용 observer 등록 함수 추가 — `contentResolver.registerContentObserver(treeUri, notifyForDescendants=true, observer)` 로 SAF tree URI 변경 시점에 MediaStoreWorker 일회성 enqueue. **이 PR 이 sms 제거 PR 보다 나중에 merge 되면 충돌 해결 세션이 해야 함**

### 5.2 Files to add
- `android/app/src/main/java/com/becalm/android/data/local/datastore/RecordingFolderStore.kt` (또는 `UserPrefsStore` 확장) — persistable tree URI 를 보관 / 조회. 이미 store 가 있으면 새 key 만 추가.
- 온보딩 측 실제 SAF picker launch 코드 (`ActivityResultContracts.OpenDocumentTree()` + `takePersistableUriPermission`) 는 **이 PR 에서 포함. UI 쪽 파일은 `RecordingFolderScreen.kt` 수정으로 처리**. UI 큰 재작업이 예상되면 서브-PR 로 분리 권장 (`refactor/ui/onboarding/saf-tree`) — **이 plan doc 에서는 원칙만, 분리 여부는 구현 세션 판단**.

### 5.3 Files to delete (dead code)
없음 (SMS dead code 는 별도 PR 담당).

### 5.4 Non-code changes
- 권한: `READ_MEDIA_AUDIO` 는 유지. SAF tree URI 가 있으면 MediaStore Audio 직접 쿼리는 불필요 → `READ_MEDIA_AUDIO` 를 제거해도 되는지 재검토. 스펙 VOI-005 는 여전히 READ_MEDIA_AUDIO 체크를 언급 → 유지가 안전.
- DataStore 마이그레이션: 기존에 저장된 `KIND_VOICE` watermark 의 단위가 epoch ms. SAF 기반 재구현도 ms 로 통일하면 migration 불필요.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn '"VoiceRecorder"' android/app/src/main/` 가 0 (스펙 공백 포함 버전으로 통일)
- [ ] **Grep invariant**: `grep -rn '"Voice Recorder"' android/app/src/main/java/com/becalm/android/worker/ingestion/` 가 1 이상
- [ ] **Grep invariant**: `grep -rn "MediaStore\\.Audio\\.Media\\.EXTERNAL_CONTENT_URI" android/app/src/main/java/com/becalm/android/worker/` 가 0
- [ ] **Grep invariant**: `grep -rn "takePersistableUriPermission\|openDocumentTree\|DocumentsContract" android/app/src/main/` 가 1 이상
- [ ] **Unit test**: `VoiceMediaStoreProbeTest` — SAF tree URI mock 으로 자식 순회 + Samsung `"Voice Recorder/"` 폴더 파일만 `SourceType.VOICE` 로 insert 됨을 검증
- [ ] **Unit test**: `RecordingFolderScreenTest` (또는 ViewModel test) — `Recordings/` SAF picker launch 및 `takePersistableUriPermission` 호출 검증
- [ ] **Integration test (Robolectric or device)**: MediaStore 에 `RELATIVE_PATH = "Recordings/Voice Recorder/"` 인 가짜 audio row 주입 후, SAF tree URI grant + probe 1 회 실행 → raw_ingestion_events 1 건 insert 됨
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공
- [ ] **Manual (device)**: Samsung One UI 6.x 실기기에서 Voice Recorder 로 녹음 → 앱 재실행 또는 pull-to-refresh → `raw_ingestion_events` 에 해당 파일 1 건 appear

---

## 7. Out of Scope

이 PR 에서 **건드리지 말 것**:
- `Call/` 하위 파일을 실제로 `SourceType.CALL_RECORDING` 으로 insert 하는 로직 — `feat/worker/voice/call-recording` 가 담당. 이 PR 은 `Call/` 서브폴더를 **인식만** 하고 상수 추가.
- `SourceTypes.CALL_RECORDING` 상수 추가 — `feat/db/voice/call-recording-enum` 담당.
- SMS/CallLog 제거 — `refactor/worker/sms/remove-dead-code` 담당.
- `VoiceMediaStoreProbe` 파일/클래스 rename — 다음 리팩토링 PR.
- Upload 경로 (`VoiceUploadWorker`) 변경 — SAF URI 가 `openInputStream` 에 통과됨을 검증까지만. Upload 로직 재작성 필요 시 별도 PR.
- ContentObserver 완전 철폐 또는 voice 전용 별도 class 로 분리 — observer 동작 검증만 하고 class 재구성은 후속 PR.

---

## 8. Dependencies

- **Blocked by**: 권장 — `refactor/worker/sms/remove-dead-code` 를 먼저 merge 하여 `ContentObserverBootstrap` / `MediaStoreWorker` 가 정리된 상태에서 voice 작업 진행. (필수 아님 — 충돌은 해결 가능하나 리스크 ↑)
- **Blocks**: `feat/worker/voice/call-recording` (Call/ 분기가 이 PR 의 SAF traversal 위에서 동작)

병렬 가능 여부:
- `feat/db/voice/call-recording-enum` 과 병렬 가능 (파일 겹침 없음)
- `refactor/worker/sms/remove-dead-code` 와 충돌 가능 (ContentObserverBootstrap, MediaStoreWorker) → 순차 진행 권장

---

## 9. Rollback plan

이 PR 은 core ingestion 경로를 교체하므로 revert 영향 큼.

1. `git revert <commit-sha>` → MediaStore 직접 쿼리로 복귀
2. 사용자 디바이스에 저장된 SAF persistable URI 는 그대로 남음 (무해 — revert 후에는 읽지 않음)
3. 기존 `KIND_VOICE` watermark 단위 (epoch ms) 는 동일하므로 추가 migration 불필요

주의: revert 시점에 이미 `call_recording` source_type 으로 insert 된 행이 Room/서버에 있으면 (→ `feat/worker/voice/call-recording` 이후 상태) 그 행들이 고아화. 순차 revert 필요 (`feat/worker/voice/call-recording` → 이 PR 순서).

---

## Appendix — Session handoff notes

- SAF URI 로 전환할 때 가장 큰 함정: `ContentResolver.openInputStream(uri)` 가 MediaStore content URI 와 SAF document URI 모두에서 동작하지만, 파일 메타데이터 (`DATE_ADDED`, `DURATION`, `DISPLAY_NAME`) 조회 방식이 다르다. SAF 는 `DocumentsContract.queryChildDocuments` + `DocumentsContract.Document.COLUMN_LAST_MODIFIED`, `COLUMN_DISPLAY_NAME`, `COLUMN_SIZE` 등 다른 컬럼 세트. DURATION 은 SAF 에 없음 → MediaStore 보조 쿼리 또는 `MediaMetadataRetriever` 사용.
- Samsung 실기기 검증 필수. 에뮬레이터로는 `Recordings/Voice Recorder/` 경로 재현 어려움. 가능하면 MediaStore 에 `INSERT` 로 fake row + `RELATIVE_PATH='Recordings/Voice Recorder/'` 주입 테스트.
- `takePersistableUriPermission` 은 Activity context 필요 → ViewModel 에서 직접 호출 불가. `ActivityResultContracts.OpenDocumentTree()` 결과 콜백 내에서 `context.contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` 패턴. Compose 라면 `rememberLauncherForActivityResult`.
- `RecordingFolderScreen.kt` 가 지금은 단순 텍스트 스캐폴드 — SAF picker launch + 결과 surface 는 이 PR 에서 ViewModel 에 state 를 추가하고 Screen 에서 launcher 트리거하는 식으로 연결.
- watermark 단위: SAF `COLUMN_LAST_MODIFIED` 는 epoch ms → 이미 저장된 값과 단위 일치. MediaStore 의 `DATE_ADDED` (sec) / 1000 변환 코드는 삭제.
- `VoiceUploadWorker.KEY_AUDIO_URI` 는 String 으로 받음 → SAF URI 문자열 그대로 전달. Worker 내부에서 `contentResolver.openInputStream(Uri.parse(audioUri))` 하면 동작 (검증 필요).
- 기존 테스트 (`MediaStoreWorkerTest.kt` ≈ 400 줄) 는 MediaStore fake row 기반 → 상당수 SAF fake tree 로 대체 필요. 테스트 재작성 규모 큼 → 구현 세션이 별도 세션으로 쪼갤 가치 있음.
