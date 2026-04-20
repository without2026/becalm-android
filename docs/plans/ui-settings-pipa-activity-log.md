# UI / Settings / PIPA Activity Log — 활동 내역 (PIPA-007 감사 로그 열람)

**Branch**: `feat/ui/settings/pipa-rights`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 6 (Settings → PIPA 권리 실행)
**Severity**: Critical (PIPA-007 invariant — 모든 권리 실행은 감사 로그에 기록되며 진입점이 `[활동 내역]` 행으로 노출되어야 함. 로그가 append 되지 않으면 다른 sub-PR 들(PIPA-002/003/004/005) 이 "조용한 실행" 이 됨)
**Type**: Gap (`pipa_action_log` DataStore key 부재, append API 부재, `ActivityLogScreen` composable 부재, 랜딩 5행 중 1행에 해당하는 viewer 부재)

---

## 1. Finding

PIPA-007 은 모든 PIPA 권리 실행(열람·동의 철회·처리정지·계정 삭제)을 **DataStore `pipa_action_log` (JSON array, append-only)** 에 기록하고, `SettingsScreen > 개인정보 관리 > [활동 내역]` 항목에서 사용자가 timestamp 역순으로 열람할 수 있도록 요구한다.

현재 상태:

```bash
grep -rn "pipa_action_log\|PipaActionLog\|ActivityLogScreen" android/app/src/main/java/
# → 0 hits
```

즉 (a) append API 없음, (b) DataStore key 없음, (c) viewer composable 없음, (d) 랜딩(`ui-settings-privacy-management.md`) 이 가리키는 5번째 row 의 실체가 없음. 나머지 4 sub-PR(Export / Consent Withdraw / Processing Pause / Account Deletion) 이 **감사 로그 append 를 이 PR 에 의존** 한다 — 본 PR 은 그 **infrastructure + viewer** 두 역할을 겸한다.

본 doc 은 umbrella(`ui-settings-privacy-management.md`) 가 만든 `PipaActivityLog` 라우트 placeholder 를 **실체 composable + ViewModel + append 인프라** 로 대체한다.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/pipa-rights.spec.yml:76-83` — PIPA-007 전문
> "감사 로그 — 모든 PIPA 권리 실행(열람·동의 철회·처리정지·계정 삭제)은 DataStore의 pipa_action_log(JSON array, append-only)에 기록된다. 필드: {action, timestamp_iso, details}. 사용자가 본인 활동 내역을 열람할 수 있도록 SettingsScreen > '개인정보 관리' > [활동 내역] 항목으로 노출. 단 이 로그는 Railway/Supabase로 업로드되지 않음(로컬 only)"
>
> "DataStore pipa_action_log append: {action: 'data_export'|'consent_withdraw'|'processing_pause'|'processing_resume'|'account_delete_initiated', timestamp_iso: '2026-04-18T14:30:00+09:00', details: {consent_type?: 'pipa_third_party', source?: 'gmail', ...}}. [활동 내역] 탭 시 ActivityLogScreen에 timestamp 역순 표시. 계정 삭제 시 action_log 자체도 함께 삭제됨(local only 성격상 서버 보존 없음). 이 로그는 법적 증빙보다 사용자 self-check 용도 — 법적 감사 필요 시 서버 측 Supabase audit log가 primary source"

### 2.2 `.spec/pipa-rights.spec.yml:92` — invariant
> "모든 PIPA 권리 실행은 DataStore pipa_action_log에 감사 기록되며 이 로그는 서버로 업로드되지 않는다"

### 2.3 `.spec/pipa-rights.spec.yml:76-83` 의 action enum 5종
`data_export` | `consent_withdraw` | `processing_pause` | `processing_resume` | `account_delete_initiated`.

`data_export` 는 PIPA-002 로부터, `consent_withdraw` 는 PIPA-003 의 per-consent 철회마다, `processing_pause` / `processing_resume` 는 PIPA-004 의 Switch on/off, `account_delete_initiated` 는 PIPA-005 가 Railway DELETE 호출 직전에 trigger.

### 2.4 `ui-settings-privacy-management.md` 랜딩 5행
랜딩이 5번째 행 "활동 내역 — 감사 기록(제35조)" 를 이 PR 로 채우는 것을 전제.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `pipa_action_log` 전수 부재
```bash
grep -rn "pipa_action_log\|PipaActionLog\|ActivityLogScreen\|pipaActionLog" \
  android/app/src/main/java/
