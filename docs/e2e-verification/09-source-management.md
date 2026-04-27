# E2E Verification — `source-management` 모듈

Spec: `becalm-android/.spec/source-management.spec.yml`

> Per-source admin & 오류 복구 경로. PRIMARY sync 트리거는 ING-011/TDY-006 이고, **SMG-005 `[지금 동기화]` 는 소스 상세 화면에서만 수동 트리거**.

---

## 0. 전체 흐름

```
SettingsScreen → SourcesListScreen (8 rows: 7 data sources + 연락처)
   ui/sources/SourcesListScreen.kt   ui/sources/SourcesListViewModel.kt:62
        │  (select source)
        ▼
SourceDetailScreen
   ui/sources/SourceDetailScreen.kt   ui/sources/SourceDetailViewModel.kt:88
   ├─ last_sync_at / events_synced_count / last_error
   ├─ [다시 연결] → OAuth 재인증 or IMAP 비밀번호 or SAF folder
   ├─ [연결 해제] → SourcesListViewModel.disconnectSource(sourceType)
   └─ [지금 동기화] → source owner별 수동 sync (backend endpoint 또는 local worker)
```

Source owners:
- Gmail / Outlook Mail: backend-managed OAuth + sync (`EmailOAuthConnector` → Railway)
- Google Calendar / Outlook Calendar: backend-managed OAuth + sync
- Naver / Daum IMAP: local worker (`ImapNaverWorker.kt`, `ImapDaumWorker.kt`)
- Voice (MediaStore): local worker (`MediaStoreWorker.kt`)

Owner rule:
- Room/local UI state remains the source of truth for rendering.
- Provider fetch owner is separate: backend-managed sources call Railway, local sources enqueue Android workers.
- IMAP app passwords are local credentials and must not be sent to Railway.

---

## SMG-001 — 7 소스 + 연락처 상태 표시

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Screen | `ui/sources/SourcesListScreen.kt` | LazyColumn |
| VM | `ui/sources/SourcesListViewModel.kt:62` | `SourcesListUiState` (L42) + `SourceStatusRow` (L29) |
| Repo | `data/repository/SourceStatusRepository.kt` | 7 소스 상태 + contacts 의사 상태 합성 |

---

## SMG-002 — 상세 화면 (last_sync_at, events_synced_count, last_error)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Screen | `ui/sources/SourceDetailScreen.kt` | detail layout |
| VM | `ui/sources/SourceDetailViewModel.kt:88` | `SourceDetailUiState` (L54) + `RecentEventSummary` (L40) |
| Data | `SourceStatusRepository.kt` | status per source |

API gap 주의 — `SourceDetailViewModel.kt:110/141` 의 "API gap: filter by sourceType in-memory" 주석 → 서버 측 소스별 필터 endpoint 부재, 전체 조회 후 메모리 필터. **perf 리스크 이슈**로 등록 필요.

---

## SMG-003 — 재연결 플로우

| 소스 종류 | 진입 파일 |
| --- | --- |
| OAuth (Gmail/Outlook/Google Calendar/Outlook Calendar) | `ui/onboarding/GmailOAuthScreen.kt` / `OutlookMailOAuthScreen.kt` / `GoogleCalendarOAuthScreen.kt` / `OutlookCalendarOAuthScreen.kt` |
| IMAP | `ui/onboarding/ImapSetupScreen.kt` (앱 비밀번호 재입력; backend OAuth 없음) |
| Voice | `ui/onboarding/RecordingFolderScreen.kt` (SAF 폴더 재선택) |

**Verify**: `SourceDetailScreen` 의 `[다시 연결]` 콜백이 Nav 로 각 화면에 재진입하는지. Mail/Calendar OAuth 는 브라우저를 열고 Railway `:status` polling 후 connected 상태로 돌아와야 한다.

---

## SMG-004 — 연결 해제 (cursor/creds 삭제, Room 유지)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| VM | `SourcesListViewModel.kt:118` | `disconnectSource(sourceType)` |
| Cursor clear | `data/local/datastore/SyncCursorStore.kt:88/96` | `clearCursor(source)` / `clearAll()` |
| Backend-managed mail flag clear | `UserPrefsStore.kt` | `setEmailSourceManagedByBackend(provider, false)` |
| IMAP clear | `SyncCursorStore.kt:140` | provider별 INBOX/SENT `setImapState(mailbox, null)` |
| Credential clear | `data/local/secure/ImapCredentialStore.kt` | IMAP app password clear |
| Room 유지 | `RawIngestionEventDao.kt` | DELETE 호출 **없어야** 함 |

**Verify invariant**:
```
grep -n "disconnectSource" becalm-android/android/app/src/main/java/com/becalm/android/ui/sources/SourcesListViewModel.kt
```
- [ ] 구현이 `RawIngestionEventDao.deleteAllForUser` / `EmailBodyDao.*delete*` 를 호출하지 않음.
- [ ] `SourceStatusRepository` 의 해당 칩이 idle 로 전이.

---

## SMG-005 — [지금 동기화] (소스 상세 화면에서만)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Button | `ui/sources/SourceDetailScreen.kt` | [지금 동기화] 버튼 |
| Local sources | `worker/WorkSchedulerImpl.kt` | IMAP/voice one-shot |
| Backend-managed sources | `ui/sources/SourceSyncPort.kt` | Gmail/Outlook Mail → `syncMailSource`, Calendar → `triggerServerSync` |

**Verify owner split**:
- `gmail`, `outlook_mail`, `google_calendar`, `outlook_calendar` do not route through on-device provider workers.
- `naver_imap`, `daum_imap`, `voice` do not call Railway provider-fetch endpoints.

**Verify invariant — dashboard 에서 소스별 수동 트리거 금지**:
```
grep -rn "enqueueUniqueWork.*sync-" becalm-android/android/app/src/main/java/com/becalm/android/ui
```
→ 결과는 `ui/sources/*` 에서만 나와야 한다. `ui/today/*` 에서 호출하면 invariant 위반.

---

## Invariants

| Invariant | 검증 |
| --- | --- |
| disconnect 시 Room 데이터 유지 | `disconnectSource` 경로에 DELETE 호출 없음 |
| disconnect 시 Keystore + DataStore cursor 만 삭제 | `SyncCursorStore.clearCursor` + token store `clear()` |
| 소스별 수동 sync 는 SourceDetailScreen 에서만 | 검색 grep (위 참조) |

---

## Tests

| 파일 | 커버 |
| --- | --- |
| `ui/sources/SourcesListViewModelTest.kt` | SMG-001/004 |
| `ui/sources/SourceDetailViewModelTest.kt` | SMG-002/005 |
| `unit/ui/sources/SourceDetailViewModelSpecTest.kt` | SMG-005 owner-based manual sync dispatch |
