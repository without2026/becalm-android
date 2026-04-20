# DB / Calendar / status-recurring — `calendar_events.status`/`recurring_event_id`/`original_start_at` 3 컬럼 + 2 인덱스 + cancelled → UPDATE invariant

**Branch**: `feat/db/calendar/status-recurring`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 2–3 (Ingestion worker 계약 / Room 스키마)
**Severity**: High (TDY-001 요구 "cancelled → strike-through" 가 저장 스키마에 없어 완전 blocking. ING-009/010 의 "DELETE 금지, UPDATE status='cancelled'" invariant 가 Worker 에 실장되어 있지 않음)
**Type**: Drift (data-model.yml:259-285 스펙과 `CalendarEventEntity` 실제 컬럼 집합 불일치)

---

## 1. Finding

`CalendarEventEntity` (스키마 version 3, PR #17 이후 4) 에 다음 3 컬럼이 빠져 있다:

1. `status` (text, NOT NULL, default `'confirmed'`) — `confirmed | cancelled` enum
2. `recurring_event_id` (text, nullable) — Google `recurringEventId` / MS Graph `seriesMasterId`
3. `original_start_at` (timestamptz, nullable) — instance 의 원래 시각 (이동/오버라이드 감지)

그리고 다음 2 인덱스도 빠져 있다:

- `idx_calendar_events_user_status_start` on `(user_id, status, start_at)` — TodayTimelineScreen 에서 confirmed 필터링 + cancelled 회색 분리 쿼리 지원
- `idx_calendar_events_user_recurring` on `(user_id, recurring_event_id)` — series-level master lookup (post-MVP)

또한 ING-009/010 의 **"cancelled 이벤트는 DELETE 하지 않고 row 를 `status='cancelled'` 로 UPDATE"** invariant 가 `GoogleCalendarWorker` / `OutlookCalendarWorker` 그 어느 곳에도 구현되어 있지 않다. 현재 Worker 들은 Graph `@removed` / Google `deletedEvent` 응답을 파싱하지 않으며, `CalendarEventEntity.toEntity(...)` 매퍼도 `status` / `recurringEventId` / `originalStartAt` 필드를 전혀 전달하지 않는다. 결과적으로 TDY-001 "cancelled → strike-through + dim" 렌더링은 **UI 에 표시할 데이터 자체가 Room 에 존재하지 않아** 완전 차단된다.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/contracts/data-model.yml:259-272` — 3 컬럼 field-level 정의

```yaml
- name: status
  type: text
  nullable: false
  default: "'confirmed'"
  # enum: confirmed | cancelled
  # 취소된 이벤트도 하드 삭제하지 않고 status='cancelled'로 보존하여 UI에서 회색 처리(strike-through) 후 노출
  # — 사용자가 과거에 본 약속의 흔적을 잃지 않도록 함 (ING-009, ING-010)
- name: recurring_event_id
  type: text
  nullable: true
  # Google Calendar: recurringEventId (master event ID for instances). Microsoft Graph: seriesMasterId.
  # NULL for non-recurring events. Enables UI grouping ('반복 — 매주 월') and series-level operations post-MVP.
- name: original_start_at
  type: timestamptz
  nullable: true
  # Google Calendar: originalStartTime. Microsoft Graph: originalStart. NULL for first-time-generated instances.
  # When an instance is rescheduled (start_at != original_start_at) UI can show '원래: M/d HH:mm'.
  # Used by ING-009/010 to detect override-vs-cancel distinction.
```

### 2.2 `.spec/contracts/data-model.yml:280-285` — 2 인덱스

```yaml
- columns: [user_id, status, start_at]
  type: btree
  # idx_calendar_events_user_status_start — TodayTimelineScreen에서 status='confirmed' 필터링과
  # 회색 처리 분리 쿼리 지원
- columns: [user_id, recurring_event_id]
  type: btree
  # idx_calendar_events_user_recurring — series-level lookup for master-event view (post-MVP).
```

### 2.3 `.spec/contracts/data-model.yml:470` — 명시적 Room migration DDL

> "calendar_events.status 컬럼 (confirmed|cancelled) 신설: 취소된 캘린더 이벤트의 soft-keep을 위해. Room 마이그레이션: ALTER TABLE calendar_events ADD COLUMN status TEXT NOT NULL DEFAULT 'confirmed'; Supabase 미러는 Railway migration에서 동일 컬럼 추가. 기존 row는 default 'confirmed'로 backfill됨. ING-009/010에서 deletedEvent/isCancelled 감지 시 DELETE 대신 UPDATE status='cancelled'"

### 2.4 `.spec/data-ingestion.spec.yml:87-94` — ING-009 Google Calendar

> "[주기적 보조 경로] Google Calendar sync가 오늘의 이벤트를 Room CalendarEvent에 저장한다. 취소된 이벤트(Google API status='cancelled')는 하드 삭제하지 않고 Room calendar_events.status='cancelled'로 UPSERT하여 UI가 회색 처리(strike-through) 후 표시하도록 한다"
>
> expected: "Google API가 deletedEvent 또는 status='cancelled'로 반환한 이벤트는 기존 row를 status='cancelled'로 UPDATE(DELETE 아님)."

### 2.5 `.spec/data-ingestion.spec.yml:96-103` — ING-010 Outlook Calendar

> "취소된 이벤트(MS Graph isCancelled=true 또는 @removed deltaToken 응답)는 하드 삭제하지 않고 Room calendar_events.status='cancelled'로 UPSERT하여 회색 처리 후 UI에 유지한다"
>
> expected: "MS Graph가 isCancelled=true 또는 @removed 이벤트를 반환한 경우 기존 row를 status='cancelled'로 UPDATE(DELETE 아님)"

### 2.6 `.spec/today-timeline.spec.yml:7-14` — TDY-001 전제

> "TodayTimelineScreen 진입 시 오늘 캘린더 이벤트(status='confirmed')와 due_at이 오늘 KST range에 속하는 commitment가 시간순으로 표시된다. 취소된 캘린더 이벤트(status='cancelled')는 strike-through + dim으로 함께 표시됨."

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `android/app/src/main/java/com/becalm/android/data/local/db/entity/CalendarEventEntity.kt:29-91` — 3 컬럼 모두 없음

현재 필드: `id, userId, sourceType, sourceRef, title, startAt, endAt, attendeesRaw, syncStatus` — **끝**. `status`, `recurringEventId`, `originalStartAt` 전무.

indices (line 31-33):
```kotlin
indices = [
    Index(value = ["user_id", "start_at"]),
],
```
→ `(user_id, status, start_at)`, `(user_id, recurring_event_id)` 없음.

### 3.2 `android/app/src/main/java/com/becalm/android/data/local/db/migration/Migrations.kt:72` — `MIGRATION_N_M` 없음

현재 `MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)`. 본 PR 기준 N→(N+1) 마이그레이션 신설 필요 (§5.4 참조).

### 3.3 `android/app/src/main/java/com/becalm/android/data/local/db/BeCalmDatabase.kt:92` — `DATABASE_VERSION = 3`

PR #17 이 3→4 까지 bump 한다고 가정하면 본 PR 은 4→5, PR #20 이 선착하면 5→6. 최종 번호는 merge 순서 확정 시점에 구현자가 결정.

### 3.4 `android/app/src/main/java/com/becalm/android/worker/ingestion/GoogleCalendarWorker.kt:56-152` — Railway-mediated flow, cancelled UPDATE 로직 없음

현재 `doWork()` 는 `triggerServerSync()` + `refreshSince()` 두 단계만 수행. cancelled / deletedEvent 분기 0 건:
```bash
grep -n "cancelled\|deletedEvent\|status" android/app/src/main/java/com/becalm/android/worker/ingestion/GoogleCalendarWorker.kt
# → 0 matches (only "status_label"-style strings)
```

### 3.5 `android/app/src/main/java/com/becalm/android/worker/ingestion/OutlookCalendarWorker.kt:178-189` — `GraphCalendarEvent.toEntity(userId)` 가 status / recurring / originalStart 미전달

```kotlin
private fun GraphCalendarEvent.toEntity(userId: String): CalendarEventEntity =
    CalendarEventEntity(
        id = UUID.nameUUIDFromBytes("outlookcal:$userId:$id".toByteArray(Charsets.UTF_8)).toString(),
        userId = userId,
        sourceType = SourceType.OUTLOOK_CALENDAR,
        sourceRef = id,
        title = subject ?: "",
        startAt = start,
        endAt = end,
        attendeesRaw = attendeesRaw,
        syncStatus = "pending",
    )