# → 0 hits
```

### 3.2 `UserPrefsStore.kt:196-205` — key 스키마 표에 `pipa_action_log` 가 없음
현재 key 목록:
```kotlin
// UserPrefsStoreImpl.kt:211-219
private val currentUserIdKey = stringPreferencesKey("current_user_id")
private val onboardingCompletedKey = booleanPreferencesKey("onboarding_completed")
private val themeModeKey = stringPreferencesKey("theme_mode")
private val localeTagKey = stringPreferencesKey("locale_tag")
private val dozePromptDismissedAtKey = longPreferencesKey("doze_whitelist_prompt_dismissed")
private val notificationsEnabledKey = booleanPreferencesKey("notifications_enabled")
private val pipaThirdPartyConsentKey = booleanPreferencesKey("pipa_third_party_consent")
private val pipaConsentTimestampKey = longPreferencesKey("pipa_consent_timestamp_millis")
private val termsAcceptedKey = booleanPreferencesKey("terms_accepted")
```
`pipa_action_log` 는 없음. spec 은 **JSON array** 형태를 요구 → `stringPreferencesKey("pipa_action_log")` 에 JSON 직렬화된 전체 배열을 저장하는 방식이 현 DataStore Preferences 포맷과 호환.

### 3.3 PIPA 권리 실행 sub-PR 들은 현재 append 호출을 **"미래의 API 에 의존"** 상태
- `ui-settings-pipa-data-export.md` — 완료 시 append `data_export`.
- `ui-settings-pipa-consent-withdraw.md` — 각 consent 항목 off 시 append `consent_withdraw` + `details.consent_type`.
- `ui-settings-pipa-processing-pause.md:127` — on 시 `PROCESSING_PAUSE` append, off 시 `PROCESSING_RESUME` append (해당 doc 에서 이미 `PipaActionLogStore` 를 참조하는 pseudo-code 로 작성됨).
- `ui-settings-pipa-account-deletion.md` — Railway DELETE 직전 `account_delete_initiated` append (삭제 성공 시 DataStore 자체가 clear 되므로 "직전" 시점이 유효).

### 3.4 PIPA-005 계정 삭제 시 log 자체도 함께 삭제
`ui-settings-pipa-account-deletion.md` 의 `DataStore.edit { clear() }` 가 `pipa_action_log` key 를 함께 삭제 → 추가 작업 불필요. spec 의 "local only … 서버 보존 없음" 과 일치.

### 3.5 Serialization 스택 참고
`grep -rn "kotlinx.serialization\|@Serializable" android/app/src/main/java/ | head` — 코드베이스가 kotlinx-serialization 또는 Moshi 를 이미 사용하는지 구현자 확인 필요. `ui-settings-pipa-data-export.md` 의 ZIP JSON 직렬화와 동일 스택 사용 권장 (중복 의존성 회피).

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| DataStore key | `pipa_action_log` (JSON array) | 없음 | 1 key 추가 |
| Append API | thread-safe append, enum action, timestamp_iso, details map | 없음 | `PipaActionLogStore` 신규 |
| Viewer | ActivityLogScreen (timestamp 역순 LazyColumn) | 없음 | composable + ViewModel |
| Nav route | `settings/pipa-rights/activity-log` | placeholder (umbrella 제공) | body 교체 |
| 4 sub-PR 연동 | Export / Consent / Pause / Delete 가 append 호출 | API 부재로 대기 | 본 PR 이 선행 |
| 감사 로그 invariant (no-upload) | Railway/Supabase 업로드 금지 | (자연 준수 — DataStore 는 로컬) | 문서 주석으로 명시 |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술.

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/data/local/datastore/UserPrefsStore.kt`**
   - 의도적으로 `UserPrefsStore` 에 **추가하지 말고** 별도 store 로 분리 (key scheme 과 API 성격이 다름 — append-only JSON vs scalar prefs). `UserPrefsStore` 확장은 지양.
   - 단 `clearAll()` 구현은 이미 `dataStore.edit { it.clear() }` 이므로 동일 DataStore 파일을 쓰면 자연스럽게 `pipa_action_log` key 도 함께 wipe 됨. **같은 `@UserPrefs` DataStore 를 공유** 하는 것이 PIPA-007 "계정 삭제 시 log 도 함께 삭제" 요구와 정합.

2. **`android/app/src/main/java/com/becalm/android/ui/navigation/BecalmNavHost.kt`**
   - umbrella 가 심은 `PipaActivityLog` placeholder composable 블록을 실체 `ActivityLogScreen(navController)` 로 교체.

