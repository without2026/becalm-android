# E2E Verification — `commitment-management` 모듈

Spec: `becalm-android/.spec/commitment-management.spec.yml`

---

## 0. 전체 흐름

```
CommitmentManagementScreen (ui/commitments/CommitmentManagementScreen.kt)
        │  filter tabs, CommitmentCard, [리마인드]/[팔로업]/[완료]
        ▼
CommitmentManagementViewModel (ui/commitments/CommitmentManagementViewModel.kt:112)
        │  onConfirm / onSchedule / onMarkDone / onDismiss / onFilterChange
        ▼
CommitmentRepository (data/repository/CommitmentRepository.kt / Impl.kt:40)
   ├─ observeAllForUser / observePendingForToday / observeAllForPerson  (Room)
   ├─ transitionState (L119)   → domain/commitment/CommitmentStateMachine.kt
   ├─ updateActionState (L136) → Room UPDATE + RailwayApi.patchCommitment (optimistic)
   └─ refreshSince (L60)       → GET /v1/commitments (RailwayApi.kt:94)

Domain state machine:
   domain/commitment/CommitmentStateMachine.kt — pending → reminded → followed_up → completed
   domain/commitment/CommitmentEvent.kt / CommitmentState.kt / TransitionError.kt

Reminder scheduling:
   domain/reminder/ReminderScheduler.kt:31 — AlarmManager + PendingIntent
   receiver/ReminderBroadcastReceiver.kt   — alarm 수신 → Push notification
```

Card composable: `ui/components/CommitmentCard.kt`

---

## CMT-001 — 전체 목록 + direction 배지 + quote/title/person_ref/due_date/action_state

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Screen | `ui/commitments/CommitmentManagementScreen.kt` | `CommitmentFilterTabs` + `LazyColumn<CommitmentCard>` |
| VM | `CommitmentManagementViewModel.kt:145` | `observeCommitments()` |
| Rows | `CommitmentManagementViewModel.kt:63` | `CommitmentRow` data class |
| Repo | `CommitmentRepositoryImpl.kt:49` | `observeAllForUser(userId)` |
| person_ref 표시명 | `PersonEnrichmentRepository.kt:105` | LEFT JOIN (Room 쿼리 또는 VM side-merge) |

---

## CMT-002 — 탭 필터 전체/내가한/상대가한

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Enum | `CommitmentManagementViewModel.kt:39` | `CommitmentFilter.ALL / GIVE / TAKE` |
| VM | `CommitmentManagementViewModel.kt:188` | `onFilterChange(filter)` |
| Apply | `CommitmentManagementViewModel.kt:305` | `applyFilter(...)` |

---

## CMT-003 — CommitmentDetailSheet (quote 전문 + 출처)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Sheet | `ui/commitments/CommitmentManagementScreen.kt` | BottomSheet composable |
| Entity | `data/local/db/entity/CommitmentEntity.kt` | `quote`, `source_event_title`, `source_event_occurred_at` |

---

## CMT-004 — D-N 배지 (Clock 기준)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Computation | `ui/components/CommitmentCard.kt` | due_date 비교 helper |
| Clock | `core/util/Clock.kt` | inject Clock — 테스트 가능 |

---

## CMT-005 — [리마인드] → action_state='reminded' + AlarmManager 예약

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| VM | `CommitmentManagementViewModel.kt:219` | `onSchedule(id, at)` |
| Repo | `CommitmentRepositoryImpl.kt:119` | `transitionState(id, event)` → state machine |
| State machine | `domain/commitment/CommitmentStateMachine.kt` + test `domain/commitment/CommitmentStateMachineTest.kt` |
| Repo update | `CommitmentRepositoryImpl.kt:136` | `updateActionState(id, 'reminded')` → Room UPDATE + `RailwayApi.patchCommitment` |
| Alarm | `domain/reminder/ReminderScheduler.kt:49` | `schedule(commitmentId, triggerAt)` |
| Test | `domain/reminder/ReminderSchedulerTest.kt` | 스케줄 취소/요청코드 |

**Verify**:
```
grep -n "patchCommitment" becalm-android/android/app/src/main/java/com/becalm/android/data/remote/api/RailwayApi.kt
```
(L119 — `patchCommitment`)

---

## CMT-006 — [팔로업] → 'followed_up'

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| VM | `CommitmentManagementViewModel.kt:203` | `onConfirm(id)` (state machine 로 followed_up 이벤트) |
| State machine | `CommitmentStateMachine.kt` | transition pending/reminded → followed_up |

(`onConfirm` / `onSchedule` / `onMarkDone` 의 매핑은 state machine event 에 따름. spec 매핑이 헷갈릴 수 있으므로 CTO 는 VM 내 `launchAction` 의 event 선택부 확인.)

---

## CMT-007 — [완료] → 'completed' + 섹션 이동

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| VM | `CommitmentManagementViewModel.kt:237` | `onMarkDone(id)` |
| Repo | `CommitmentRepositoryImpl.kt:119` | `transitionState(id, CommitmentEvent.Complete)` |
| Reminder cancel | `ReminderScheduler.kt:78` | `cancel(commitmentId)` (invariant: completed 에 알림 금지) |

---

## CMT-008 — AlarmManager push notification (due_date 당일 09:00)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Scheduler | `ReminderScheduler.kt:49` | `schedule(commitmentId, triggerAt)` |
| Receiver | `receiver/ReminderBroadcastReceiver.kt` | `onReceive` → NotificationManager |
| Gate (completed skip) | `CommitmentRepositoryImpl.kt:119` | completed 전이 시 `cancel(id)` 호출 존재 확인 |

**Verify invariant**:
```
grep -n "cancel(" becalm-android/android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepositoryImpl.kt
```

---

## CMT-009 — '이행 완료' 섹션 접힘/펼침

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| UI | `ui/commitments/CommitmentManagementScreen.kt` | ExpandableSection / `LazyColumn` header |

---

## CMT-010 — pull-to-refresh → GET /v1/commitments

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| UI | Material PullToRefresh | in screen |
| Repo | `CommitmentRepositoryImpl.kt:60` | `refreshSince(userId, since)` |
| API | `RailwayApi.kt:94` | `getCommitments(since, direction?, cursor?)` |

---

## Invariants

| Invariant | 검증 |
| --- | --- |
| Room primary source | VM observe 경로가 모두 `CommitmentRepository.observe*` → Flow<RoomEntity> |
| Optimistic update + async PATCH | `updateActionState` 는 Room UPDATE 선행 → PATCH 실패 시 sync_status='pending' 큐 재진입 (`CommitmentRepositoryImpl.kt:136`) |
| completed 알림 금지 | `ReminderScheduler.cancel(id)` 가 completed 전이 시 호출됨 |
| quote 원문 편집 금지 | UI 에서 `TextOverflow.Ellipsis` 사용 OK, 하지만 `TextField` 편집 컴포넌트 없어야 함 |

---

## Tests

| 파일 | 커버 |
| --- | --- |
| `ui/commitments/CommitmentManagementViewModelTest.kt` | CMT-001/002/005/006/007 |
| `domain/commitment/CommitmentStateMachineTest.kt` | 상태 전이 invariant |
| `domain/reminder/ReminderSchedulerTest.kt` | CMT-005/008 schedule/cancel |
