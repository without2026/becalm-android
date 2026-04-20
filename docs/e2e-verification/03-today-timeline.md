# E2E Verification — `today-timeline` 모듈

Spec: `becalm-android/.spec/today-timeline.spec.yml`

> Invariant: TodayTimelineScreen 은 **Room 이 primary source**, Supabase 직접 호출 없음, 모든 서버 조회는 Railway 경유.

---

## 0. 전체 흐름

```
TodayTimelineScreen (Compose)
   ui/today/TodayTimelineScreen.kt
        │
        ▼
TodayViewModel (ui/today/TodayViewModel.kt:127)
   ├─ observe Commitment   → CommitmentRepository.observePendingForToday()
   ├─ observe CalendarEvent → CalendarEventRepository
   └─ observe SourceStatus → SourceStatusRepository
        │
        ▼
Room (Flow<List<Entity>>)
   ├─ CommitmentDao
   ├─ CalendarEventDao
   └─ RawIngestionEventDao

pull-to-refresh ── triggers ──▶ ForegroundCatchUpScheduler (worker/ForegroundCatchUpScheduler.kt:107)
   └─ 6 adapters in parallel → enqueueUniqueWork('sync-all-upload', REPLACE) (SYNC-006)
```

Refresh refs (API side, Railway):
- `RailwayApi.kt:94` `getCommitments` → TDY-004
- `RailwayApi.kt:139` `getCalendarEvents` → TDY-005

---

## TDY-001 — 통합 타임라인 (Commitment + CalendarEvent 시간순)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| UI | `ui/today/TodayTimelineScreen.kt` | `LazyColumn` — `TimelineItem` sealed |
| VM | `ui/today/TodayViewModel.kt:218` | `buildTimeline(...)` — give/take 배지 + person_ref 표시명 매핑 |
| Commitment → item | `TodayViewModel.kt:226` | `CommitmentEntity.toTimelineItem()` |
| Calendar → item | `TodayViewModel.kt:239` | `CalendarEventEntity.toTimelineItem()` |
| person_ref 표시 | `data/repository/PersonEnrichmentRepository.kt:105` | `observeByPersonRef` — LEFT JOIN 조회 |

**Verify**: `grep -n "toTimelineItem\|TimelineItem\." becalm-android/android/app/src/main/java/com/becalm/android/ui/today/TodayViewModel.kt`

---

## TDY-002 — 빈 상태

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| UI | `ui/today/TodayTimelineScreen.kt` + `ui/components/StateViews.kt` | `EmptyState` composable |
| VM | `TodayViewModel.kt:100` | `TodayUiState.items.isEmpty()` |

---

## TDY-003 — SourceStatusStrip (6 chips, read-only)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| UI | `ui/components/SourceStatusIndicator.kt` | 칩 렌더 — 탭 콜백 없음 |
| VM | `TodayViewModel.kt:84` | `SourceStatusUi` data class |
| Repo | `data/repository/SourceStatusRepository.kt` | 6개 소스 상태 관찰 (idle/syncing/error/synced+HH:mm) |

**Verify**: `grep -n "clickable\|onClick" becalm-android/android/app/src/main/java/com/becalm/android/ui/components/SourceStatusIndicator.kt` → 칩에 클릭 핸들러 **없어야** 한다.

---

## TDY-004 — GET /v1/commitments (Railway)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| API | `data/remote/api/RailwayApi.kt:94` | `getCommitments(since, direction?, cursor?)` |
| Repo | `data/repository/CommitmentRepositoryImpl.kt:60` | `refreshSince(userId, since)` → DTO → Room UPSERT |
| DTO → entity | `CommitmentRepositoryImpl.kt:298` | `CommitmentDto.toEntity(userId)` |

**Verify**: `grep -n "getCommitments" becalm-android/android/app/src/main/java/com/becalm/android/data/remote/api/RailwayApi.kt`

---

## TDY-005 — GET /v1/calendar_events

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| API | `RailwayApi.kt:139` | `getCalendarEvents(since, cursor?)` |
| Repo | `data/repository/CalendarEventRepository.kt` | cache refresh |

---

## TDY-006 — pull-to-refresh (local)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| UI gesture | `ui/today/TodayTimelineScreen.kt` | Material `PullToRefresh` |
| VM reload | `TodayViewModel.kt:127` | 관찰 Flow 재구독 + refresh 트리거 |

---

## TDY-007 — 우상단 설정 아이콘 → SettingsScreen (드로어 없음)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| UI | `ui/today/TodayTimelineScreen.kt` | TopBar icon |
| Nav | `ui/navigation/BecalmNavHost.kt:186` | `BecalmRoute.Settings` |

**Verify — invariant**: `grep -rn "ModalDrawer\|NavigationDrawer" becalm-android/android/app/src/main/java/com/becalm/android/ui/today` → **히트 0건**.

---

## TDY-008 — OverallSyncIndicator (집계)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| UI | `ui/components/SourceStatusIndicator.kt` | aggregated chip |
| Repo | `SourceStatusRepository.kt` | 6개 소스 `min(last_sync_at)` 집계 |

---

## TDY-009 — pull-to-refresh triggers ING-011 + SYNC-006

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Trigger | `worker/ForegroundCatchUpScheduler.kt:107` | `start()` — `onStart(owner)` lifecycle hook |
| Parallel fan-out | `ForegroundCatchUpScheduler.kt:177` | `enqueueForSources(sources)` |
| Immediate upload | `worker/UploadWorker.kt:54` + `worker/UniqueWorkKeys.kt:11` | `UniqueWorkKeys.UPLOAD` → `enqueueUniqueWork('sync-all-upload', REPLACE)` |

**Verify**: `grep -n "enqueueUniqueWork\|REPLACE" becalm-android/android/app/src/main/java/com/becalm/android/worker/ForegroundCatchUpScheduler.kt`

---

## TDY-010 — ColdSyncScreen (최초 1회만)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Screen | `ui/onboarding/ColdSyncScreen.kt` | full-screen loader |
| VM | `ui/today/ColdSyncViewModel.kt:49` | progress 집계 (0/6 ~ 6/6) |
| Gate | `UserPrefsStore.observeOnboardingCompleted` + Room 3테이블 counting | `RawIngestionEventDao.kt:179` + CommitmentDao + CalendarEventDao |

**Verify invariant**:
- [ ] ColdSync 진입 조건이 **onboarding_completed==false AND Room 전 테이블 0건** 인지. 둘 중 하나만 맞아서 ColdSync 가 재표시되면 invariant 위반.

---

## Tests

| 파일 | 커버 |
| --- | --- |
| `android/app/src/test/.../ui/today/TodayViewModelTest.kt` | TDY-001/002/006 |
| `android/app/src/test/.../ui/today/ColdSyncViewModelTest.kt` | TDY-010 |
| (없음) | TDY-003/007/008 — UI-only behavior, Compose test 추가 필요 |