3. **`android/app/src/main/java/com/becalm/android/ui/settings/pipa/PrivacyManagementScreen.kt`** (umbrella 에서 생성됨)
   - `[활동 내역]` 행이 이미 5번째로 배치되어 있으므로, 본 PR 에서는 navigate target 만 `PipaActivityLog.path` 로 유지. 수정 불필요 (umbrella 가 이미 세팅한다는 가정).

### 5.2 Files to add

1. **`android/app/src/main/java/com/becalm/android/data/local/datastore/PipaActionLogStore.kt`**
   - 역할: append-only JSON array store. 동일 `@UserPrefs DataStore<Preferences>` 를 공유(계정 삭제 시 `clearAll()` 에 의해 함께 지워지도록).
   - 의존성: `@UserPrefs DataStore<Preferences>`, `@IoDispatcher CoroutineDispatcher`, `Logger`.
   - API (interface + Impl 분리, 기존 `UserPrefsStore` 와 동일 패턴):
     - `observeEntries(): Flow<List<PipaActionLogEntry>>` — timestamp 내림차순 정렬 후 emit.
     - `suspend append(action: PipaAction, details: Map<String, String> = emptyMap(), at: Instant = Clock.System.now()): BecalmResult<Unit>`.
     - `suspend clear(): BecalmResult<Unit>` — 필요 시 (예: QA 시나리오). 실제 삭제권은 계정 삭제 경로가 수행.
   - Data class:
     ```kotlin
     @Serializable
     data class PipaActionLogEntry(
         val action: String,       // "data_export" | "consent_withdraw" | …
         val timestampIso: String, // ISO-8601 with +09:00 offset
         val details: Map<String, String> = emptyMap(),
     )
     ```
   - enum `PipaAction`: DATA_EXPORT / CONSENT_WITHDRAW / PROCESSING_PAUSE / PROCESSING_RESUME / ACCOUNT_DELETE_INITIATED.
     - `toWire(): String` → `"data_export"` 등 snake_case.
   - Append 동시성: `DataStore.edit { prefs -> ... }` 블록 안에서 `prefs[KEY] = currentList.plus(newEntry).toJsonString()`. DataStore 가 편집 블록 serial 하게 보장 → race 없음.
   - 상한: 5000 entries (롤링). 초과 시 oldest drop. MVP 에서 실측 append 빈도는 수동 권리 실행뿐이라 년 단위로도 수십 회 수준 — 상한은 안전판.

2. **`android/app/src/main/java/com/becalm/android/ui/settings/pipa/ActivityLogScreen.kt`**
   - 역할: PIPA-007 viewer. BecalmScaffold + back arrow + timestamp 역순 `LazyColumn`.
   - 각 row: timestamp(locale-formatted KST) + action 한글 라벨 + 접힌 details 요약.
   - 비어있을 때 `EmptyState("아직 기록된 권리 실행 내역이 없습니다")`.

3. **`android/app/src/main/java/com/becalm/android/ui/settings/pipa/ActivityLogViewModel.kt`**
   - 의존성: `PipaActionLogStore`, `Logger`.
   - `val uiState: StateFlow<ActivityLogUiState>` — `entries: List<PipaActionLogRow>`, `loading: Boolean`, `error: String?`.
   - `observeEntries()` flow 를 `map` 으로 UI row 에 투영 (action → 한글 라벨, timestamp → KST HH:mm + MM/dd).

4. **`android/app/src/main/res/values/strings.xml` / `values-ko/strings.xml`**
   - `pipa_activity_log_title` = "활동 내역"
   - `pipa_activity_log_empty` = "아직 기록된 권리 실행 내역이 없습니다"
   - action 한글 라벨 5종:
     - `pipa_action_label_data_export` = "데이터 다운로드"
     - `pipa_action_label_consent_withdraw` = "동의 철회"
     - `pipa_action_label_processing_pause` = "처리 일시중단"
     - `pipa_action_label_processing_resume` = "처리 재개"
     - `pipa_action_label_account_delete_initiated` = "계정 삭제 요청"

5. **Tests**:
   - `PipaActionLogStoreTest` — append 후 `observeEntries` 가 최신순 반환.
   - `PipaActionLogStoreTest` — `clear` 후 empty list.
   - `PipaActionLogStoreTest` — 상한(5000) 초과 시 oldest drop.
   - `PipaActionLogStoreTest` — 동일 DataStore 파일의 `UserPrefsStore.clearAll()` 호출 후 log 도 함께 삭제됨 (PIPA-005 정합).
   - `ActivityLogViewModelTest` — 5종 action 모두 한글 라벨 매핑.
   - `ActivityLogScreenTest` (Compose) — 3건 entry 주입 → timestamp 역순 표시.

### 5.3 Files to delete (dead code)