```
→ `status`, `recurringEventId`, `originalStartAt` 필드 모두 미전달. `isCancelled=true` / `@removed` 이벤트를 별도 분기하는 코드 없음 — pagination 루프(line 102-156) 가 `page.value` 를 그대로 upsert.

### 3.6 `android/app/src/main/java/com/becalm/android/data/repository/CalendarEventRepository.kt:316-327` — `CalendarEventDto.toEntity(userId)` 에서도 status 생략

```kotlin
private fun CalendarEventDto.toEntity(userId: String): CalendarEventEntity =
    CalendarEventEntity(
        id = id, userId = userId, sourceType = sourceType, sourceRef = sourceRef,
        title = title, startAt = startAt, endAt = endAt, attendeesRaw = attendeesRaw,
        syncStatus = syncStatus ?: "synced",
    )
```
→ Railway 서버가 `status` 필드를 응답에 포함하더라도 Android DTO 가 수신·저장하지 않는다.

### 3.7 `android/app/src/main/java/com/becalm/android/data/repository/CalendarEventRepository.kt` — `markCancelled` / `observeTodayConfirmed` 없음

본 repository interface 에는 `observeForUser(userId, from, to)` / `observeForPerson` / `refreshSince` / `insertLocalBatch` / `triggerServerSync` / `deleteAllForUser` 6 개 함수만 존재. TDY-001 이 요구하는 "confirmed 만 필터한 flow" 와 ING-009/010 이 요구하는 "row 를 cancelled 로 마킹" 메서드가 모두 없다.

### 3.8 `android/app/src/main/java/com/becalm/android/data/local/db/dao/CalendarEventDao.kt` — observe 에 status 필터 없음

`observeInRange` (line 48-61) 는 `start_at` 범위만 필터. `WHERE status = 'confirmed'` 도, status 로 정렬·분리하는 projection 도 없음.

### 3.9 검증 grep

```bash
# 컬럼 부재 확인
grep -n "\"status\"\|\"recurring_event_id\"\|\"original_start_at\"" \
  android/app/src/main/java/com/becalm/android/data/local/db/entity/CalendarEventEntity.kt
