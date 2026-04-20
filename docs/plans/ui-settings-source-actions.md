# UI / Sources / settings-source-actions — SourceDetail 상태 메타 + [지금 동기화]/[연결해제]/[다시 연결] 액션 배선

**Branch**: `feat/ui/sources`
**Status**: PLAN ONLY — 구현은 별도 세션. 이 plan doc 이외의 코드 커밋 금지.
**E2E Stage**: 5 — Settings → Sources (detail actions)
**Severity**: Medium
**Type**: Drift + Gap

> 동일 브랜치(`feat/ui/sources`)에 이미 plan doc 이 2건 존재 (`ui-sources-contacts-permission.md`, `ui-sources-detail-actions-and-localization.md`).
> 본 plan 은 **SourceDetail 액션 wiring + 상태 메타 표시** 만을 **작고 검증 가능한 단위**로 분리하고, 로컬라이즈·에러 taxonomy·401 interceptor 는 상위 omnibus plan 에 맡긴다.

---

## 1. Finding

`SourceDetailScreen` 은 SMG-002..005 요구 4 건 중 **상태 메타 3 필드 + 액션 3종** 이 모두 drift/gap 상태:

1. **SMG-002 상태 메타 미표시** — `SourceDetailUiState` 에 `last_sync_at`, `events_synced_count`, `last_error` 필드가 존재하지 않아 (`SourceDetailViewModel.kt:54-59`), Repository 가 이미 제공하는 `SourceStatus.lastSyncedAt` / `errorMessage` 값 (`SourceStatusRepository.kt:49-54`) 을 UI 가 드롭한다. 사용자는 status 칩만 보고 정확한 시각/건수/오류 사유를 알 수 없다.
2. **SMG-003 [다시 연결] 빈 람다** — 버튼은 존재하지만 `onClick = { /* TODO(SMG-004): wire reconnect action */ }` (`SourceDetailScreen.kt:158`). OAuth/IMAP/SAF 재진입 경로가 앱 어디에서도 트리거되지 않는다.
3. **SMG-004 [연결 해제] no-op** — `SourcesListViewModel.disconnectSource(sourceType)` 가 `logger.w(TAG, "disconnectSource called for $sourceType but no API method exists yet (SMG-004)")` (`SourcesListViewModel.kt:118-123`) 만 수행. `SourceStatusRepository` 에 `disconnect()` API 가 없고, Keystore credential wipe 와 per-source cursor reset 도 트리거되지 않는다. 확인 다이얼로그도 없다.
4. **SMG-005 [지금 동기화] 버튼 부재** — `SourceDetailScreen` Column 에는 reconnect/disconnect 두 버튼만 있고 (`SourceDetailScreen.kt:155-169`) [지금 동기화] 버튼이 **아예 없다**. 결과적으로 `SourceType` 별 수동 트리거 경로가 앱 어디에도 존재하지 않는다 (ING-011 foreground catch-up 과 DASH-009 pull-to-refresh 는 **모든 소스** 를 함께 돌리므로 소스별 강제 재시도 불가).

