# Worker / Sync / cursor-invalidation — ING-013 커서 무효화 + 30d 재동기 트리거 미구현

**Branch**: `feat/worker/sync/cursor-invalidation`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 3 (Data ingestion — incremental sync 복구 경로)
**Severity**: Medium (ING-013 전무 — Gmail historyId 만료 / IMAP UIDVALIDITY 변경 시 무한 재시도 또는 데이터 흐름 멈춤)
**Type**: Gap

---

## 1. Finding

ING-013 은 **3 가지 cursor-invalidation 시나리오** 에 대해 "DataStore cursor 를 초기화하고 최근 30 일 범위로 전체 재동기화" 를 요구한다:

- Gmail **HTTP 410** — `history.list` 의 `startHistoryId` 가 만료됨 (Gmail 은 최근 7 일만 history 보관)
- IMAP **UIDVALIDITY mismatch** — 서버 측 폴더 재생성 시 UID 가 재할당되어 기존 `lastSeenUid` 가 의미를 잃음
- MS Graph **HTTP 410** — `delta` token 이 만료됨 (Outlook mail/calendar delta)

**현재 부분 구현 상태**:

- Gmail 410 처리는 `GmailWorker.kt:99-101` 에 존재 — `cursorStore.setGmailHistoryId(null)` 까지는 수행. 단 **"최근 30 일 재동기" 가 명시적이지 않음** — full-sync fallback 범위가 스펙과 불일치 가능.
- IMAP UIDVALIDITY mismatch 대응 — `ImapNaverWorker.kt:43-45` 주석이 의도는 명시하지만, 구현이 "full resync all messages in INBOX" (스펙: "최근 30d") 로 범위 불일치.
- MS Graph 410 (Outlook mail/calendar) — 처리 경로 0 곳:
  ```bash
  grep -rn "410\|deltaToken\|deltaLink" android/app/src/main/java/com/becalm/android/worker/ingestion/OutlookMailWorker.kt
  grep -rn "410\|deltaToken\|deltaLink" android/app/src/main/java/com/becalm/android/worker/ingestion/OutlookCalendarWorker.kt
  ```
  → 410 분기 자체가 없음 → deltaLink 가 만료되면 **영구 sync 중단**.
- OverallSyncIndicator 가 "syncing 상태로 표시" 를 요구 (ING-013 expected 말미) — 구현 없음.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/data-ingestion.spec.yml:123-130` — ING-013 (원문)

```yaml
- id: ING-013
  type: lifecycle
  description: "커서 무효화(Gmail 410 historyId 만료 / IMAP UIDVALIDITY 변경 / MS Graph 410 deltaToken stale)
    시 해당 소스 어댑터가 DataStore cursor를 초기화하고 제한된 전체 재동기화(최근 30일)를 수행한다"
  trigger: "소스 API가 커서 무효화 오류 반환 (Gmail HTTP 410, IMAP UIDVALIDITY mismatch, MS Graph HTTP 410)"
  precondition: "어댑터 cursor가 DataStore에 저장됨, source API가 410 또는 UIDVALIDITY mismatch 응답"
  expected: "DataStore 해당 소스 cursor 초기화됨(null). 최근 30일 범위로 전체 재동기화 실행됨.
    신규 cursor 저장됨. OverallSyncIndicator의 해당 소스 칩이 syncing 상태로 표시됨"