# → 0

# DELETE 호출 확인 (cancelled 처리로 오용되어서는 안 됨)
grep -rn "deleteAllForUser\|DELETE FROM calendar_events" \
  android/app/src/main/java/com/becalm/android/
# → 로그아웃 경로에서만 사용되어야 함
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| status 컬럼 | `text NOT NULL DEFAULT 'confirmed'` | 없음 | 컬럼 추가 + enum validation |
| recurring_event_id | `text nullable` | 없음 | 컬럼 추가 |
| original_start_at | `timestamptz nullable` | 없음 | 컬럼 추가 (Room INTEGER) |
| idx_calendar_events_user_status_start | `(user_id, status, start_at)` btree | 없음 | 인덱스 추가 |
| idx_calendar_events_user_recurring | `(user_id, recurring_event_id)` btree | 없음 | 인덱스 추가 |
| ING-009 cancelled 감지 | Google `status='cancelled'` / `deletedEvent` → UPDATE | Worker 파싱·분기 0 건 | Worker + DTO + repo.markCancelled |
| ING-010 cancelled 감지 | MS Graph `isCancelled=true` / `@removed` → UPDATE | `GraphCalendarEvent.toEntity` status 생략 | DTO + Worker 분기 + mapper |
| TDY-001 confirmed 필터 flow | `observeTodayConfirmed` | 없음 | repo + DAO 메서드 신설 |
| DTO status 수신 | Railway 응답 `status` 필드 parse | `CalendarEventDto.toEntity` status 생략 | DTO + mapper 갱신 |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술.

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/data/local/db/entity/CalendarEventEntity.kt`**
   - 3 필드 추가 (`status: String = "confirmed"`, `recurringEventId: String? = null`, `originalStartAt: Instant? = null`) — `@ColumnInfo(name = "status", defaultValue = "'confirmed'")` 등.
   - `indices = [...]` 에 `Index(value = ["user_id", "status", "start_at"], name = "idx_calendar_events_user_status_start")` 및 `Index(value = ["user_id", "recurring_event_id"], name = "idx_calendar_events_user_recurring")` 추가.
   - KDoc 확장: "status enum: confirmed|cancelled; cancelled row 는 DELETE 금지 — ING-009/010 invariant 참조"

2. **`android/app/src/main/java/com/becalm/android/data/local/db/BeCalmDatabase.kt`**
   - `DATABASE_VERSION` bump (N → N+1). 정확한 번호는 PR #17/#20 머지 순서에 따라 구현 세션에서 확정.

3. **`android/app/src/main/java/com/becalm/android/data/local/db/migration/Migrations.kt`**
   - 신규 `MIGRATION_N_(N+1)` — spec DDL (§2.3 data-model.yml:470):
     - `ALTER TABLE calendar_events ADD COLUMN status TEXT NOT NULL DEFAULT 'confirmed'`
     - `ALTER TABLE calendar_events ADD COLUMN recurring_event_id TEXT`
     - `ALTER TABLE calendar_events ADD COLUMN original_start_at INTEGER`
     - `CREATE INDEX idx_calendar_events_user_status_start ON calendar_events(user_id, status, start_at)`
     - `CREATE INDEX idx_calendar_events_user_recurring ON calendar_events(user_id, recurring_event_id)`
   - `MIGRATIONS` 배열에 추가.

4. **`android/app/src/main/java/com/becalm/android/data/local/db/dao/CalendarEventDao.kt`**
   - 신규 `observeTodayConfirmed(userId, rangeStart, rangeEnd)` flow — `WHERE status = 'confirmed'` 필터 + `ORDER BY start_at ASC`.
   - 기존 `observeInRange` 는 TDY-001 "cancelled strike-through" 를 위해 유지 (status 무관, confirmed + cancelled 모두 반환). ViewModel/UI 가 status 로 분기.
   - 신규 `markCancelled(id: String, at: Instant): Int` — `UPDATE calendar_events SET status='cancelled', sync_status='pending' WHERE id = :id` (DELETE 금지 invariant).

5. **`android/app/src/main/java/com/becalm/android/data/repository/CalendarEventRepository.kt`**
   - Interface 에 `markCancelled(id: String): BecalmResult<Int>` 추가.
   - Interface 에 `observeTodayConfirmed(userId, from, to): Flow<List<CalendarEventEntity>>` 추가 (또는 TDY-001 이 cancelled 도 함께 받아야 하므로 기존 `observeForUser` 유지하고 UI 측 filter — 구현 세션에서 결정, 권장: repo 에 둘 다 노출하여 caller 가 선택).
   - `CalendarEventDto.toEntity(userId)` 매퍼에 3 필드 전달 (기본값 `"confirmed"` / `null` / `null` fallback).

6. **`android/app/src/main/java/com/becalm/android/data/remote/dto/CalendarEventDto.kt`** (및 Graph DTO)
   - `@field:Json(name = "status") val status: String? = null`
   - `@field:Json(name = "recurring_event_id") val recurringEventId: String? = null`
   - `@field:Json(name = "original_start_at") val originalStartAt: Instant? = null`
   - MS Graph 측 `GraphCalendarEvent` 에 `isCancelled: Boolean`, `seriesMasterId: String?`, `originalStart: Instant?` 필드 추가 (없다면). `@removed` marker 는 `calendarViewDelta` page-level 에서 식별 — 구현 시 `CalendarViewDeltaPage` 구조 확인.

7. **`android/app/src/main/java/com/becalm/android/worker/ingestion/GoogleCalendarWorker.kt`**
   - Railway-mediated 모델 유지: 서버가 `status='cancelled'` 응답을 주면 Android 는 upsert 만 하면 OK. 단, `refreshSince` 가 소프트삭제 row 를 단순 덮어써야 하므로 repo 의 upsert 전략이 `OnConflictStrategy.REPLACE` 임을 확인하고, REPLACE 시 기존 row 의 `status` 컬럼이 DTO 값으로 덮어써지도록 매퍼에 `status = dto.status ?: "confirmed"` 명시.
   - **DELETE 호출 금지** invariant 주석 + grep test (§6). 현 Worker 에 DELETE 호출은 없으므로 실질 변경은 매퍼 경유.

8. **`android/app/src/main/java/com/becalm/android/worker/ingestion/OutlookCalendarWorker.kt`**
   - pagination 루프(line 102-156)의 `events.map { it.toEntity(userId) }` 를 유지하되 `toEntity` 매퍼에 `status = if (isCancelled) "cancelled" else "confirmed"` / `recurringEventId = seriesMasterId` / `originalStartAt = originalStart` 전달.
   - `@removed` marker 가 별도 리스트로 오면 해당 리스트는 DELETE 대신 `calendarEventRepository.markCancelled(sourceRef)` 로 처리. DELETE 금지.

### 5.2 Files to add

- `android/app/schemas/com.becalm.android.data.local.db.BeCalmDatabase/<new>.json` — KSP 산출물, 빌드 후 커밋.
- `android/app/src/androidTest/java/com/becalm/android/data/local/db/CalendarStatusMigrationTest.kt` — 본 PR 의 migration 검증.

### 5.3 Files to delete (dead code)

없음.

### 5.4 Non-code changes

- **DB migration**: schema version bump. PR #17 이 3→4, PR #20 이 4→5 라면 본 PR 은 5→6. Room migration 은 선형 체인이므로 머지 순서에 따라 번호 조정 필요 — 구현자가 merge 직전 확인 후 확정.
- Supabase Postgres DDL 은 별도 migration file (Railway/Supabase 팀 영역, becalm-backend 레포).
- Config / manifest / permission 변경 없음.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -n "\"status\"\|\"recurring_event_id\"\|\"original_start_at\"" android/app/src/main/java/com/becalm/android/data/local/db/entity/CalendarEventEntity.kt | wc -l` ≥ 3
- [ ] **Grep invariant**: `grep -n "idx_calendar_events_user_status_start\|idx_calendar_events_user_recurring" android/app/src/main/java/com/becalm/android/data/local/db/entity/CalendarEventEntity.kt | wc -l` ≥ 2
- [ ] **Grep invariant (DELETE 금지)**: `grep -rn "DELETE FROM calendar_events" android/app/src/main/java/com/becalm/android/worker/ingestion/ | wc -l` = 0 (Worker 에서 calendar_events 를 DELETE 하지 않는다)
- [ ] **Grep invariant**: `grep -rn "markCancelled" android/app/src/main/java/com/becalm/android/worker/ingestion/ | wc -l` ≥ 1 (Outlook `@removed` 경로가 markCancelled 를 호출)
- [ ] **MigrationTest**: 신규 migration 후 3 컬럼 존재 + default `confirmed` backfill 검증 (`SELECT status FROM calendar_events` → 기존 row 전부 `'confirmed'`)
- [ ] **MigrationTest**: 2 인덱스 존재 (`SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='calendar_events'`)
- [ ] **Unit test**: `CalendarEventRepositoryImplTest — markCancelled 가 row 를 DELETE 하지 않고 status='cancelled' 로 UPDATE 한다`
- [ ] **Unit test**: `OutlookCalendarWorkerTest — isCancelled=true 이벤트는 insertLocalBatch 에 status='cancelled' 로 전달된다`
- [ ] **Schema JSON**: `app/schemas/.../<new>.json` commit 포함
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin` 성공

---

## 7. Out of Scope

- TDY-001 UI 측 strike-through 렌더 — 별도 PR `feat/ui/today` (`docs/plans/ui-today-counterparty-direction.md`). 본 PR 은 데이터 저장 + Worker invariant 까지.
- `recurring_event_id` 를 활용한 "반복 — 매주 월" series master 뷰 — post-MVP.
- `original_start_at` 을 활용한 "원래: M/d HH:mm" 표시 — post-MVP.
- Supabase Postgres 마이그레이션 — Railway/Supabase 세션.
- Railway `/v1/calendar_events` 응답 스키마에 `status` / `recurring_event_id` / `original_start_at` 포함 — 별도 서버 변경.
- `CalendarEventDao` 에 series-level lookup 쿼리 (`WHERE recurring_event_id = :masterId`) — post-MVP.

---

## 8. Dependencies

- **Blocked by**:
  - PR #17 (`feat/db/commitment/due-at-hint-approximate`) — 3→4 migration. 본 PR 은 그 뒤 번호(4→5 또는 5→6) 를 사용해야 하므로 선형 의존.
  - PR #20 (존재한다면) — 마이그레이션 체인 선착순.
- **Blocks**:
  - `feat/ui/today` (`docs/plans/ui-today-counterparty-direction.md`) 의 strike-through 렌더 부분. counterparty / direction 부분은 본 PR 없이도 구현 가능하므로 병렬 가능하나, strike-through acceptance 는 status 컬럼이 필요.
  - TDY-001 완전 acceptance (status='confirmed' 필터 + cancelled strike-through).
- **병렬 가능**:
  - `fix/ui/today/since-kst` (`docs/plans/ui-today-since-kst.md`) — 파일 겹침 없음 (VM + Clock + since 파라미터만 수정). 본 PR 과 독립.
  - 다른 `feat/db/commitment/*` PR 들 — 같은 Migrations.kt 를 편집하지만 선형 체인이므로 merge 순서만 주의.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

Revert 후 device 는 schema vN+1 상태 — vN APK 다운그레이드 시 open 실패 (destructive fallback 미등록, `BeCalmDatabase.kt:100-104` 주석 참조). 따라서 rollback 은 **프로덕션 배포 전** 또는 **beta 사용자 전부 uninstall/reinstall 가능** 한 시점에만 안전.

코드 레벨 부분 롤백:
1. Entity 의 3 필드는 `default = "confirmed"` / `null` / `null` 이므로 유지해도 UI 가 무시 가능.
2. Worker 의 `toEntity` 매퍼에서 status 전달만 제거하면 모든 row 가 `'confirmed'` 로 유지됨.
3. `markCancelled` 호출 지점만 주석 처리 — DELETE 로 되돌리는 것은 **금지** (데이터 소실).

마이그레이션 자체는 되돌리지 않는다 (forward-only).

---

## Appendix — Session handoff notes

- **Schema version 번호는 merge 직전 구현자가 확정**. PR #17 머지 상태 + PR #20 존재 여부에 따라 4→5 또는 5→6. `BeCalmDatabase.DATABASE_VERSION` + `Migrations.kt` 의 `MIGRATION_N_(N+1)` 객체 이름 + `app/schemas/<N+1>.json` 세 곳이 모두 정합해야 함.
- **Room `@ColumnInfo(defaultValue = "'confirmed'")` 사용 시 `'confirmed'` 의 작은따옴표 포함 여부에 주의** — SQLite literal 이므로 따옴표 포함 필요. `defaultValue = "confirmed"` 는 Room validator 에서 schema mismatch 로 실패.
- **Google Calendar 의 cancelled 응답 형태 두 가지**:
  - 정규 이벤트 내 `status: "cancelled"` 필드 — 반복 이벤트의 단일 instance 취소 (override)
  - `deletedEvent: true` 마커 — 일반 이벤트 전체 삭제
  - 두 경우 모두 Android 쪽에서는 `status='cancelled'` 로 통합 처리. Railway 서버가 이미 통합해 준다고 가정하되, 구현자가 Railway 응답 스키마 한 번 확인 필요.
- **MS Graph `@removed` 는 page-level metadata** — `page.value` 요소 외부의 별도 필드. 현 `CalendarViewDeltaPage` 에 해당 필드가 없으면 신설 필요. 구현자가 `data/remote/msgraph/CalendarViewDeltaPage.kt` 확인 후 결정.
- **Upsert 전략 재확인**: `CalendarEventDao.insertAll` 이 `OnConflictStrategy.REPLACE` 이므로 `status` 가 포함된 DTO 가 들어오면 기존 row 를 덮어쓴다. 다만 **client-side 에서 이미 markCancelled 된 row 가 서버에서 `status='confirmed'` 로 다시 내려오면 덮어써질 위험** — 이는 정상(서버가 최종 truth). 문제 되지 않는다.
- **테스트 fixture**: `CalendarEventEntity` 를 만드는 모든 테스트 fixture 가 3 필드 default 값을 받도록 기본 생성자 default 유지. 폭발적 fixture 갱신 방지.
- **observeTodayConfirmed 구조 결정**: TDY-001 이 "cancelled 도 strike-through 로 함께 표시" 요구하므로, UI 측이 `observeForUser` (confirmed+cancelled 모두) 로 받고 렌더 시점에 분기하는 편이 단순. 별도 `observeTodayConfirmed` 는 post-MVP 최적화(예: widget) 로 미뤄도 됨. 구현자가 surgical 판단.