부차적 drift: `SourcesListViewModel` 의 `itemsCount = 0` 하드코딩 (`SourcesListViewModel.kt:78`) — SMG-001 `"N명 보강됨 / N건"` 요구를 충족시키기 위해 DAO 집계가 필요하나, 본 plan 은 `SourceDetailScreen` 의 `events_synced_count` 표시에만 한정하고 List 쪽 count drift 는 후속으로 위임.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/source-management.spec.yml:20-28` — SMG-002

> "id: SMG-002
>  type: ui_interaction
>  description: \"연결된 소스 탭 시 소스 상세 화면 표시 — last_sync_at, events_synced_count, last_error(있는 경우), [연결 해제] 버튼\"
>  screen: \"SourceDetailScreen\"
>  interaction: \"connected 소스 카드 탭\"
>  precondition: \"SourcesListScreen에서 Gmail(connected) 카드 탭\"
>  expected: \"SourceDetailScreen 표시됨. last_sync_at: 'HH:mm' 형식. events_synced_count: N건. last_error: 없음(오류 없을 시 미표시). [연결 해제] 버튼 표시됨. [지금 동기화] 버튼 표시됨\""

### 2.2 `.spec/source-management.spec.yml:30-38` — SMG-003

> "미연결 또는 오류 상태 소스 탭 시 해당 소스의 재연결 플로우로 이동한다 — OAuth 소스는 OAuth 재인증, IMAP 소스는 앱 비밀번호 재입력, voice는 SAF 폴더 재선택 … [다시 연결] 탭 시 Microsoft OAuth 재인증 플로우 실행됨. 재인증 성공 시 DataStore Outlook 토큰 업데이트됨. SourceDetailScreen 상태가 connected로 갱신됨"

### 2.3 `.spec/source-management.spec.yml:40-48` — SMG-004

> "소스 연결 해제 시 해당 소스의 DataStore cursor 초기화, Keystore credentials 삭제, 기존 Room 데이터는 유지, 칩 상태가 idle로 변경된다 … DataStore gmail last_cursor 초기화됨(null). Keystore Gmail OAuth 토큰 삭제됨. Room의 기존 gmail raw_ingestion_events + EmailBody 유지됨(삭제 없음). SourceStatusStrip Gmail 칩이 idle(회색)으로 변경됨"

### 2.4 `.spec/source-management.spec.yml:50-58` — SMG-005

> "소스 상세 화면의 [지금 동기화] 버튼이 해당 소스 어댑터만 선택적으로 실행한다 — WorkManager enqueueUniqueWork('sync-<source>', REPLACE). 이것이 앱에서 유일한 소스별 수동 트리거다 … WorkManager enqueueUniqueWork('sync-gmail', REPLACE) 호출됨. Gmail 어댑터만 실행됨(다른 소스 영향 없음). SourceDetailScreen의 last_sync_at 갱신됨. SourceStatusStrip Gmail 칩 syncing 상태로 전환 → 완료 후 synced로 전환됨"

### 2.5 `.spec/source-management.spec.yml:60-63` — invariants

> "소스 연결 해제 시 기존 Room 데이터(raw_ingestion_events, EmailBody, Transcript)는 삭제하지 않는다"
> "소스 연결 해제 시 해당 소스의 Keystore credentials와 DataStore cursor만 삭제된다"
> "[지금 동기화]는 소스 상세 화면에서만 접근 가능하다 — 대시보드에서 소스별 개별 동기화 불가"

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `SourceDetailUiState` 에 메타 3필드 부재 — `SourceDetailViewModel.kt:54-59`

```kotlin
public data class SourceDetailUiState(
    val sourceType: String = "",
    val status: String = "",
    val recentEvents: List<RecentEventSummary> = emptyList(),
    val error: String? = null,
)
```

Repository 는 이미 `lastSyncedAt` / `errorMessage` 를 제공 (`SourceStatusRepository.kt:49-54`) 하지만 VM `combine` 블록이 이 둘을 drop (`SourceDetailViewModel.kt:137-157`).

### 3.2 `SourceDetailScreen` 버튼 onClick 빈 람다, [지금 동기화] 부재 — `SourceDetailScreen.kt:155-169`

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

`[지금 동기화]` BecalmButton 은 이 Column 안 어디에도 없다 — status Row 와 위 두 버튼이 전부.

### 3.3 `SourcesListViewModel.disconnectSource` — `SourcesListViewModel.kt:118-123` log-only

```kotlin
public fun disconnectSource(sourceType: String) {
    // SMG-004: SourceStatusRepository.disconnect() does not exist in the current API.
    // When a revoke endpoint is added, replace this with a viewModelScope.launch call
    // that delegates to the repository.
    logger.w(TAG, "disconnectSource called for $sourceType but no API method exists yet (SMG-004)")
}
```

### 3.4 `SourceStatusRepository.disconnect(sourceType)` API 부재

`SourceStatusRepository` 인터페이스 (`SourceStatusRepository.kt:75-153`) 에는 `observeAll`, `observeFor`, `observeSources`, `refreshFromServer`, `recordSyncSuccess`, `recordSyncError`, `recordSyncStart`, `clearAll` 만 존재. per-source `disconnect()` 없음.

### 3.5 Keystore credential store + per-source cursor store 는 이미 존재

- `EncryptedTokenStore.clear()` (`EncryptedTokenStore.kt:154-162`) — **Supabase session 전체** wipe 용. per-source OAuth 토큰 wipe API 아님 (OAuth provider 전용 store 는 별도 plan `repo-auth-gmail-oauth-provider.md` / `repo-auth-msgraph-oauth-provider.md` 의 산출물 필요).
- `ImapCredentialStore.clear()` (`ImapCredentialStore.kt:110-113`) — **전역 IMAP** wipe. per-provider (naver/daum) 분리 API 는 `repo-imap-per-provider-credentials.md` 에서 제공.
- `SyncCursorStore.clearCursor(source)` (`SyncCursorStore.kt:88`) — per-source cursor reset 이 이미 제공됨. IMAP 은 `setImapState(mailbox, null)`, Gmail historyId 는 `setGmailHistoryId(null)`.

### 3.6 `WorkScheduler` 에 per-source oneShot enqueue 이미 존재

`WorkSchedulerImpl.kt:142-196` 에 `enqueueGmailOneShotNow()`, `enqueueImapNaverOneShotNow()`, `enqueueImapDaumOneShotNow()`, `enqueueOutlookMailOneShotNow()`, `enqueueGCalOneShotNow()`, `enqueueOutlookCalOneShotNow()`, `enqueueMediaStoreOneShotNow()` 가 정의돼 있다. 내부적으로 `enqueueUniqueWork(<UniqueWorkKeys.X>, REPLACE, request)` 를 호출하므로 SMG-005 의 `enqueueUniqueWork('sync-<source>', REPLACE)` 계약을 이미 충족. 본 plan 은 **신규 API 추가 없이 기존 메서드를 `sourceType → method` 디스패처로 호출**.

### 3.7 `RawIngestionEventDao` 에 `countBySourceType` 쿼리 부재

```bash
grep -rn "countBySourceType\|countBySource" android/app/src/main/java/com/becalm/android/data/local/db/dao/RawIngestionEventDao.kt
# → 0 matches
```
`events_synced_count` 산출은 DAO 신규 쿼리 1건을 추가하거나, 기존 `observeTimelineForUser` 결과를 in-memory 필터. 본 plan 은 **DAO 신규 쿼리 추가**를 권장 (user 성장 시 in-memory 카운트는 O(N) 스캔이 반복됨).

### 3.8 검증 grep

```bash
# 빈 람다 TODO 2건 존재 확인
grep -rn "TODO(SMG-004)" android/app/src/main/java/com/becalm/android/ui/sources/ | wc -l
# → 2