```

### 2.2 `.spec/data-ingestion.spec.yml` invariants

> "커서는 로그아웃 또는 앱 삭제 시 DataStore에서 삭제된다"

### 2.3 `.spec/data-ingestion.spec.yml:83` — ING-008 (naver IMAP) 에서 UIDVALIDITY 연결

> "다음 sync 시 UIDVALIDITY 변경 감지되면 ING-013 재동기화"

---

## 3. Code Reality (지금 무엇인가)

### 3.1 Gmail 410 — 부분 구현, 30d 범위 명시 부재

`GmailWorker.kt:99-101`:
```kotlin
// historyId expired (404/410) — clear cursor then full-sync
cursorStore.setGmailHistoryId(null)
```

`GmailWorker.kt:186-187`:
> "The historyId cursor is not available from [full-sync] …"

→ Cursor clear 는 OK. 그러나 full-sync 범위가 "전체 받은편지함" 인지 "최근 30 일" 인지 코드 확인 필요. **스펙은 30d 로 제한**을 요구 (백필 비용 통제).

### 3.2 IMAP UIDVALIDITY — 범위 불일치

`ImapNaverWorker.kt:43-46`:
```kotlin
* Incremental sync is driven by the UIDVALIDITY + UIDNEXT cursor pair persisted in
* [SyncCursorStore] under mailbox [MAILBOX_NAVER]. A UIDVALIDITY mismatch causes a full
* resync (all messages in INBOX up to [MAX_MESSAGES_PER_RUN]) and resets the cursor.
```

→ "all messages in INBOX" — 스펙의 "최근 30 일" 과 불일치. 실제로는 `MAX_MESSAGES_PER_RUN` 상한에 의해 de facto 제한되겠으나, 시간 기반 cutoff 가 명시적으로 없음.

### 3.3 MS Graph 410 (Outlook) — 전무

```bash
grep -n "410\|410 Gone\|deltaLink" android/app/src/main/java/com/becalm/android/worker/ingestion/OutlookMailWorker.kt
# → expected: no matches
```

→ `OutlookMailWorker` / `OutlookCalendarWorker` 가 410 응답을 받아도 일반 Network error 로 처리되어 WorkManager 가 retry 를 반복. deltaLink 는 영원히 회복되지 않음.

### 3.4 `SyncCursorStore.kt` — cursor clear API 는 있음

```kotlin
// line 108
public fun observeGmailHistoryId(): Flow<Long?>
// line 116
public suspend fun setGmailHistoryId(historyId: Long?)
// line 131
public fun observeImapState(mailbox: String): Flow<ImapCursorState?>
// line 140
public suspend fun setImapState(mailbox: String, state: ImapCursorState?)
```

→ 인프라는 존재. 각 워커에서 410 감지 시 `set*(null)` 호출하면 됨. 단 MS Graph 쪽 `deltaLink` 에 해당하는 method 는 별도 확인 필요 — `observeOutlookDeltaLink` / `setOutlookDeltaLink` 가 이미 있는지 탐색:
```bash
grep -n "Outlook\|deltaLink" android/app/src/main/java/com/becalm/android/data/local/datastore/SyncCursorStore.kt
```
(없다면 신규 추가 필요.)

### 3.5 30d resync constant 부재

```bash
grep -rn "RESYNC_DAYS\|30.days\|DAYS_30" android/app/src/main/java/com/becalm/android/worker/
# → empty
```

→ 각 워커가 full-sync 시 사용할 공통 30d cutoff 가 없음.

### 3.6 OverallSyncIndicator 연동 — syncing 상태

`SourceStatusRepository` 가 `SourceConnectionStatus.SYNCING` enum 을 이미 보유 (ColdSyncViewModel 에서 사용). cursor invalidation 트리거 시점에 `recordSyncStart` 또는 `setStatus(SYNCING)` 호출이 필요 — 각 worker 내부 호출 여부 재검증.

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| Gmail 410 cursor clear | 구현 | 구현됨 | - |
| Gmail 410 range | 30d 제한 | 명시 없음 (full) | cutoff 파라미터 추가 |
| IMAP UIDVALIDITY | 30d 재동기 | "all messages in INBOX" | 30d cutoff 필터 추가 |
| MS Graph 410 (mail/cal) | 감지 + deltaLink 클리어 + 30d | **전무** | 신규 분기 + cursor clear + 30d fetch |
| OverallSyncIndicator | syncing 칩 표시 | 부분 (일반 sync 시) — 410 recovery 시 동일 경로 사용 여부 재확인 | recordSyncStart 호출 확인 |
| 공통 30d 상수 | — | 없음 | `const val RESYNC_DAYS = 30` |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/data/local/datastore/SyncCursorStore.kt`**
   - (필요 시) `observeOutlookMailDeltaLink` / `setOutlookMailDeltaLink(String?)` + calendar 대칭 추가.
   - 기존 `observeGmailHistoryId` / `observeImapState` 는 유지.

2. **`android/app/src/main/java/com/becalm/android/worker/ingestion/GmailWorker.kt`**
   - `seedHistoryIdCursor()` 및 full-sync fallback 에 **30d lower-bound 쿼리** 적용:
     - Gmail API `users.messages.list(q = "after:${cutoffEpochSec}")` 로 제한.
   - 410 recovery 경로에서 `sourceStatusRepository.recordSyncStart(SourceType.GMAIL)` 호출 (이미 있다면 no-op).

3. **`android/app/src/main/java/com/becalm/android/worker/ingestion/ImapNaverWorker.kt`** + `ImapDaumWorker.kt`
   - UIDVALIDITY mismatch 분기에서 IMAP `SEARCH SINCE <dd-MMM-yyyy>` command 로 30d 이내만 fetch.
   - cutoff: `Clock.System.now().minus(30.days).toLocalDateTime(utc).date.format(...)`.