없음. umbrella 의 placeholder 도 그대로 둔다 (sub-PR merge 시 전원 실체화 완료 후 별도 정리 PR 로 해당 placeholder 파일 삭제 — umbrella appendix 참조).

### 5.4 Non-code changes

- `kotlinx-serialization-json` 이 이미 의존성에 없으면 추가 필요 — `ui-settings-pipa-data-export.md` 와 **동일** 요구. 두 sub-PR 중 먼저 merge 되는 쪽이 의존성 추가. 세션 간 조율 필요 (Appendix).
- 4 sub-PR 의 append 호출지점은 그쪽 doc 의 `Files to change` 에 이미 언급됨 — 본 PR 이 `PipaActionLogStore` API 를 제공하면 그쪽이 Hilt inject 후 호출.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant (key 선언)**: `grep -n "pipa_action_log" android/app/src/main/java/com/becalm/android/data/local/datastore/PipaActionLogStore.kt | wc -l` ≥ 1.
- [ ] **Grep invariant (서버 업로드 금지)**: `grep -rn "pipa_action_log\|PipaActionLogStore" android/app/src/main/java/com/becalm/android/data/remote/ | wc -l` 이 **0** (spec invariant `.spec/pipa-rights.spec.yml:92` — 서버로 업로드 금지).
- [ ] **Grep invariant (action enum 5종)**: `grep -n "DATA_EXPORT\|CONSENT_WITHDRAW\|PROCESSING_PAUSE\|PROCESSING_RESUME\|ACCOUNT_DELETE_INITIATED" android/app/src/main/java/com/becalm/android/data/local/datastore/PipaActionLogStore.kt | wc -l` ≥ 5.
- [ ] **Unit test**: `PipaActionLogStoreTest — append 3건 → observeEntries timestamp 내림차순`.
- [ ] **Unit test**: `PipaActionLogStoreTest — userPrefs.edit { clear() } 호출 후 log 도 비어 있음` (PIPA-005 정합).
- [ ] **Compose test**: `ActivityLogScreenTest — 5종 action 각각의 한글 라벨 렌더링`.
- [ ] **Compose test**: `ActivityLogScreenTest — 비어있을 때 EmptyState 표시`.
- [ ] **Navigation test**: `BecalmNavHostTest — PipaActivityLog 라우트가 placeholder 에서 실체 ActivityLogScreen 으로 교체됨`.

---

## 7. Out of Scope

- 4 sub-PR (Export/Consent/Pause/Delete) 의 append **호출 추가** — 각 sub-PR 에서 inject 후 호출. 본 PR 은 API 제공만.
- action 별 세부 상세 페이지 (entry 탭 시 드릴다운) — MVP 는 single-level list. post-MVP.
- 로그 내보내기 (CSV/JSON export) — PIPA-002 의 `datastore.json` 에 자연스럽게 포함되므로 별도 UI 불필요.
- Sentry 보고 — 이 로그는 **로컬 only** invariant 이므로 Sentry 경로 연동 금지 (spec `.spec/pipa-rights.spec.yml:92`).
- **서버 측 감사 로그 미러링** — Railway / Supabase audit log 는 spec 상 primary source 이지만 별도 인프라. 본 PR 은 클라이언트 로컬 경로만. 서버 측 엔드포인트 연동 금지.
- **다기기 동기화** — 동일 사용자가 여러 기기에서 권리 실행 시 각 기기의 로그는 독립. 병합/동기화 경로 없음. MVP 범위 외 (서버 측 audit 이 그 역할을 담당).
- 시간대 선택 UI — 항상 KST(+09:00) 고정 (ONTOLOGY: 1인 CTO 한국어 사용자 기준).

---

## 8. Dependencies

- **Blocked by**: `ui-settings-privacy-management.md` (umbrella) — `PipaActivityLog` 라우트 placeholder 선행.
- **Blocks**:
  - `ui-settings-pipa-data-export.md` (append `data_export`).
  - `ui-settings-pipa-consent-withdraw.md` (append `consent_withdraw` per toggle).
  - `ui-settings-pipa-processing-pause.md` (append `processing_pause` / `processing_resume`).
  - `ui-settings-pipa-account-deletion.md` (append `account_delete_initiated` — Railway DELETE 직전).
