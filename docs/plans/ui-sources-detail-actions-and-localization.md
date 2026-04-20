# UI / Sources / detail-actions-and-localization — SourceDetailScreen 액션 3종(재연결/연결해제/지금동기화) 실제 연결 + SourcesList 로컬라이즈된 표시명/오류 taxonomy + 401 token refresh

**Branch**: `feat/ui/sources`
**Status**: PLAN ONLY — 구현은 별도 세션. 이 plan doc 이외의 코드 커밋 금지.
**E2E Stage**: 5 — Settings → Sources (list + detail + per-source action)
**Severity**: **Critical** (SMG-002..005 의 사용자 경로 **전부** placeholder. [연결 해제] 는 log-only no-op, [다시 연결] 는 빈 람다, [지금 동기화] 버튼 자체 부재. 사용자가 토큰 만료 상태에서 Gmail 을 복구할 경로가 **앱 어디에도 없다**.)
**Type**: Gap (액션 wiring 미구현) + Drift (로컬라이즈된 표시명 / 오류 taxonomy 누락) + Dead-code (`disconnectSource` VM 메서드가 log 만 남김)

---

## 1. Finding

Settings → Sources 플로우의 **3 계층 UI surface** 가 spec SMG-001..005 와 각각 drift/gap:

1. **SourcesListScreen** (`ui/sources/SourcesListScreen.kt:129`) — row title 을 `row.sourceType.replaceFirstChar { it.uppercase() }` 로 raw 표시 → `"Gmail" / "Outlook_mail" / "Naver_imap" / "Daum_imap" / "Google_calendar" / "Outlook_calendar"` 노출. SMG-001 은 `"Gmail" / "Outlook Mail" / "네이버 메일" / "다음 메일" / "Google Calendar" / "Outlook Calendar"` 한국어 표시 요구. 또한 `row.lastError` 는 raw string (예: `"HTTP 401"`) 그대로 노출 — SMG-001 `"오류 — 토큰이 만료되었습니다"` 와 불일치.

2. **SourceDetailScreen** (`ui/sources/SourceDetailScreen.kt:142-170`) — [다시 연결] / [연결 해제] 버튼이 존재하나 `onClick = { /* TODO(SMG-004): wire reconnect action */ }` 와 `onClick = { /* TODO(SMG-004): no API exists yet */ }` 의 **빈 람다**. `[지금 동기화]` 버튼은 **아예 없음**. 상태 카드에 `last_sync_at`, `events_synced_count`, `last_error` 필드 모두 미표시 (`SourceDetailUiState` 에 필드 자체가 없음, `SourceDetailViewModel.kt:54-59`).

3. **SourceDetailViewModel** (`ui/sources/SourceDetailViewModel.kt`) 에는 `reconnect`/`disconnect`/`syncNow` 메서드 **하나도 없음**. `SourcesListViewModel.disconnectSource` (`ui/sources/SourcesListViewModel.kt:118-123`) 는 `logger.w(TAG, "disconnectSource called for $sourceType but no API method exists yet (SMG-004)")` — log-only no-op. 결과적으로 disconnect 는 Keystore 토큰도 cursor 도 건드리지 않아 SMG-004 가 전혀 이행되지 않음.