4. **`android/app/src/main/java/com/becalm/android/worker/ingestion/OutlookMailWorker.kt`**
   - 410 응답 감지 분기 **신규 추가**:
     - HTTP 410 → `syncCursorStore.setOutlookMailDeltaLink(null)`
     - 30d 범위 full-sync (`$filter=receivedDateTime ge ${cutoffIso}`)
     - 신규 deltaLink 저장
     - `sourceStatusRepository.recordSyncStart(SourceType.OUTLOOK_MAIL)` 호출
   - 기존 retry 경로가 410 을 흡수하지 않도록 `WorkerRetry` 분류 로직 확인 — 410 은 **retry 아님, cursor invalidation** 으로 분류.

5. **`android/app/src/main/java/com/becalm/android/worker/ingestion/OutlookCalendarWorker.kt`**
   - 대칭 구현 — calendar delta 410 recovery.

6. **`android/app/src/main/java/com/becalm/android/worker/WorkerRetry.kt`** (존재 확인 필요)
   - 410 을 transient error 가 아닌 **cursor-invalidation sentinel** 로 매핑. `BecalmError.NotFound` 가 이미 있으면 재사용 (GmailWorker 가 이미 사용).

7. **`android/app/src/main/java/com/becalm/android/worker/ingestion/package-info.kt` 혹은 새 상수 파일**
   - 공통 상수:
     ```kotlin
     internal const val CURSOR_INVALIDATION_RESYNC_DAYS: Int = 30
     ```
   - 각 워커가 import.

### 5.2 Files to add

1. **`android/app/src/test/java/com/becalm/android/worker/ingestion/GmailWorker410Test.kt`**
   - Gmail fake server 가 first call 에 410 → worker 가 cursor null → second call 에 30d-filter 로 full-sync → 성공.

2. **`android/app/src/test/java/com/becalm/android/worker/ingestion/ImapNaverUidValidityTest.kt`**
   - IMAP fake 가 UIDVALIDITY 변경 → worker 가 30d SINCE 로 재fetch → 새 UIDVALIDITY/UID 저장.

3. **`android/app/src/test/java/com/becalm/android/worker/ingestion/OutlookMail410Test.kt`**
   - MS Graph fake 가 410 → deltaLink null → 30d filter 로 fetch → 신규 deltaLink 저장.

4. **`android/app/src/test/java/com/becalm/android/worker/ingestion/OutlookCalendar410Test.kt`**
   - 대칭.

### 5.3 Files to delete

없음.

### 5.4 Non-code changes

