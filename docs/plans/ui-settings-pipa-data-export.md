# UI / Settings / PIPA Data Export — 내 데이터 다운로드 (PIPA-002 열람권)

**Branch**: `feat/ui/settings/pipa-rights`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 6 (Settings → PIPA 권리 실행)
**Severity**: Critical (PIPA 제35조 열람권 — MVP 법규 블로커)
**Type**: Gap (DataExportScreen / ZIP 생성 코루틴 / SAF 통합 전부 부재)

---

## 1. Finding

PIPA 제35조 "정보주체의 열람권" 을 MVP 에서 실행할 수 있는 코드 경로가 **전무**하다.
현재 `SettingsSourcesSection.kt:43-48` 에 "Wipe Data" 버튼만 있고, "내 데이터 다운로드" 항목은 메뉴에도, 화면에도, 코루틴에도 존재하지 않는다.

spec 은 **Room 7 개 테이블 + DataStore dump 를 JSON 으로 직렬화해 SAF 경로로 ZIP 저장** 을 요구하며, **access_token / refresh_token / password hash 절대 포함 금지** invariant 를 건다 (`.spec/pipa-rights.spec.yml:87`).

본 PR 은 umbrella (`ui-settings-privacy-management.md`) 가 만든 `PipaDataExport` 라우트 placeholder 를 **실체 composable + ViewModel + 백그라운드 export worker 1 개** 로 대체한다.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/pipa-rights.spec.yml:26-34` — PIPA-002 전문
> "[내 데이터 다운로드] — 본인 Room 데이터베이스의 모든 레코드를 ZIP으로 묶어 SAF(Storage Access Framework) 경로로 저장한다. 포함: raw_ingestion_events.json, commitments.json, calendar_events.json, email_body.json, persons_enrichment.json, user_profile.json, DataStore dump(json). 비포함: access_token/refresh_token, Supabase user password hash(앱이 보유하지 않음) …"
>
> "AlertDialog '데이터를 ZIP 파일로 저장하시겠습니까?' 확인 시 백그라운드 coroutine이 Room 각 테이블을 SELECT → JSON 직렬화 → ZIP 엔트리. 완료 후 ACTION_CREATE_DOCUMENT intent로 'becalm_export_{user_id_hash}_{yyyyMMdd_HHmm}.zip' 저장. ZIP 구조: /raw_ingestion_events.json, /commitments.json, /calendar_events.json, /email_body.json, /persons_enrichment.json, /user_profile.json, /datastore.json, /README.txt(PIPA 열람권 안내 + 각 파일 스키마 요약). 저장 실패 시 Snackbar 에러. 민감 비밀번호·토큰은 절대 포함 안 함. 대용량(이메일 bodies 등) 시 progress indicator 표시"

### 2.2 `.spec/pipa-rights.spec.yml:87` — invariant
> "[내 데이터 다운로드]는 access_token·refresh_token·password hash 등 인증 비밀을 절대 포함하지 않는다"

### 2.3 `.spec/pipa-rights.spec.yml:76-83` — PIPA-007 감사 로그 연동
> "action: 'data_export' … DataStore pipa_action_log append … 이 로그는 Railway/Supabase로 업로드되지 않음(로컬 only)"

---

## 3. Code Reality (지금 무엇인가)

### 3.1 "내 데이터 다운로드" 관련 코드 0 건
```bash
grep -rn "data_export\|ACTION_CREATE_DOCUMENT\|pipa_action_log\|DataExportScreen\|DataExportWorker" \
  android/app/src/main/java/