4. **401 token refresh** — `TokenRefreshInterceptor` / `AuthStateObserver` 류 코드 0 matches. 따라서 OAuth 토큰이 만료되면 사용자는 `ERROR` 로 보이는 row 에 들어와도 복구 flow 가 없다 (본 plan 의 reconnect UI + 별도 PR #3/#4 의 provider 가 결합돼야 동작).

본 plan 은 **UI wiring + SourceStatusRepository 의 `disconnect` / `initializeDefaults` 추가 + 로컬라이즈 mapper + 401 `TokenRefreshInterceptor`** 를 한 덩어리로 다룬다. OAuth provider 자체 (토큰 교환·revoke) 는 PR #3/#4 (repo-auth) 와 PR #6 (repo-imap) 의 산출물을 소비한다.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/source-management.spec.yml:10-18` — SMG-001
> "Settings → Sources 목록 화면에서 6개 소스의 현재 상태(connected / disconnected / error + 이유)와 '연락처' 의사 소스 행을 표시한다. 총 7개 행 … **Gmail: '연결됨'. Outlook: '오류 — 토큰이 만료되었습니다'** … voice: '연결됨'. imap: '미연결'. google_calendar: '연결됨'. outlook_calendar: '미연결'."

### 2.2 `.spec/source-management.spec.yml:20-28` — SMG-002
> "연결된 소스 탭 시 소스 상세 화면 표시 — `last_sync_at`, `events_synced_count`, `last_error`(있는 경우), [연결 해제] 버튼 … **last_sync_at: 'HH:mm' 형식. events_synced_count: N건. last_error: 없음(오류 없을 시 미표시). [연결 해제] 버튼 표시됨. [지금 동기화] 버튼 표시됨**"

### 2.3 `.spec/source-management.spec.yml:30-38` — SMG-003
> "미연결 또는 오류 상태 소스 탭 시 해당 소스의 재연결 플로우로 이동한다 — OAuth 소스는 OAuth 재인증, IMAP 소스는 앱 비밀번호 재입력 … **[다시 연결] 탭 시 Microsoft OAuth 재인증 플로우 실행됨. 재인증 성공 시 DataStore Outlook 토큰 업데이트됨. SourceDetailScreen 상태가 connected로 갱신됨**"

### 2.4 `.spec/source-management.spec.yml:40-48` — SMG-004
> "소스 연결 해제 시 해당 소스의 DataStore cursor 초기화, Keystore credentials 삭제, 기존 Room 데이터는 유지, 칩 상태가 idle로 변경된다 … **DataStore gmail last_cursor 초기화됨(null). Keystore Gmail OAuth 토큰 삭제됨. Room의 기존 gmail raw_ingestion_events + EmailBody 유지됨(삭제 없음). SourceStatusStrip Gmail 칩이 idle(회색)으로 변경됨**"

### 2.5 `.spec/source-management.spec.yml:50-58` — SMG-005
> "소스 상세 화면의 [지금 동기화] 버튼이 해당 소스 어댑터만 선택적으로 실행한다 — **WorkManager enqueueUniqueWork('sync-<source>', REPLACE)**. 이것이 앱에서 유일한 소스별 수동 트리거다 … SourceStatusStrip Gmail 칩 syncing 상태로 전환 → 완료 후 synced로 전환됨"

### 2.6 `.spec/source-management.spec.yml:60-63` — invariants
> "소스 연결 해제 시 기존 Room 데이터(raw_ingestion_events, EmailBody, Transcript)는 삭제하지 않는다"
> "소스 연결 해제 시 해당 소스의 Keystore credentials와 DataStore cursor만 삭제된다"
> "[지금 동기화]는 소스 상세 화면에서만 접근 가능하다 — 대시보드에서 소스별 개별 동기화 불가"

### 2.7 `.spec/contracts/ui-map.yml:143-155`
> "- path: /settings/sources / screen: SourcesListScreen / components: [SourceStatusRow] … SMG-001: 6-source list + '연락처' pseudo-source row (ENR-008)."
> "- path: /settings/sources/{source_id} / screen: SourceDetailScreen / components: [SourceStatusDetail, LastSyncInfo, **ReconnectButton**, **DisconnectButton**, **ManualSyncButton**] … SMG-002..005: source detail, reconnect flow, disconnect, manual per-source sync."

### 2.8 `.spec/contracts/ui-map.yml:171-174` — SourceStatusStrip
> "6개 소스(**Gmail, Outlook Mail, 네이버, 다음, Google Calendar, Outlook Calendar**) 칩 배열. 각 칩: idle(회색) / syncing(스피너) / error(빨간 점) / synced(초록 체크+HH:mm)."

### 2.9 `.spec/onboarding.spec.yml:106` — invariant
> "온보딩은 순차 진행이며 **건너뛴 OAuth 소스는 설정 화면에서 재연결 가능**하다"

---

## 3. Code Reality (지금 무엇인가)

### 3.1 SourcesListScreen — 로컬라이즈되지 않은 raw title + raw 에러 문자열

`android/app/src/main/java/com/becalm/android/ui/sources/SourcesListScreen.kt:127-140`:
```kotlin
Column(modifier = Modifier.weight(1f)) {
    Text(
        text = row.sourceType.replaceFirstChar { it.uppercase() },   // ← raw "gmail" → "Gmail", "outlook_mail" → "Outlook_mail"
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (row.lastError != null) {
        Text(
            text = row.lastError,                                    // ← 서버/OkHttp raw message 직노출
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
```

### 3.2 SourceDetailScreen — 세 버튼 모두 빈 람다, [지금 동기화] 부재, 메타데이터 4종 미표시

`android/app/src/main/java/com/becalm/android/ui/sources/SourceDetailScreen.kt:155-169`:
```kotlin
// Reconnect / Disconnect — disconnect is a no-op (SMG-004 pending)
BecalmButton(
    text = stringResource(R.string.action_reconnect),
    onClick = { /* TODO(SMG-004): wire reconnect action */ },
    variant = BecalmButtonVariant.Secondary,
    modifier = Modifier.fillMaxWidth(),
)
Spacer(modifier = Modifier.height(8.dp))
BecalmButton(
    text = stringResource(R.string.action_disconnect),
    onClick = { /* TODO(SMG-004): no API exists yet */ },
    variant = BecalmButtonVariant.Text,
    modifier = Modifier.fillMaxWidth(),
)
```
→ [지금 동기화] 버튼 자체가 이 Column 에 **존재하지 않음**. 상태 Row 하나 + 두 버튼만.

`SourceDetailUiState` (`ui/sources/SourceDetailViewModel.kt:54-59`):
```kotlin
public data class SourceDetailUiState(
    val sourceType: String = "",
    val status: String = "",
    val recentEvents: List<RecentEventSummary> = emptyList(),
    val error: String? = null,
)
```
→ `lastSyncAt`, `eventsSyncedCount`, `lastError` 필드 없음. `SourceStatusRepository` 는 `SourceStatus.lastSyncedAt` / `errorMessage` 를 이미 emit 하지만 (`SourceStatusRepository.kt:49-54`) VM 이 이를 drop.

### 3.3 SourceDetailViewModel — reconnect/disconnect/syncNow 부재

`SourceDetailViewModel` 에는 `state: StateFlow` 와 `setUserId` 만 있음. 3 개 액션 메서드 전무. `SourcesListViewModel.disconnectSource(sourceType)` 는 `logger.w(...)` log-only no-op (`SourcesListViewModel.kt:118-123`).

### 3.4 401/토큰 만료 복구 경로 없음

```bash
grep -rn "TokenRefreshInterceptor\|OAuthTokenStore\|AuthStateObserver\|onTokenExpired\|refreshToken" \
  android/app/src/main/java/com/becalm/android/
# → 0 matches
```

### 3.5 SourceStatusRepository — `disconnect` / `initializeDefaults` 미구현

`SourceStatusRepository.kt:75-...` interface 는 `observeAll`, `observeFor`, `refreshFromServer` 정도만 노출. `disconnect(sourceType)` 없음. 첫 실행 시 6 개 canonical row 를 seed 하는 로직도 없음 → SourceStatusStrip 6 chip preview (`ui/components/SourceStatusStrip.kt:212-217`) 는 6 개를 보여주지만, 런타임에는 naver_imap/daum_imap row 가 온보딩에서 생성되지 않으면 존재하지 않음 (UI-EMAIL-013 과 연결).

### 3.6 WorkScheduler API 기존 확인

```bash
grep -rn "enqueueSourceSync\|WorkScheduler\|UniqueWorkKeys" android/app/src/main/java/com/becalm/android/ | head
```
→ `WorkSchedulerImpl` + `UniqueWorkKeys` 는 이미 존재 (voice / email / calendar 6-source all supported 가정). 본 plan 은 이 API 를 **소비만**.

검증 grep:
```bash
grep -rn "TODO(SMG-004)" android/app/src/main/java/com/becalm/android/ | wc -l
# → ≥ 2 (SourceDetailScreen 두 군데)

grep -rn "action_sync_now\|R\.string\.action_sync_now" android/app/src/main/
# → 0 (신규 string key 필요)

grep -rn "sources_error_token_expired\|sources_error_network" android/app/src/main/
# → 0 (신규 string key 필요)
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| Row title 로컬라이즈 | `"Gmail" / "Outlook Mail" / "네이버 메일" / "다음 메일" / "Google Calendar" / "Outlook Calendar"` (SMG-001) | `sourceType.replaceFirstChar` (raw enum) | `sourceDisplayName(sourceType): StringRes` mapper 신설 |
| Row 에러 표시 | `"오류 — 토큰이 만료되었습니다"` (taxonomy 기반) | raw `lastError` 직노출 | 에러 코드 → localized string mapper 신설 |
| [다시 연결] 버튼 | OAuth 재인증 flow launch (SMG-003) | empty lambda | VM.reconnect() → Gmail launcher / MSAL launcher / IMAP form navigate |
| [연결 해제] 버튼 | Keystore wipe + cursor reset + status=idle + confirm dialog (SMG-004) | log-only VM + empty lambda | AlertDialog + VM.disconnect() + Repository.disconnect() |
| [지금 동기화] 버튼 | `WorkManager.enqueueUniqueWork('sync-<source>', REPLACE)` (SMG-005) | 버튼 부재 | UI + VM.syncNow() + WorkSchedulerImpl.enqueueSourceSync 호출 |
| `last_sync_at` 표시 | `HH:mm` 형식 (SMG-002) | 미표시 | UiState 확장 + KST HH:mm formatter |
| `events_synced_count` | N건 (SMG-002) | 미표시 | UiState 확장 + DAO count 쿼리 (API gap — 본 plan 범위 내 간단 DAO 쿼리 추가) |
| `last_error` | 있을 때만 표시 (SMG-002) | 미표시 | UiState 확장 + mapper 재사용 |
| 6 canonical row seeding | SourceStatusStrip 6 chip (ui-map.yml:171-174) | 런타임 seed 없음 | `SourceStatusRepository.initializeDefaults()` + `UserPrefsStore.isFirstLaunch` 게이트 |
| 401 refresh | 토큰 만료 → 자동 refresh → 실패 시 error 상태 (SMG-003 의 재인증 진입점 전제) | interceptor 부재 | `TokenRefreshInterceptor` OkHttp 레벨 신설 |
| Disconnect 확인 | SMG-004 시 `[확인]` 다이얼로그 | 다이얼로그 없음 | `AlertDialog` Composable 추가 |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술.

### 5.1 Files to change

- **`android/app/src/main/java/com/becalm/android/ui/sources/SourcesListScreen.kt`**
  - `row.sourceType.replaceFirstChar { it.uppercase() }` 제거. 대신 `stringResource(sourceDisplayName(row.sourceType))` (5.2 신규 함수).
  - `row.lastError` 직노출 제거. 대신 `row.lastError?.let { stringResource(errorCodeToLocalizedString(row.errorCode)) }` — errorCode 는 `SourceStatusRow` 에 새 필드로 추가하고 Repository merger 가 raw message → `SourceErrorCode` enum (token_expired / network / parse / quota / unknown) 으로 매핑.
  - Preview 도 `Naver_imap / Daum_imap` 라벨이 한글로 렌더되도록 fixture 갱신.

- **`android/app/src/main/java/com/becalm/android/ui/sources/SourceDetailScreen.kt`**
  - 상태 카드 Row 아래에 `LastSyncInfo` 영역 추가 — `state.lastSyncAt?.let { formatHHmmKst(it) }`, `state.eventsSyncedCount` ("N건"), `state.lastError?.let { stringResource(...) }` (없으면 미표시).
  - 버튼 3 개를 이 순서로: `[지금 동기화]` (primary) → `[다시 연결]` (secondary) → `[연결 해제]` (text). 각각 `onClick = { viewModel.syncNow() / reconnect() / onDisconnectClicked() }`.
  - Disconnect confirmation: `var showDisconnectConfirm by remember { mutableStateOf(false) }`; `onDisconnectClicked = { showDisconnectConfirm = true }`. `if (showDisconnectConfirm) AlertDialog(...)` — `[확인]` → `viewModel.disconnect()` + dismiss, `[취소]` → dismiss. title/body/label 은 모두 string resource.
  - navigationIcon 위 타이틀도 `stringResource(sourceDisplayName(state.sourceType))` 로 교체 (`SourceDetailScreen.kt:66` 의 `state.sourceType.replaceFirstChar { it.uppercase() }` 제거).

- **`android/app/src/main/java/com/becalm/android/ui/sources/SourceDetailViewModel.kt`**
  - `SourceDetailUiState` 에 필드 추가: `lastSyncAt: Instant?`, `eventsSyncedCount: Int`, `lastErrorCode: SourceErrorCode?`.
  - `combine(statusFlow, eventsFlow, countFlow) { ... }` 로 count 조인. `countFlow` 는 `rawIngestionRepository.observeCountForSourceType(userId, sourceType)` — **간단 DAO 추가**가 필요하나 대체로 기존 `observeTimelineForUser` 결과를 in-memory `count` 로 산출 가능 (large dataset 시 DAO 추가는 별도 plan).
  - 신규 `public fun syncNow()` — `viewModelScope.launch { workScheduler.enqueueSourceSync(sourceType) }`.
  - 신규 `public fun reconnect()` — 반환 타입 `ReconnectIntent` (Composable 이 관찰해 네비게이션 실행). `when (sourceType) { GMAIL -> ReconnectIntent.LaunchGmailOAuth; OUTLOOK_MAIL -> ReconnectIntent.LaunchOutlookOAuth; NAVER_IMAP, DAUM_IMAP -> ReconnectIntent.NavigateImapForm(sourceType); 등 }`. 화면은 `reconnectIntent` one-shot flow 로 수신.
  - 신규 `public fun disconnect()` — `viewModelScope.launch { sourceStatusRepository.disconnect(sourceType) }`. Repository 가 Keystore wipe + cursor reset + status=NEVER_CONNECTED 전환 수행.
  - Constructor inject: `WorkScheduler`, `OAuthCredentialStore` (PR #3/#4 산출물), `ImapCredentialStore` (PR #6), `AuthRepository` (userId).

- **`android/app/src/main/java/com/becalm/android/ui/sources/SourcesListViewModel.kt`**
  - `disconnectSource` log-only 메서드 **제거** (dead code). 모든 disconnect 경로는 SourceDetail 경유로 일원화 (spec invariant "[지금 동기화] 는 소스 상세 화면에서만 접근 가능" 과 정합).
  - `SourceStatusRow` 에 `errorCode: SourceErrorCode?` 필드 추가. Map step 에서 `SourceStatus.errorMessage` → `SourceErrorCode` 매핑 (5.1 의 merger 에서 선 매핑).

- **`android/app/src/main/java/com/becalm/android/data/repository/SourceStatusRepository.kt`** + `impl`
  - interface 에 신규 메서드 3 개:
    - `suspend fun disconnect(sourceType: String): BecalmResult<Unit>` — `OAuthCredentialStore.clear(sourceType)` (GMAIL/OUTLOOK_MAIL) 또는 `ImapCredentialStore.clear(sourceType)` (NAVER_IMAP/DAUM_IMAP) 호출 → `SyncCursorStore.reset(sourceType)` → DataStore status 를 `NEVER_CONNECTED` 로 직접 set → `_errorMessage` null 로.
    - `suspend fun initializeDefaults()` — `userPrefsStore.isFirstLaunch.first()` true 일 때만 `SourceType.ALL` 6 개 row 를 `NEVER_CONNECTED` 상태로 seed. idempotent (이미 존재하는 row 는 skip).
  - impl 의 merger (`data/repository/internal/SourceStatusMerger.kt`) 에서 raw error message → `SourceErrorCode` 추론 추가 (when-절: `"401" in msg || "token" in msg.lowercase() -> TOKEN_EXPIRED`, `"UnknownHostException"|"SocketTimeout" -> NETWORK`, `"parse"|"json" -> PARSE`, `"quota"|"rate_limit" -> QUOTA`, else UNKNOWN).
  - Hilt 바인딩은 기존 `RepositoryModule.kt` 에 `OAuthCredentialStore`/`ImapCredentialStore` 주입 추가만.

- **`android/app/src/main/res/values/strings.xml`** + **`values-ko/strings.xml`**
  - 소스 표시명: `source_display_gmail`, `source_display_outlook_mail`, `source_display_naver_imap`, `source_display_daum_imap`, `source_display_google_calendar`, `source_display_outlook_calendar`, `source_display_contacts`, `source_display_voice`.
  - 에러 taxonomy: `sources_error_token_expired = "오류 — 토큰이 만료되었습니다"`, `sources_error_network = "오류 — 네트워크 연결을 확인해주세요"`, `sources_error_parse = "오류 — 서버 응답을 해석할 수 없습니다"`, `sources_error_quota = "오류 — API 할당량 초과"`, `sources_error_unknown = "오류 — 잠시 후 다시 시도"`.
  - 액션: `action_sync_now = "지금 동기화"`. (`action_reconnect` / `action_disconnect` 는 이미 존재한다고 가정 — 없으면 본 PR 에서 추가.)
  - Dialog: `disconnect_confirm_title = "연결을 해제할까요?"`, `disconnect_confirm_body = "기존 동기화된 이메일은 앱에 남습니다. 저장된 인증 정보만 삭제됩니다."`, `disconnect_confirm_ok = "확인"`, `disconnect_confirm_cancel = "취소"`.
  - Metadata: `source_detail_last_sync_label = "마지막 동기화 %1$s"`, `source_detail_events_synced = "수집 %1$d건"`.

### 5.2 Files to add

- **`android/app/src/main/java/com/becalm/android/ui/sources/SourceDisplayNames.kt`** — `fun sourceDisplayName(sourceType: String): Int` (StringRes). when-절로 `SourceType.GMAIL → R.string.source_display_gmail` 등 매핑. unknown 은 `R.string.source_display_unknown` fallback.

- **`android/app/src/main/java/com/becalm/android/ui/sources/SourceErrorCode.kt`** — sealed/enum `SourceErrorCode { TOKEN_EXPIRED, NETWORK, PARSE, QUOTA, UNKNOWN }` + extension `fun SourceErrorCode.toStringRes(): Int`.

- **`android/app/src/main/java/com/becalm/android/ui/sources/ReconnectIntent.kt`** — sealed `ReconnectIntent` : `LaunchGmailOAuth`, `LaunchOutlookOAuth`, `NavigateImapForm(sourceType: String)`, `LaunchGoogleCalendarOAuth`, `LaunchOutlookCalendarOAuth`. Composable 이 `LaunchedEffect(intent)` 로 네비게이션.

- **`android/app/src/main/java/com/becalm/android/data/remote/interceptor/TokenRefreshInterceptor.kt`** — `OkHttp Interceptor` 구현. 요청이 401 로 실패하면 `OAuthCredentialStore.refresh(sourceType)` 시도 (sourceType 은 요청 헤더 태그로 제공). refresh 성공 시 새 토큰으로 1 회 재시도. 실패 시 `SourceStatusRepository.markError(sourceType, SourceErrorCode.TOKEN_EXPIRED)` 호출 후 원 응답 반환. (이 interceptor 는 UI 에서 직접 호출되지 않고 `NetworkModule` 에 Hilt 주입됨 — 본 plan 에서 모듈 수정 최소.)

- **Tests**:
  - `android/app/src/test/java/com/becalm/android/ui/sources/SourceDetailViewModelTest.kt`:
    - `syncNow_delegatesToWorkScheduler`
    - `disconnect_delegatesToRepository_andResetsStatus`
    - `reconnect_emitsCorrectIntent_perSourceType` (parameterized: 6 source types)
    - `state_includesLastSyncAt_eventsSyncedCount_lastError`
  - `android/app/src/test/java/com/becalm/android/ui/sources/SourceDisplayNamesTest.kt` — 6 + contacts + voice 매핑 검증.
  - `android/app/src/test/java/com/becalm/android/data/repository/internal/SourceStatusMergerTokenExpiredTest.kt` — raw message → errorCode 매핑 when-절 검증 (5 case).
  - `android/app/src/androidTest/java/com/becalm/android/ui/sources/SourcesListScreenSnapshotTest.kt` (Roborazzi) — 로컬라이즈된 7-row list snapshot (ko locale).

### 5.3 Files to delete (dead code)

- **`SourcesListViewModel.disconnectSource`** — 메서드만 제거. 클래스 자체는 유지.
- 그 외 dead code는 없음. `TODO(SMG-004)` 주석 2 개는 본 PR 의 실제 wiring 으로 자연 소멸.

### 5.4 Non-code changes

- DB 마이그레이션: 없음 (DataStore 키만 조작).
- Config/manifest: `NetworkModule` 에 `TokenRefreshInterceptor` 추가 시 `OkHttpClient.addInterceptor` 1 라인 — 본 PR 포함 OK.
- Permission: 변경 없음.

---

## 6. Acceptance Criteria

다른 세션이 구현 완료 여부를 **기계적으로 검증**할 수 있는 항목.

- [ ] **Grep invariant**: `grep -rn "TODO(SMG-004)" android/app/src/main/java/com/becalm/android/ui/sources/ | wc -l` = 0
- [ ] **Grep invariant**: `grep -rn "sourceType.replaceFirstChar" android/app/src/main/java/com/becalm/android/ui/sources/ | wc -l` = 0 (모두 `sourceDisplayName` 로 교체)
- [ ] **Grep invariant**: `grep -rn "no API method exists yet" android/app/src/main/java/com/becalm/android/ | wc -l` = 0 (`SourcesListViewModel.disconnectSource` dead code 제거됨)
- [ ] **Grep invariant**: `grep -rn "R\.string\.action_sync_now\|R\.string\.sources_error_token_expired\|R\.string\.disconnect_confirm_title" android/app/src/main/java/com/becalm/android/ui/sources/ | wc -l` ≥ 3
- [ ] **Unit test**: `SourceDetailViewModelTest.syncNow_delegatesToWorkScheduler` 통과 — `WorkScheduler.enqueueSourceSync(sourceType)` 가 정확히 1 회 호출됨
- [ ] **Unit test**: `SourceDetailViewModelTest.disconnect_delegatesToRepository_andResetsStatus` 통과 — Fake `SourceStatusRepository.disconnect(sourceType)` 호출 확인 + Fake `OAuthCredentialStore.clear` 호출
- [ ] **Unit test**: `SourceDetailViewModelTest.reconnect_emitsCorrectIntent_perSourceType` 통과 — 6 sourceType 모두 올바른 `ReconnectIntent` sealed variant emit
- [ ] **Unit test**: `SourceDetailViewModelTest.state_includesLastSyncAt_eventsSyncedCount_lastError` 통과 — UiState 3 신규 필드가 Repository emit 값과 정확히 일치
- [ ] **Unit test**: `SourceStatusMergerTokenExpiredTest` — 5 case 매핑 (401 / UnknownHost / parse / quota / unknown) 모두 올바른 `SourceErrorCode` 반환
- [ ] **Unit test**: `SourceDisplayNamesTest` — 8 sourceType 매핑 + unknown fallback
- [ ] **Snapshot test**: `SourcesListScreenSnapshotTest` (Roborazzi, ko locale) — 7 row title 에 `"네이버 메일"` / `"다음 메일"` / `"Outlook Mail"` 등 한국어 문자열 포함 (이미지 diff baseline 통과)
- [ ] **Compile gate**: `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:verifyRoborazziDebug` 성공
- [ ] **Manual**: 토큰 만료된 Outlook 소스가 Row 에 `"오류 — 토큰이 만료되었습니다"` 로 표기. Detail 진입 후 `[다시 연결]` 탭 → MSAL 재인증 flow launch (PR #4 산출물 연결).
- [ ] **Manual**: Gmail Detail 에서 `[연결 해제]` 탭 → AlertDialog 표시 → `[확인]` 탭 후 Row status 가 `"미연결"` 로 즉시 전환, Keystore 토큰 삭제 확인 (logcat + `OAuthCredentialStore.hasToken(GMAIL)` = false).

---

## 7. Out of Scope

이 PR 에서 **건드리지 말 것**. 의도치 않은 scope creep 방지.

- **OAuth provider 실제 구현** (`GoogleAuthTokenProviderImpl`, `MsGraphTokenProviderImpl`) — PR #3 / #4. 본 plan 은 이 provider 들의 `refresh(sourceType)` / `clear(sourceType)` suspending API 를 **소비만**.
- **IMAP credential wipe 세부 로직** — PR #6 `repo-imap-per-provider-credentials.md` 가 `ImapCredentialStore.clear(sourceType)` 제공. 본 plan 은 이를 호출만.
- **WorkScheduler / UniqueWorkKeys 구조 변경** — 이미 6-source 전부 지원한다고 가정. 만약 특정 source key 가 누락됐다면 별도 plan 에서 추가.
- **PIPA per-provider consent 화면** — 별도 plan `ui-onboarding-pipa-email-consent.md`. 본 plan 의 [다시 연결] 경로에서 PIPA 재확인이 필요하다면 해당 PR 이 navigation 삽입.
- **`SourceStatusStrip` 칩 렌더링 변경** — 이미 preview 가 6 chip 이고 runtime 은 `initializeDefaults` 만 추가되면 자동으로 6 chip 표시. 본 plan 은 Strip composable 자체는 수정하지 않음.
- **RawEvent 상세 이메일 렌더링** — 별도 plan `ui-raw-event-email-rendering.md`. Detail 의 `recentEvents` 리스트는 기존 rendering 그대로 유지.
- **DB 마이그레이션** — 본 PR 은 Room schema 변경 없음. `events_synced_count` 는 기존 DAO 의 in-memory count 로 산출.
- **EmailBody retention / cleanup** — email-pipeline.spec.yml 4-2 는 `RetentionSweepWorker` 책임. disconnect 시 invariant "기존 데이터 유지" 준수만 확인 (본 plan 은 데이터 **삭제 안 함**).

---

## 8. Dependencies

- **Blocked by**:
  - PR #3 (`feat/repo/auth` — `repo-auth-gmail-oauth-provider.md`) — `OAuthCredentialStore.clear(sourceType)` / `refresh(sourceType)` API 필요.
  - PR #4 (`feat/repo/auth` — `repo-auth-msgraph-oauth-provider.md`) — MSAL token revoke/refresh 동일 인터페이스.
  - PR #6 (`feat/repo/imap` — `repo-imap-per-provider-credentials.md`) — `ImapCredentialStore.clear(sourceType)` per-provider API.
  - PR #12 (`ui-onboarding-gmail-oauth.md`) — `GmailOAuthLauncher` 재사용 (reconnect 경로에서 동일 launcher 소환).
  - PR #13 (`ui-onboarding-outlook-oauth.md`) — `MsalOutlookLauncher` 재사용.
- **Blocks**:
  - 없음 (UI 말단 leaf). 후속 PIPA consent plan 이 reconnect 경로에 PIPA 재확인을 삽입할 수 있으나 본 plan 의 `ReconnectIntent` 를 wrapping 만 하므로 충돌 없음.

merge 순서: **#3 → #4 → #6 → #12 → #13 → #14 (IMAP) → #15 (PIPA) → 본 plan (`feat/ui/sources`)**. 본 plan 은 별도 브랜치이므로 `feat/ui/onboarding` 스택과 병렬 가능하나, **먼저 머지될 경우 compile 실패** (없는 API 호출). 따라서 CI 는 #3/#4/#6 완료 후 본 plan 을 체크아웃·빌드.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

revert 후:
1. SourceDetailScreen 이 다시 placeholder 버튼으로 회귀. 사용자 데이터는 무손상 — `SourceStatusRepository.disconnect` 가 호출되기 전 상태이므로 Keystore/cursor 는 revert 이전과 동일 (revert 는 쓰기 경로만 되돌림).
2. `initializeDefaults` 로 seed 된 `NEVER_CONNECTED` row 6 개는 DataStore 에 남지만 benign — legacy 화면도 이를 읽어 표시할 뿐. 추가 cleanup 불필요.
3. `TokenRefreshInterceptor` 제거로 인해 401 응답은 raw 로 propagate. 기존 (= interceptor 도입 전) 동작과 동일하므로 regression 없음.

revert 체크리스트:
- [ ] `./gradlew :app:assembleDebug` 성공
- [ ] `SourcesListScreen` preview 렌더 성공 (로컬라이즈 StringRes 가 남으면 경고 없음)
- [ ] DataStore 파일에 `initializeDefaults` seed 된 row 가 남아있어도 crash 없음 (`observeAll` 은 이미 dead row 를 tolerate)

schema 변경 없으므로 데이터 복구 전략 불필요.

---

## Appendix — Session handoff notes

구현 세션에게 전달할 추가 컨텍스트.

- **왜 `reconnect()` 가 VM 이 직접 네비게이션하지 않고 `ReconnectIntent` one-shot flow 를 emit 하는가**: Hilt/Compose 경계에서 ViewModel 은 `NavController` 의존성을 갖지 않는 것이 컨벤션. intent pattern 으로 Composable 측에서 `LaunchedEffect(intent)` 로 `navController.navigate(...)` 수행. 테스트 시에도 intent 만 검증하면 충분.
- **`initializeDefaults()` 호출 시점**: `BecalmApplication.onCreate` 가 아니라 첫 OnboardingViewModel init 에서 호출하는 것이 안전 — `BecalmApplication` 은 hilt 준비 전에 실행되므로 inject 불가. `OnboardingViewModel.init` 에서 `userPrefsStore.isFirstLaunch.first() == true && repo.observeAll().first().isEmpty()` 두 조건 충족 시에만 seed.
- **`events_synced_count` 의 데이터 소스**: 현재 DAO 에 `observeCountForSourceType` 가 없다. 본 plan 은 in-memory count (기존 `observeTimelineForUser` 결과의 `.filter { sourceType }.size`) 로 MVP 처리. 대량 데이터에서 성능 문제가 실측되면 후속 plan 에서 `@Query("SELECT COUNT(*) FROM raw_ingestion_events WHERE source_type = :st AND user_id = :uid")` DAO 추가.
- **`SourceErrorCode` 매핑은 merger 에서 수행**: 이유는 (a) UI 레이어는 enum 만 받아야 presentation 결합도 낮음, (b) 여러 소비처 (Strip, List, Detail) 가 중복 매핑 안 함, (c) 테스트에서 raw message fixture 만 주면 검증 용이.
- **AlertDialog 는 `AlertDialog(...)` Material3 사용**: 프로젝트에 이미 `BecalmAlertDialog` wrapper 가 있다면 그걸 재사용 (`ui/components/` 확인). 없으면 M3 raw 사용.
- **로컬 locale 만 ko**: `values/` 에도 한국어 입력 (앱 spec "Korean only"). `values-ko/` 는 형식상 동일 파일. 추후 en 추가 시 `values/` 를 en 으로 교체 + `values-ko/` 신설. 본 plan 은 ko 만.
- **TokenRefreshInterceptor 가 `SourceStatusRepository.markError` 를 호출하는 순환 의존 위험**: Hilt graph 에서 `SourceStatusRepository` ← `NetworkModule` ← `TokenRefreshInterceptor`. `dagger.Lazy<SourceStatusRepository>` 로 inject 해 순환 회피. (이 디테일은 구현 세션에서 실제 DI graph 확인 후 대응.)
- **전체 규모 추정**: ~10 파일 변경, ~5 파일 신규, ~6 테스트 추가. 2 세션 분량 (1 은 Repository + Merger + strings + displayNames, 2 는 Detail/List UI + ViewModel + tests).
- **함정 1**: `[지금 동기화]` 를 탭하는 즉시 SourceStatusStrip 칩이 `syncing` 으로 바뀌려면 `WorkScheduler.enqueueSourceSync` 가 WorkManager 에 enqueue 직후 `SourceStatusRepository.markSyncing(sourceType)` 를 호출해야 한다 (WorkInfo observer 가 붙기 전에는 UI 가 idle 로 보일 수 있음). 본 plan 은 이 로직을 Repository.disconnect 와 대칭으로 `Repository.markSyncing(sourceType)` 로 신설.
- **함정 2**: Reconnect 후 `SourceStatusRepository.refreshFromServer` 호출이 없으면 server-first 상태가 재동기화되지 않는다. VM.reconnect 결과 성공 시 `repository.refreshFromServer()` 를 trigger (낙관적 UI 는 이미 `CONNECTED` 로 전환된 뒤).