- Keystore / OAuth 토큰 관련 변경 없음 (cursor 는 DataStore, token 은 Keystore — 분리 유지).
- `SourceStatusRepository.recordSyncStart` API 가 이미 있으면 그대로 사용. 없으면 신규 추가 — **별도 PR** 로 분리 가능 (본 PR 외 범위).

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "CURSOR_INVALIDATION_RESYNC_DAYS\|resyncDays\|RESYNC_DAYS" android/app/src/main/java/com/becalm/android/worker/ | wc -l` ≥ 4 (상수 선언 + Gmail + IMAP×2 + Outlook×2 사용)
- [ ] **Grep invariant**: `grep -rn "410" android/app/src/main/java/com/becalm/android/worker/ingestion/OutlookMailWorker.kt | wc -l` ≥ 1
- [ ] **Grep invariant**: `grep -rn "410" android/app/src/main/java/com/becalm/android/worker/ingestion/OutlookCalendarWorker.kt | wc -l` ≥ 1
- [ ] **Grep invariant**: `grep -n "setGmailHistoryId(null)\|setImapState(mailbox, null)\|setOutlookMailDeltaLink(null)" android/app/src/main/java/com/becalm/android/worker/ingestion/ | wc -l` ≥ 3
- [ ] **Unit test**: `GmailWorker410Test — first call 410 → cursor cleared → 30d filter re-fetch` 통과
- [ ] **Unit test**: `ImapNaverUidValidityTest — UIDVALIDITY mismatch → 30d SINCE re-fetch` 통과
- [ ] **Unit test**: `OutlookMail410Test — 410 → deltaLink null → 30d filter fetch + save new deltaLink` 통과
- [ ] **Unit test**: `OutlookCalendar410Test` 대칭 통과
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공

---

## 7. Out of Scope

- **Daum IMAP** 가 존재하나 동일한 UIDVALIDITY 규약이므로 ImapNaverWorker 와 대칭 구현 — 본 PR 에 포함
- `OverallSyncIndicator` UI 시각화 — 이미 SYNCING enum 이 매핑되어 있으면 추가 작업 없음. UI 변경은 별도 PR 범위
- `SourceStatusRepository.recordSyncStart` 시그니처 신설 — 없다면 별도 PR `repo/source-status-record-sync-start`
- Gmail history 7 일 창 외 데이터 손실 복구 — Gmail 특성상 410 이후 missing 7~30 일 분은 full-sync 로 복구 가능 (멱등 client_event_id). 30 일 이전 것은 복구 불가 (스펙이 30d cutoff 명시)
- MS Graph `$deltaToken` 형식의 cursor 검증 로직 — 서버에 맡김

---

## 8. Dependencies

- **Blocked by**: 없음. `SyncCursorStore` 가 Gmail/IMAP 은 이미 지원. Outlook deltaLink 메서드가 없으면 본 PR 내 추가.
- **Blocks**:
  - `feat/worker/coldsync` (문서 5) — Stage 2 가 "cursor 안정화" 를 수행하지만 ING-013 경로도 동일한 30d cutoff 를 공유. 본 PR 머지 후 Stage 2 가 `CURSOR_INVALIDATION_RESYNC_DAYS` 상수를 재사용 가능.
- **병렬 가능**:
  - `fix/worker/sync/quarantine-chunk-split` (문서 3) — 다른 파일
  - `fix/worker/sync/foreground-upload-trigger` (문서 2) — 다른 파일

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

Revert 후:
- Gmail 410 recovery 는 여전히 작동 (기존 경로 유지). 30d cutoff 만 사라져 전체 백필로 돌아감.
- IMAP UIDVALIDITY 도 기존 경로 유지. 30d cutoff 사라짐.
- **Outlook 410 는 revert 이전 상태 (미구현) 로 돌아가며 delta 만료 시 sync 중단** — 위험 존재. 이 경우 OAuth 재연결이 유일한 회복 경로. revert 전 영향 평가 필수.

---

## Appendix — Session handoff notes

- **중간 크기 PR** (~400 LOC). 5 워커 (Gmail + NaverIMAP + DaumIMAP + OutlookMail + OutlookCalendar) 를 대칭 수정 + 4 테스트.
- **공통 상수 재사용 핵심**: `CURSOR_INVALIDATION_RESYNC_DAYS = 30` 을 한 파일에 둔 뒤 모든 워커가 import. 30 이라는 magic number 가 코드에 뿌려지지 않도록 주의.
- Gmail `q = "after:..."` 는 Unix epoch *초* 단위 (ms 아님). 실수 방지.
- IMAP `SEARCH SINCE` 는 RFC 3501 날짜 포맷 `dd-MMM-yyyy` (예: `20-Apr-2026`). locale 고정 영문 월 약어 필수.
- MS Graph `$filter=receivedDateTime ge 2026-03-21T00:00:00Z` ISO 8601 UTC. calendar 는 `start/dateTime` 필드 사용.
- `BecalmError.NotFound` (Gmail) / `BecalmError.CursorExpired` (신규 제안) — 신규 타입보다는 기존 `NotFound` 에 `retryableAsResync: Boolean` 플래그 추가하거나, 각 워커에서 HTTP 코드 직접 확인 (410 intercept) 중 택 1. 구현자 판단. 현재 GmailWorker 는 `BecalmError.NotFound` 사용 중.
- 410 감지는 OkHttp interceptor 레벨이 아닌 **워커 레벨** 에서 처리해야 함 — cursor state 업데이트 + 30d fetch 는 워커의 도메인 로직.
- `sourceStatusRepository.recordSyncStart` 가 현재 존재하는지 확인:
  ```bash
  grep -n "recordSyncStart\|recordSyncSuccess\|recordSyncError" android/app/src/main/java/com/becalm/android/data/repository/SourceStatusRepository.kt
  ```
  없으면 본 PR 에서 소규모 추가 (no-op 구현이어도 UI 연동만 하면 됨).
- **데이터 integrity**: client_event_id 멱등성 덕분에 30d 재동기화가 중복 INSERT 유발하지 않음 (ING-015 invariant). 기존 sync_status='synced' row 는 re-INSERT 시도에 대해 서버 200 ack + 로컬 no-op.
- 30d 범위를 초과하는 older 이메일은 이 recovery 범위 밖 — 사용자가 명시적으로 소스 재연결 시 cold sync stage 2 가 풀 스캔. 그래서 본 기능은 "이미 안정 운영 중인 사용자의 cursor 만료 회복" 에 한정된 scope.