# → 0 hits
```

### 3.2 Room 7 테이블 존재 확인 — SELECT 가능성
- `commitments`, `raw_ingestion_events`, `calendar_events`, `email_body`, `persons_enrichment` 엔티티 모두 존재 (참고: `android/app/src/main/java/com/becalm/android/data/local/db/entity/*.kt`).
- DataStore `UserPrefsStore.kt:183-185` `clearAll()` 는 존재하나 dump 가능한 JSON snapshot 을 export 하는 API 는 없음.

### 3.3 민감 정보 위치 (export 절대 제외 대상)
- `EncryptedTokenStore` (EncryptedSharedPreferences) — access/refresh token. **절대 제외**.
- Supabase user password hash → 앱이 보유하지 않음(spec 명시).
- IMAP app password → Keystore encrypted. **제외**.

### 3.4 SAF 통합 부재
`grep -rn "ACTION_CREATE_DOCUMENT\|rememberLauncherForActivityResult" android/app/src/main/java/com/becalm/android/ui/` 는 없음 (2026-04 기준 코드 범위). 따라서 본 PR 이 첫 SAF-create 통합.

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| 메뉴 행 | "[내 데이터 다운로드] — 열람권(제35조)" | umbrella PR 의 placeholder nav only | 실 composable |
| ZIP 생성 | 7 JSON + README.txt | 없음 | DataExportService 신규 |
| SAF 저장 | ACTION_CREATE_DOCUMENT, filename pattern | 없음 | Activity-result launcher |
| 진행 표시 | progress indicator | 없음 | UiState.progress |
| 감사 로그 | pipa_action_log append | 없음 | PipaActionLogStore 신규 |
| 민감 정보 제외 | token / password hash | 자연 제외 (export 구현 자체 없음) | Explicit allowlist 필요 |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/ui/navigation/BecalmNavHost.kt`**
   - umbrella PR 가 심은 `PipaDataExport` placeholder 를 `DataExportScreen(navController)` 로 교체.

2. **`android/app/src/main/java/com/becalm/android/data/local/datastore/UserPrefsStore.kt`**
   - `exportNonSecretSnapshot(): Map<String, Any?>` 추가. 민감 키는 명시적 allowlist 기반으로만 포함.
   - 반대 방향 대신 **allowlist** 설계: 포함 가능 키 (`onboarding_completed`, `theme_mode`, `locale_tag`, `notifications_enabled`, `pipa_third_party_consent`, `pipa_consent_timestamp_millis`, `terms_accepted`). **제외**: `current_user_id` (hash 로 치환), doze prompt 등 내부.

### 5.2 Files to add

1. **`android/app/src/main/java/com/becalm/android/ui/settings/pipa/DataExportScreen.kt`**
   - 역할: PIPA-002 전용 화면. 상단 설명 + [다운로드] 버튼 + progress indicator + Snackbar.
   - `ActivityResultContracts.CreateDocument("application/zip")` 를 `rememberLauncherForActivityResult` 로 호출.
   - 파일명 템플릿: `becalm_export_{user_id_hash}_{yyyyMMdd_HHmm}.zip` (`redact(userId)` 는 `core/util/Logger.kt` 의 기존 hash 유틸 재사용 가능).
   - 결과 Uri 를 ViewModel 로 전달 → 백그라운드 export 시작.

2. **`android/app/src/main/java/com/becalm/android/ui/settings/pipa/DataExportViewModel.kt`**
   - `@HiltViewModel`. `UiState(progress, error, completed)` StateFlow.
   - 의존성: `CommitmentDao`, `RawIngestionEventDao`, `CalendarEventDao`, `PersonEnrichmentDao`, 이메일 body DAO (현 경로 파일명 확인 필요), `UserPrefsStore`, `AuthRepository` (userId 조회), `PipaActionLogStore`, `@IoDispatcher`, `Logger`.
   - `export(outputUri: Uri)` 호출 → `viewModelScope.launch(ioDispatcher)` 에서 ZIP 생성.

3. **`android/app/src/main/java/com/becalm/android/data/local/export/RoomExportService.kt`**
   - 역할: DAO 전수 쿼리 → JSON 직렬화 (moshi 재사용) → `ZipOutputStream` entry 로 기록.
   - **read-only**. 트랜잭션 불필요 (단일 사용자 열람).
   - 각 테이블마다 `observeAll()` 대신 **1 회성 suspend fetch** — 긴 쿼리 결과는 chunk 단위로 stream 하여 OOM 방지 (이메일 body 가 큼).
   - README.txt 생성 — 섹션별 schema summary + "PIPA 제35조 열람권 자료입니다".

4. **`android/app/src/main/java/com/becalm/android/data/local/datastore/PipaActionLogStore.kt`**
   - 새 DataStore file `becalm_pipa_action_log.preferences_pb` (또는 `Proto` DataStore).
   - `appendAction(action: PipaActionType, details: Map<String, String>)` suspend API.
   - `observeAll(): Flow<List<PipaActionLogEntry>>` — `ActivityLogScreen` 이 구독.
   - JSON array (append-only). UserPrefs 와는 **파일 분리** (혼재 금지; PIPA 감사 전용).

5. **`android/app/src/main/res/drawable/`** — 필요 시 아이콘 추가 (재사용 가능하면 생략).

6. **`strings.xml`** — `pipa_data_export_title`, `pipa_data_export_description`, `pipa_data_export_confirm_dialog_title`, `pipa_data_export_confirm_dialog_message`, `pipa_data_export_progress`, `pipa_data_export_success`, `pipa_data_export_failure`.

7. **Tests**:
   - `RoomExportServiceTest` (Robolectric) — ZIP 에 정확히 7 JSON + README.txt 포함; 각 JSON 이 DAO 결과와 일치.
   - `RoomExportServiceSensitivityTest` — 생성된 ZIP 을 unzip 하여 `grep` 으로 `access_token`, `refresh_token`, `password_hash` 문자열이 0 hit 인지 검증.
   - `DataExportViewModelTest` — 정상 경로 + IO 실패 → error 노출 경로.

### 5.3 Files to delete (dead code)

없음.

### 5.4 Non-code changes

- `.claude.json` / build.gradle: moshi 는 이미 사용 중이므로 신규 의존성 없음 (확인 필요).
- Manifest: SAF 는 추가 permission 불필요.
- sub-PR 전용 하위 doc 이므로 `ui-map.yml` drift 없음 (umbrella 가 이미 등록).

---

## 6. Acceptance Criteria

- [ ] **Instrumentation test**: `DataExportIntegrationTest — export → ZIP 검증` — 생성된 ZIP 파일에 정확히 `{raw_ingestion_events, commitments, calendar_events, email_body, persons_enrichment, user_profile, datastore}.json + README.txt` 엔트리 존재.
- [ ] **Grep invariant (민감 정보 제외)**: 테스트에서 ZIP 을 풀어 `grep -ri "access_token\|refresh_token\|password_hash" <tempdir> | wc -l` 가 0.
- [ ] **Unit test**: `DataExportViewModelTest — IO 실패 시 error state 노출 + success state 미전환`.
- [ ] **Grep invariant (감사 로그)**: `grep -n "PipaActionType.DATA_EXPORT\|action = \"data_export\"" android/app/src/main/java/ | wc -l` ≥ 1 (Export 성공 시 DataStore 에 append 됨).
- [ ] **Grep invariant (전용 DataStore 파일)**: `PipaActionLogStore` 가 `becalm_pipa_action_log` 를 사용하며 `UserPrefs` DataStore 와 파일 공유하지 않음 — `grep -n "becalm_pipa_action_log" android/app/src/main/java/ | wc -l` ≥ 1.
- [ ] **Manual (WSL2 verify)**: Samsung 디바이스에서 `ACTION_CREATE_DOCUMENT` 플로우 정상 동작 — Documents 폴더 또는 SD 카드 경로 선택 가능.
- [ ] **진행 표시**: 이메일 body 가 100+ 건 있는 계정에서 ZIP 생성 중 progress indicator 렌더링 확인 (UI test with fake DAO).

---

## 7. Out of Scope

- Railway / Supabase 서버 측 데이터 export — spec 명시 "Room 이 완전 캐시이므로 동일" → 불필요.
- 5 개 sub route 의 나머지 4 개 기능 — 각 sub-doc 참조.
- `PipaActionLogStore.observeAll` 의 실제 뷰어 UI — `ui-settings-pipa-activity-log.md` 별도 PR.
- ZIP 암호화 — MVP 범위 외 (user 가 SAF 경로로 직접 저장하므로 디스크 암호화 의존).
- Schema JSON 정형 검증 — MVP 는 moshi 의 직렬화 기본값만 사용.

---

## 8. Dependencies

- **Blocked by**: `ui-settings-privacy-management.md` umbrella (본 PR 은 그 PR 이 심은 `PipaDataExport` route placeholder 를 교체).
- **Blocks**: 없음.
- **병렬 가능**: PIPA-003/004/005/007 sub-PR 모두 서로 파일 겹침 없음 (각자 다른 composable 파일). 단 **`PipaActionLogStore.kt`** 파일은 4 sub (Export/Consent/Pause/Delete) 가 공용이므로 **첫 번째로 머지되는 sub 가 만들고 나머지는 import** — 순서 의존.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

ZIP export 는 read-only 경로이므로 데이터 무결성 영향 없음. PipaActionLogStore 가 이미 log entry 를 append 한 경우, revert 로 로그 viewer 가 사라져도 DataStore 파일은 남아 있음 — forward-only. 디바이스에 로컬 ZIP 이 이미 저장되었다면 그 파일은 앱과 무관하므로 남아 있음 (사용자 소유).

---

## Appendix — Session handoff notes

- **Moshi vs kotlinx.serialization**: 현 코드베이스는 Moshi (`@field:Json`) 를 remote DTO 에 사용. 로컬 Room entity 는 kotlinx.datetime.Instant 를 포함하므로 custom TypeAdapter 가 필요할 수 있음. 구현 시 DAO 결과 → DTO 로 먼저 map 한 뒤 moshi serialize 가 안전.
- **user_profile.json 정의**: spec 은 "user_profile.json" 을 명시하지만 Room 에 단독 `user_profile` 테이블은 없음. DataStore 의 `current_user_id` + `AuthRepository.currentSession()?.email` (masked) 로 구성하거나, backend 의 `/v1/users/me` 응답을 로컬 캐시한다면 그 값을 사용. **구현 세션 결정 필요** — session handoff 시 CTO 확인.
- **ZipOutputStream 스트리밍**: 이메일 body 가 큰 계정은 수십 MB 가능. `ZipOutputStream(contentResolver.openOutputStream(uri)?.buffered())` 패턴으로 메모리에 전체 로드 없이 streaming write.
- **progress 계산**: 전체 row count 대비 쓰기 완료 row count 비율. 단일 Flow 로 `UiState.progress` 를 반복 갱신하되 너무 자주 emit 하면 Compose 재구성 비용. 100 row 단위 debounce 권장.
- **PipaActionLogStore 네이밍**: `becalm_pipa_action_log.preferences_pb` 로 파일 분리 — UserPrefs 와 혼재하면 `clearAll` 호출 시 감사 로그가 함께 지워져 spec invariant 와 상충 가능. 단, PIPA-005 계정 삭제 시에는 함께 삭제 되어야 함 (spec "계정 삭제 시 action_log 자체도 함께 삭제됨").
- sub-PR 중 **가장 무거운** 편 (Export 루틴 + PipaActionLogStore 까지 함께 만들어야 함). 병렬 sub 들 중 가장 먼저 머지되는 것이 본 PR 이라면 `PipaActionLogStore` 를 여기서 도입하고 다른 sub 는 사용만.