- **병렬 가능**: 이론적으로는 다른 sub-PR 과 병렬이나, 위 4 sub-PR 이 `PipaActionLogStore` 를 inject 하기 때문에 **이 PR 이 먼저 머지** 되는 편이 후속 구현 세션의 인터페이스 stub 작업을 줄여 준다. umbrella → 본 PR → 나머지 4 sub 순서 권장.
- **파일 겹침**:
  - umbrella 의 `PrivacyManagementScreen.kt` — 수정 없음 (본 PR 은 route body 만 교체).
  - `BecalmNavHost.kt` — umbrella 가 5 placeholder 심어둔 후 각 sub-PR 이 1개씩 body 교체 → linear stack 충돌 없음.
  - 다른 umbrella 밖 branch (`feat/ui/sources`, `feat/ui/error/global-banners`) 와는 파일 겹침 없음.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

Revert 시 `PipaActionLogStore` 코드가 사라지고 `ActivityLogScreen` nav target 이 다시 umbrella placeholder 로 돌아감. DataStore 의 `pipa_action_log` key 는 남지만 읽는 코드가 없으므로 무시됨. 위험:
- 본 PR revert 후 4 sub-PR 이 이미 merge 된 상태라면 그쪽 `workScheduler.enqueue*` / `rawIngestionRepository.*` 호출 옆의 `pipaActionLogStore.append(...)` 가 unresolved symbol — **compile error**. 따라서 본 PR revert 는 **모든 4 sub-PR revert 와 동시에** 가능 또는 프로덕션 배포 전에만 안전.

---

## Appendix — Session handoff notes

- **DataStore 파일 공유 결정**: `PipaActionLogStore` 를 **별도 DataStore 파일** 로 만들지, 아니면 `@UserPrefs` 파일을 공유할지 결정이 필요. 본 plan 의 권고는 **공유** — 이유:
  1. PIPA-005 계정 삭제 시 `UserPrefsStore.clearAll()` 한 번으로 log 도 wipe 됨 (spec "함께 삭제됨").
  2. 별도 파일이면 추가 clear 호출이 필요하고, 누락 시 spec 위반 (drift 위험).
  3. Key namespace 는 `pipa_action_log` 하나라 name collision 없음.
  단, append 동시성을 위해 `DataStore.edit { ... }` 블록이 serial 하게 돌아가는 것은 모든 DataStore 인스턴스가 보장하는 스펙 — 동일 파일 공유라도 안전.
- **JSON 직렬화 방식**:
  - 안 A: `stringPreferencesKey` + kotlinx-serialization JSON full-array. 단점: append 1건마다 전체 배열 재작성 (O(n) write). 상한 5000 가정 시 각 write 수 KB — 실용 범위.
  - 안 B: Proto DataStore (typed schema). 장점: 효율. 단점: proto 스키마 관리 + 기존 `@UserPrefs` 가 Preferences DataStore 이므로 분리 필요 → 위 "공유" 전제 깨짐.
  → 본 plan 권고: **안 A**. 5000 entries × ~200 bytes = 1 MB 상한 — MVP 로 충분.
- **action enum 5종은 spec 고정**: 새 action 을 나중에 추가하려면 `.spec/pipa-rights.spec.yml` 먼저 수정 후 이 store 의 enum 에 추가.
- **timestamp 형식**: spec 예시 `'2026-04-18T14:30:00+09:00'` 준수 — `kotlinx.datetime.Instant.toString()` 은 `+00:00` 형태로 나올 수 있음. KST 고정을 위해 `instant.toLocalDateTime(TimeZone.of("Asia/Seoul")).toString() + "+09:00"` 형태의 커스텀 직렬화 권장 (또는 `OffsetDateTime` equivalent).
- **PII 주의**: `details` map 은 sensitive 값 (email, source cursor 등) 을 넣지 말 것. spec 예시가 `consent_type: 'pipa_third_party'`, `source: 'gmail'` 정도의 enum 수준만 요구. 구현 세션은 append 호출지점에서 **enum/리터럴** 만 전달하도록 문서 강제.
- **계정 삭제 race**: PIPA-005 가 `account_delete_initiated` append 후 Railway DELETE 호출 중 앱이 죽으면 로그는 남지만 계정은 안 지워진 상태 — 의도적으로 유지 (재시도 유도). spec "account_delete_initiated" 라는 네이밍 자체가 "완료" 가 아닌 "개시" 를 의미.
- **법적 증빙 경계**: spec 명시 — 이 로그는 법적 감사용이 아님. 그럼에도 QA 세션에서 "법적 증빙" 으로 혼동 가능 — README 또는 KDoc 에 "self-check 용도" 문구를 남겨 drift 방지.
- **Grep invariant (서버 업로드 금지)** 가 본 PR 의 가장 중요한 invariant. 미래 어떤 세션이 log 를 Sentry 로 보내는 유혹에 빠지면 spec 위반. CI grep gate 로 강제.