# disconnectSource log-only 확인
grep -rn "no API method exists yet" android/app/src/main/java/com/becalm/android/
# → 1 match (SourcesListViewModel.kt)

# [지금 동기화] 버튼 부재 확인
grep -rn "action_sync_now\|sync_now" android/app/src/main/res/values*/strings.xml
# → 0 matches
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 (SMG-##) | Code 현실 | Δ |
|------|--------------------|-----------|---|
| `last_sync_at` 표시 | `HH:mm` 형식 (SMG-002) | UiState 에 필드 자체 없음 | `SourceDetailUiState.lastSyncAt: Instant?` 추가 + KST `HH:mm` formatter |
| `events_synced_count` 표시 | N건 (SMG-002) | 필드 없음 | DAO `countBySourceType(userId, sourceType): Flow<Int>` 신설 + UiState 확장 |
| `last_error` 표시 | 있을 때만 (SMG-002) | 필드 없음 | UiState 에 `lastError: String?` 추가 (raw message 로 충분 — 로컬라이즈는 omnibus plan 책임) |
| [다시 연결] 액션 | OAuth/IMAP/SAF 재진입 (SMG-003) | 빈 람다 | `VM.reconnect()` → `ReconnectIntent` sealed emit → Composable 에서 launcher/navigate |
| [연결 해제] 액션 | cursor reset + Keystore wipe + status=idle (SMG-004) | log-only | `Repository.disconnect(sourceType)` + 확인 AlertDialog |
| [지금 동기화] 버튼 | `enqueueUniqueWork('sync-<source>', REPLACE)` (SMG-005) | 버튼 부재 | UI 버튼 + `VM.syncNow()` → `WorkScheduler.enqueue<Source>OneShotNow()` 디스패처 |
| Repository `disconnect()` API | SMG-004 | 부재 | `SourceStatusRepository.disconnect(sourceType)` suspend 메서드 + impl |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술.

### 5.1 Files to change

- **`android/app/src/main/java/com/becalm/android/ui/sources/SourceDetailViewModel.kt`**
  - `SourceDetailUiState` 에 3 필드 추가: `lastSyncAt: Instant?`, `eventsSyncedCount: Int`, `lastError: String?`.
  - Constructor 에 `WorkScheduler`, `RawIngestionRepository` (count 쿼리) 주입. OAuth/IMAP credential store 는 **직접 주입하지 않는다** — `SourceStatusRepository.disconnect()` 내부에서 합성한다 (SRP).
  - `combine(statusFlow, eventsFlow, countFlow)` 로 count 조인. `countFlow = rawIngestionRepository.observeCountForSourceType(userId, sourceType)`.
  - 신규 `public fun syncNow()` — `viewModelScope.launch` 안에서 `sourceType → WorkScheduler.enqueue<X>OneShotNow()` when-디스패처. `SourceType.GMAIL → workScheduler.enqueueGmailOneShotNow()` 등 6 source 매핑.
  - 신규 `public fun disconnect()` — `viewModelScope.launch { sourceStatusRepository.disconnect(sourceType) }`.
  - 신규 `public fun reconnect()` — `MutableSharedFlow<ReconnectIntent>.emit(...)` 로 one-shot emit. UI 가 `LaunchedEffect` 로 수신.

- **`android/app/src/main/java/com/becalm/android/ui/sources/SourceDetailScreen.kt`**
  - 상태 카드 `Column` 에 메타 3 줄 추가: `Text(stringResource(R.string.source_detail_last_sync_label, formatHHmm(lastSyncAt)))`, `Text(stringResource(R.string.source_detail_events_synced, eventsSyncedCount))`, `state.lastError?.let { Text(it, color = error) }` (없으면 숨김).
  - 버튼 3개 순서: `[지금 동기화]` (Primary) → `[다시 연결]` (Secondary) → `[연결 해제]` (Text). 각 onClick → VM 메서드 호출.
  - `[연결 해제]` 는 `var showConfirm by remember { mutableStateOf(false) }` 후 `if (showConfirm) AlertDialog(...)`. `[확인]` → `viewModel.disconnect()` + dismiss.
  - `LaunchedEffect(Unit) { viewModel.reconnectIntents.collect { intent -> when(intent) { ... } } }` — intent sealed 를 `navController.navigate` / OAuth launcher 호출로 매핑.

- **`android/app/src/main/java/com/becalm/android/ui/sources/SourcesListViewModel.kt`**
  - `disconnectSource` log-only 메서드 **제거** (dead code). disconnect 는 SourceDetail 단일 경유 (spec invariant "[지금 동기화]는 소스 상세에서만 접근" 과 정합, disconnect 역시 동일 원칙).

- **`android/app/src/main/java/com/becalm/android/data/repository/SourceStatusRepository.kt`** (interface + impl)
  - 신규 interface 메서드:
    ```
    public suspend fun disconnect(sourceType: String): BecalmResult<Unit>
    ```
  - impl 내부 순서: (1) `syncCursorStore` per-source clear — `SourceType.GMAIL → setGmailHistoryId(null)`, `NAVER_IMAP → setImapState("naver", null)`, `DAUM_IMAP → setImapState("daum", null)`, `OUTLOOK_MAIL → clearCursor("outlook_mail")`, `GOOGLE_CALENDAR → clearCursor("google_calendar")`, `OUTLOOK_CALENDAR → clearCursor("outlook_calendar")` ; (2) credential wipe — `ProviderDisconnectHandler` multibinding set 을 순회하며 sourceType 매칭 핸들러만 실행 (구현은 PR `repo-auth-gmail-oauth-provider.md` / `repo-auth-msgraph-oauth-provider.md` / `repo-imap-per-provider-credentials.md` 산출물); (3) DataStore source-status 키 3종 제거 (`lastSyncedAt`, `lastError`, `inProgress`) → `deriveStatus` 가 `NEVER_CONNECTED` 로 emit.
  - Hilt 주입 추가: `SyncCursorStore` + `Set<@JvmSuppressWildcards ProviderDisconnectHandler>` (dagger multibinding).

- **`android/app/src/main/java/com/becalm/android/data/local/db/dao/RawIngestionEventDao.kt`**
  - 신규 `@Query("SELECT COUNT(*) FROM raw_ingestion_events WHERE user_id = :userId AND source_type = :sourceType") fun observeCountBySourceType(userId: String, sourceType: String): Flow<Int>`.
  - sync_status 필터 없음 (SMG-002 문언: "events_synced_count: N건" — 전체 수집 건수 기준, synced-only 아님 — Appendix 참조).

- **`android/app/src/main/java/com/becalm/android/data/repository/RawIngestionRepository.kt`**
  - DAO 위임 `public fun observeCountForSourceType(userId: String, sourceType: String): Flow<Int>` 추가.

- **`android/app/src/main/res/values/strings.xml`** + **`values-ko/strings.xml`**
  - 신규 키 5개: `action_sync_now`, `source_detail_last_sync_label` (`마지막 동기화 %1$s`), `source_detail_events_synced` (`수집 %1$d건`), `disconnect_confirm_title` (`연결을 해제할까요?`), `disconnect_confirm_body` (`기존에 수집된 데이터는 앱에 유지됩니다. 저장된 인증 정보만 삭제됩니다.`).
  - `action_reconnect` / `action_disconnect` 는 이미 존재 (`values-ko/strings.xml:15-16` 확인).

### 5.2 Files to add

- **`android/app/src/main/java/com/becalm/android/ui/sources/ReconnectIntent.kt`** — sealed class. `LaunchGmailOAuth`, `LaunchOutlookOAuth`, `NavigateImapForm(sourceType)`, `LaunchGoogleCalendarOAuth`, `LaunchOutlookCalendarOAuth`. ViewModel 은 `navController` 의존 없이 intent 만 emit.
- **`android/app/src/main/java/com/becalm/android/data/repository/ProviderDisconnectHandler.kt`** — 인터페이스 `interface ProviderDisconnectHandler { val sourceType: String; suspend fun clearCredentials() }`. Hilt `@IntoSet` 으로 provider 각각이 구현체 등록. `SourceStatusRepositoryImpl.disconnect()` 가 set 을 순회하며 매칭 sourceType 의 핸들러만 실행.
- **Tests**:
  - `SourceDetailViewModelTest`:
    - `state_includesLastSyncAt_eventsSyncedCount_lastError` — Repository + DAO fake 로 3 필드 노출 확인.
    - `syncNow_dispatchesToWorkScheduler_perSourceType` (parameterized 6 source).
    - `disconnect_delegatesToRepository` — fake `SourceStatusRepository.disconnect(sourceType)` 1회 호출 검증.
    - `reconnect_emitsCorrectIntent_perSourceType` (parameterized 6 source).
  - `SourceStatusRepositoryDisconnectTest`:
    - Gmail disconnect → `SyncCursorStore.setGmailHistoryId(null)` 호출 + DataStore 3키 제거 + fake `ProviderDisconnectHandler(gmail).clearCredentials()` 호출.
    - IMAP naver disconnect → `setImapState("naver", null)` 호출.
    - Invariant: Room DAO 삭제 메서드가 호출되지 **않음** (mockk verify `{ dao wasNot Called }`).
  - `RawIngestionEventDaoCountBySourceTest` — Room in-memory DB 로 count 쿼리 정확성.

### 5.3 Files to delete (dead code)

- **`SourcesListViewModel.disconnectSource`** 메서드 (클래스 유지). `SourcesListViewModel.kt:110-123` 블록 제거.
- `SourceDetailScreen.kt:155-168` 의 `TODO(SMG-004)` 주석 2건 — 실제 wiring 으로 자연 소멸.

### 5.4 Non-code changes

- DB 마이그레이션: 없음. 신규 DAO 쿼리는 스키마 변경 없음 (SELECT COUNT).
- Manifest/Permission: 변경 없음.
- Hilt `RepositoryModule` 에 `@Multibinds abstract fun providerDisconnectHandlers(): Set<ProviderDisconnectHandler>` 1줄 추가.

---

## 6. Acceptance Criteria

다른 세션이 구현 완료 여부를 **기계적으로 검증**할 수 있는 항목.

- [ ] **Grep invariant**: `grep -rn "TODO(SMG-004)" android/app/src/main/java/com/becalm/android/ui/sources/ | wc -l` = 0.
- [ ] **Grep invariant**: `grep -rn "no API method exists yet" android/app/src/main/java/com/becalm/android/ | wc -l` = 0 (`SourcesListViewModel.disconnectSource` 제거).
- [ ] **Grep invariant**: `grep -rn "R\.string\.action_sync_now" android/app/src/main/java/com/becalm/android/ui/sources/ | wc -l` ≥ 1 (버튼 배선됨).
- [ ] **Grep invariant**: `grep -n "fun disconnect" android/app/src/main/java/com/becalm/android/data/repository/SourceStatusRepository.kt | wc -l` ≥ 2 (interface + impl).
- [ ] **Grep invariant**: `grep -n "observeCountBySourceType\|countBySourceType" android/app/src/main/java/com/becalm/android/data/local/db/dao/RawIngestionEventDao.kt | wc -l` ≥ 1.
- [ ] **Unit test**: `SourceDetailViewModelTest.state_includesLastSyncAt_eventsSyncedCount_lastError` 통과.
- [ ] **Unit test**: `SourceDetailViewModelTest.syncNow_dispatchesToWorkScheduler_perSourceType` — 6 sourceType × 각각 올바른 `enqueue<X>OneShotNow()` 1회 호출.
- [ ] **Unit test**: `SourceDetailViewModelTest.disconnect_delegatesToRepository` 통과.
- [ ] **Unit test**: `SourceStatusRepositoryDisconnectTest` — cursor clear + DataStore 3키 제거 + Room DAO 삭제 메서드 미호출 (invariant 2.5 첫 번째 문장).
- [ ] **Compile gate**: `./gradlew :app:assembleDebug :app:testDebugUnitTest` 성공.
- [ ] **Manual**: Gmail Detail `[지금 동기화]` 탭 → logcat 에 `enqueueGmailOneShotNow key=${UniqueWorkKeys.GMAIL}` 확인, `SourceStatusStrip` Gmail 칩 syncing → synced 전환.
- [ ] **Manual**: Gmail Detail `[연결 해제]` 탭 → AlertDialog 표시 → `[확인]` → row 가 `NEVER_CONNECTED` 로 전환, `SyncCursorStore.observeGmailHistoryId().first() == null` 확인, Room `raw_ingestion_events` 행 수 불변.

---

## 7. Out of Scope

이 PR 에서 **건드리지 말 것**. 의도치 않은 scope creep 방지.

- **로컬라이즈된 source 표시명** (`"네이버 메일"` 등) + **에러 taxonomy** (`"오류 — 토큰이 만료되었습니다"`) — omnibus plan `ui-sources-detail-actions-and-localization.md` 가 이미 담당. 본 plan 은 raw `sourceType` 라벨과 raw `lastError` 문자열을 그대로 노출한다.
- **401 token refresh `TokenRefreshInterceptor`** — omnibus plan 책임.
- **Gmail / Google Calendar OAuth provider 토큰 교환·revoke 구현** — `repo-auth-gmail-oauth-provider.md` 가 `ProviderDisconnectHandler(gmail)` / `ProviderDisconnectHandler(google_calendar)` 구현체 제공.
- **Outlook Mail / Outlook Calendar MSAL provider** — `repo-auth-msgraph-oauth-provider.md` 가 `ProviderDisconnectHandler(outlook_mail)` / `ProviderDisconnectHandler(outlook_calendar)` 구현체 제공.
- **IMAP per-provider credential 분리** — `repo-imap-per-provider-credentials.md` 가 `ProviderDisconnectHandler(naver_imap)` / `ProviderDisconnectHandler(daum_imap)` 구현체 제공.
- **voice SAF 재연결 launcher** — voice source 는 SMG-001 6-source 대상에 포함되지 않으며 본 plan 범위 외. 별도 plan 이 SAF launcher 재사용 담당.
- **contacts 행 상태 drift** — `ui-sources-contacts-permission.md` 책임.
- **SourcesListScreen `itemsCount = 0` drift** (`SourcesListViewModel.kt:78`) — SMG-001 의 "N명 보강됨" 표시는 별도 PR.
- **Room schema 변경** — 본 plan 은 SELECT COUNT 쿼리만 추가. 새 컬럼/인덱스 없음.
- **ENR-008 contacts detail navigation 분기** — 별도 plan.

---

## 8. Dependencies

- **Blocked by**:
  - `repo-auth-gmail-oauth-provider.md` — `ProviderDisconnectHandler(gmail)` + `ProviderDisconnectHandler(google_calendar)` 구현 필요 (없으면 disconnect 시 Keystore credential 이 남음 → SMG-004 invariant 2.5 "Keystore credentials 삭제" 미충족).
  - `repo-auth-msgraph-oauth-provider.md` — `ProviderDisconnectHandler(outlook_mail)` + `ProviderDisconnectHandler(outlook_calendar)` 동일.
  - `repo-imap-per-provider-credentials.md` — `ProviderDisconnectHandler(naver_imap)` + `ProviderDisconnectHandler(daum_imap)` 동일.

  본 plan 머지 전에 위 3 plan 의 `ProviderDisconnectHandler` multibinding 이 **존재해야 SMG-004 invariant 충족**. 만약 blocker 가 미완성인 상태로 본 plan 을 먼저 머지한다면 disconnect 는 cursor + DataStore status 만 wipe 하고 credential 은 그대로 남아 SMG-004 불완전.

- **병렬 가능**:
  - `ui-sources-contacts-permission.md` — `SourcesListViewModel.kt` 가 파일 겹침이지만 본 plan 은 해당 VM 의 `disconnectSource` 메서드 **제거**만, contacts-permission 은 `observeAll` 의 contactsRow 하드코딩 교체만 → 두 diff 는 disjoint, rebase 단순.
  - `ui-sources-detail-actions-and-localization.md` (omnibus) — `SourceDetailScreen.kt` / `SourceDetailViewModel.kt` 가 **동일 파일** 에 쓰므로 충돌 가능. omnibus 가 로컬라이즈/에러 taxonomy 로 전체 리라이트에 가까운 반면, 본 plan 은 메타 3필드 + 버튼 3종만. **머지 순서**: 본 plan 먼저 (소규모 surgical) → omnibus 이후 (로컬라이즈 wrapping).
  - `worker-person-enrichment-periodic-observer.md` — 파일 겹침 없음.

- **Blocks**: 없음 (UI leaf).

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

- `SourceStatusRepository.disconnect()` 호출 이력이 있는 사용자는 Keystore/cursor 가 이미 wiped 상태로 남음 — revert 는 쓰기 경로만 되돌리므로 해당 사용자는 재로그인 필요. revert 로 인한 **데이터 손실은 없음** (Room 데이터 불변 invariant).
- 신규 DAO 쿼리 `observeCountBySourceType` 는 SELECT-only — revert 시 스키마 영향 없음.
- `ReconnectIntent` sealed 및 `ProviderDisconnectHandler` 인터페이스는 신규 파일 — revert 시 consumer 가 본 plan 의 VM 과 Repository impl 뿐이므로 compile 실패 없음. 단, `repo-auth-*` / `repo-imap-*` plan 이 이미 머지된 상태라면 해당 impl 들의 `ProviderDisconnectHandler` 구현체가 orphan 으로 남음 (unused dagger multibinding entry) — compile 은 성공, 런타임 impact 없음.
- AlertDialog string resource 는 revert 시 제거됨 — 참조 누락 없음.

revert 체크리스트:
- [ ] `./gradlew :app:assembleDebug :app:testDebugUnitTest` 성공.
- [ ] `SourceDetailScreen` preview 렌더 성공 (메타 3필드 / 버튼 3종 제거된 pre-revert 화면).
- [ ] `SourcesListScreen` 변경 없음 확인 (본 plan 은 List 파일 미수정).

schema 변경 없으므로 데이터 복구 전략 불필요.

---

## Appendix — Session handoff notes

- **왜 `SourceStatusRepository.disconnect()` 를 신설하고 `SourcesListViewModel.disconnectSource` 를 제거하는가**: List 에서 disconnect 하는 경로는 spec invariant "[지금 동기화]는 소스 상세에서만 접근" 의 정신과 정합하지 않는다 — disconnect 역시 상세 화면의 확인 다이얼로그를 거치는 경로로 일원화. 또한 VM 에서 Repository 를 "API 없음" 이라 하드코딩 log 해둔 dead-code 는 구현 세션이 해당 주석만 보고 Repository 확장을 건너뛰는 실수를 유발한다.
- **`ProviderDisconnectHandler` multibinding 을 쓰는 이유**: `SourceStatusRepositoryImpl` 이 6 source 의 credential store (Gmail OAuth, Outlook OAuth, Google Calendar OAuth, Outlook Calendar OAuth, Naver IMAP, Daum IMAP) 를 직접 주입받으면 순환 의존과 SRP 위반. Dagger `@IntoSet` 으로 provider 측에서 자기 store 와 sourceType 을 합성한 handler 를 등록하고, Repository 는 sourceType 필터링만 수행.
- **`events_synced_count` 의 필터 기준**: SMG-002 문언은 "events_synced_count: N건" — synced 만인지 전체 수집인지 명시 없음. 사용자 멘탈 모델 상 "수집된 건수 = 로컬 Room 에 존재하는 건수" 가 가장 자연스럽고, syncStatus 별 세분은 settings 고급 화면의 책임. 본 plan 은 **전체 수집 건수** 로 해석 (`COUNT(*) WHERE source_type = ? AND user_id = ?`). spec 저자 확인 후 필요 시 `syncStatus='synced'` 필터 추가는 trivial.
- **`enqueueUniqueWork('sync-<source>', REPLACE)` 계약**: 현 `WorkSchedulerImpl.enqueue<X>OneShotNow()` 가 내부적으로 `enqueueUniqueWork(UniqueWorkKeys.X, REPLACE, request)` 를 수행 (예: `UniqueWorkKeys.GMAIL`). SMG-005 의 `'sync-gmail'` 문자열과는 키 네이밍이 다르나, 유일성과 REPLACE 정책은 동일하므로 spec 취지 충족. spec 저자가 키 문자열 포맷을 엄격히 요구한다면 `UniqueWorkKeys` 상수 값만 `"sync-gmail"` 등으로 치환 — 본 plan 범위 외.
- **AlertDialog 컴포넌트**: 프로젝트의 `ui/components/` 에 `BecalmAlertDialog` 가 있으면 그걸, 없으면 Material3 `AlertDialog` 를 직접 사용. string resource `action_disconnect` 는 이미 존재 (`values-ko/strings.xml:16`).
- **`reconnect()` 가 navigation 을 emit 하지 않고 `ReconnectIntent` 를 emit 하는 이유**: Compose 컨벤션 — ViewModel 은 `NavController` 의존성 없음. intent one-shot flow (`SharedFlow<ReconnectIntent>` + `replay=0`) 로 UI 가 `LaunchedEffect` 에서 `navController.navigate` 또는 OAuth launcher 를 소환. 테스트는 intent emit 만 검증하면 충분.
- **커밋 메시지 형식** (CLAUDE.md CI/CD protocol 준수, `feat/ui/sources` 브랜치 누적):
  - `feat(ui/sources): settings-source-actions — SourceDetail 메타 3필드 + syncNow/disconnect/reconnect 배선`
  - `feat(repo/sources): settings-source-actions — SourceStatusRepository.disconnect + ProviderDisconnectHandler multibinding`
  - `feat(db/raw-events): settings-source-actions — observeCountBySourceType DAO 쿼리 추가`
  - 각 commit 는 동일 브랜치 `feat/ui/sources` 에 누적, logic-slug 는 커밋 메시지에만 존재.
- **omnibus plan 과의 병합 전략**: 본 plan 이 먼저 머지되고 omnibus 가 이어서 로컬라이즈/에러 taxonomy 를 wrapping — omnibus 의 `SourceDetailUiState` 확장안은 본 plan 의 필드 3개를 전제하면 drop-in 으로 `lastError: String?` 를 `lastErrorCode: SourceErrorCode?` 로 교체하는 소규모 diff 로 축소된다.
