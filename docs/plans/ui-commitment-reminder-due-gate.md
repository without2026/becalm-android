# UI / Commitment / Reminder Due Gate — CMT-005 + CMT-008 alarm realignment

**Branch**: `feat/ui/commitment` (umbrella)
**Status**: IMPLEMENTED — Wave 4 commit C5.
**E2E Stage**: 5 — Commitment Management
**Severity**: High
**Type**: Drift

---

## 1. Finding

`ReminderScheduler` currently schedules an alarm at **09:00 KST of the due day** (legacy "scheduled time" semantics). Spec CMT-005 requires **`due_at − 1h`** via `setExactAndAllowWhileIdle`, `FLAG_IMMUTABLE`, with skip conditions (`due_at == null || triggerAt <= now()`). Spec CMT-008 additionally requires the `AlarmReceiver.onReceive` to re-query Room and silently drop if `action_state ∈ (completed, cancelled)` to prevent ghost reminders, and to use a dedicated notification channel `commitment_due_soon` with IMPORTANCE_HIGH, title `곧 마감되는 약속 (1시간 뒤)`, deep link to `CommitmentDetailSheet`.

---

## 2. Spec Contract

- **`.spec/commitment-management.spec.yml:47-55`** — CMT-005:
  > "[리마인드] → `action_state='reminded'` + PATCH `/v1/commitments/{id}` + `AlarmManager.setExactAndAllowWhileIdle` at `due_at − 1h` with `PendingIntent.FLAG_IMMUTABLE`. `due_at` null 또는 과거이면 알람 skip."

- **`.spec/commitment-management.spec.yml:77-85`** — CMT-008:
  > "알람 발사 시점 (`due_at − 1h`) → `CommitmentAlarmReceiver.onReceive`: Room 을 **재질의** → `action_state ∈ (completed, cancelled)` 이면 silent drop (notify 안함). 그 외엔 notification channel `commitment_due_soon` (IMPORTANCE_HIGH), title `곧 마감되는 약속 (1시간 뒤)`, body `[내가 한/상대가 한] {title}`, tap → deep link `becalm://commitments/{id}` → CommitmentDetailSheet 열림."

- **`.spec/commitment-management.spec.yml:138-140`** (invariant):
  > "MUST-NOT: completed / cancelled commitment 에 대해 알람 notify."

---

## 3. Code Reality

- **`android/app/src/main/java/com/becalm/android/worker/reminder/ReminderScheduler.kt:~49`** — schedules `at = LocalDateTime(dueDate, LocalTime(9,0)).toInstant(KST)`. Legacy 09:00 logic.
- **`android/app/src/main/java/com/becalm/android/worker/reminder/ReminderBroadcastReceiver.kt`** — (if exists) does not re-query Room; notifies unconditionally.
- **`android/app/src/main/java/com/becalm/android/BecalmApp.kt`** — notification channel registration. `commitment_due_soon` channel 존재 여부 확인 필요.
- **`android/app/src/main/AndroidManifest.xml`** — no `<data android:scheme="becalm" android:host="commitments" />` deep-link filter currently.

```bash
grep -rn "commitment_due_soon\|setExactAndAllowWhileIdle" android/app/src/main/
grep -rn "FLAG_IMMUTABLE" android/app/src/main/java/com/becalm/android/worker/reminder/
```

---

## 4. Gap

| 측면 | Spec 요구 | Code 현실 | 차이 |
|---|---|---|---|
| 트리거 시각 | `due_at − 1h` | 09:00 KST | 계산 로직 교체 |
| Skip 조건 | `due_at==null‖과거` | 없음 / 부분 | guard 추가 |
| PendingIntent flag | FLAG_IMMUTABLE | 미확인 | 강제 설정 |
| Receiver 재질의 | 상태 체크 후 drop | 없음 | Receiver 재구현 |
| Channel | `commitment_due_soon` HIGH | 미확인 | 신규 등록 |
| Notification copy | `곧 마감되는 약속 (1시간 뒤)` | 다른 문구 | strings.xml |
| Deep link | `becalm://commitments/{id}` | 없음 | intent filter + PendingIntent |

---

## 5. Proposed Fix

### 5.1 Files to change

- `android/app/src/main/java/com/becalm/android/worker/reminder/ReminderScheduler.kt`
  — replace `schedule(commitmentId, dueDate)` with `schedule(commitmentId, dueAt: Instant?)`. Compute `triggerAt = dueAt.minus(1.hours)`. Early-return if `dueAt == null || triggerAt <= Clock.System.now()`. Use `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, triggerMillis, pendingIntent)` with `PendingIntent.getBroadcast(..., PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)`. Keep `cancel(commitmentId)` behavior unchanged.
- `android/app/src/main/java/com/becalm/android/worker/reminder/ReminderBroadcastReceiver.kt` (add if missing; rewrite if present)
  — Hilt `@AndroidEntryPoint`. In `onReceive`: read id from intent; launch `goAsync` coroutine; query `commitmentDao.findById(id)`; if null → log+return. If `actionState in {completed, cancelled}` → log "silent drop" + return. Else: build notification on channel `commitment_due_soon`, title `R.string.commitment_alarm_title`, body formatted from `R.string.commitment_alarm_body_give/take` based on direction, content intent = `PendingIntent.getActivity(..., Intent(ACTION_VIEW, Uri.parse("becalm://commitments/$id")), FLAG_IMMUTABLE)`.
- `android/app/src/main/java/com/becalm/android/BecalmApp.kt`
  — register notification channel `commitment_due_soon` (NotificationManager.IMPORTANCE_HIGH) in `onCreate` `NotificationChannel(...)` block (alongside existing channels).
- `android/app/src/main/AndroidManifest.xml`
  — add `<intent-filter android:autoVerify="false"><action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.DEFAULT"/><category android:name="android.intent.category.BROWSABLE"/><data android:scheme="becalm" android:host="commitments"/></intent-filter>` to `MainActivity`.
- `android/app/src/main/java/com/becalm/android/MainActivity.kt` (or NavHost entry)
  — handle incoming `Intent.data` with scheme `becalm`; parse `commitments/{id}`; `navController.navigate(BecalmRoute.CommitmentDetail(id))`.
- `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt`
  — `onRemind(id)` handler: fetch entity, `repo.transitionState(id, Remind)`, then `ReminderScheduler.schedule(id, entity.dueAt)`. Replace legacy `onSchedule(Instant)` call-site.

### 5.2 Files to add

- `android/app/src/test/java/com/becalm/android/worker/reminder/ReminderSchedulerTest.kt`
  — fixed `Clock` (e.g., `TestClock` with `2026-04-22T10:00+09:00`). Cases: (a) `dueAt=null` → no alarm; (b) `dueAt` past → no alarm; (c) `dueAt` future → `setExactAndAllowWhileIdle` called with `triggerAt = dueAt − 1h`, FLAG_IMMUTABLE on PendingIntent. MockK `AlarmManager`.
- `android/app/src/test/java/com/becalm/android/worker/reminder/ReminderBroadcastReceiverTest.kt`
  — Robolectric or MockK. Cases: (a) state=pending → `notify(...)` 호출; (b) state=completed → `notify` 미호출; (c) state=cancelled → `notify` 미호출; (d) entity=null → `notify` 미호출.

### 5.3 Files to delete
- Legacy `SchedulingWorker` / `09:00` constant (if exists) after grep confirms no callers.

### 5.4 Non-code changes
- `strings.xml`:
  - `commitment_alarm_title = "곧 마감되는 약속 (1시간 뒤)"`
  - `commitment_alarm_body_give = "내가 한 약속: %1$s"`
  - `commitment_alarm_body_take = "상대가 한 약속: %1$s"`
  - `commitment_channel_due_soon_name = "곧 마감 리마인더"`
  - `commitment_channel_due_soon_desc = "약속 마감 1시간 전 알림"`
- `AndroidManifest.xml` deep-link filter (see 5.1).

---

## 6. Acceptance Criteria

- [ ] **Grep — no legacy 09:00**: `grep -rn "LocalTime(9" android/app/src/main/java/com/becalm/android/worker/reminder/ | wc -l` == 0.
- [ ] **Grep — FLAG_IMMUTABLE**: `grep -rn "FLAG_IMMUTABLE" android/app/src/main/java/com/becalm/android/worker/reminder/` ≥ 2.
- [ ] **Grep — channel**: `grep -rn "commitment_due_soon" android/app/src/main/` ≥ 2 (channel create + notification use).
- [ ] **Unit — scheduler**: `ReminderSchedulerTest` 3+ 케이스 통과.
- [ ] **Unit — receiver**: `ReminderBroadcastReceiverTest` silent-drop 케이스 통과.
- [ ] **Manifest**: deep-link filter `becalm://commitments` 존재.
- [ ] **Manual**: `dueAt=now+2h` commitment 에서 [리마인드] → 알람 1시간 뒤 예약 확인 (adb dumpsys alarm).

---

## 7. Out of Scope

- `commitment_due_today` (법률·DB 가 아닌 inbox-style 알림) — 존재한다면 건드리지 않음.
- 알람 정확도 옵션 (`SCHEDULE_EXACT_ALARM` 권한 prompt flow) — 이미 도입된 걸 재사용. 신규 요청 flow 는 별도 plan.
- Notification 액션 버튼 (e.g. "완료" 직접 노티에서) — 스펙에 없음.

---

## 8. Dependencies

- **Blocked by**: C2 `ui-commitment-action-state-alignment` (새 `Remind` event 필요); C4 `ui-commitment-detail-sheet` (deep link 대상 route 필요); DB Wave 1 #17 `due_at` 컬럼 (이미 머지됨).
- **Blocks**: 없음 (알람 채널 등록은 onCreate 1회).

---

## 9. Rollback plan

- Revert commit. Alarm 이미 예약된 것들은 앱 재시작 시 reschedule 되는 메커니즘 유지 (만약 없다면 이 commit 에 `BecalmApp.onCreate` 에서 `rescheduleAllOnBoot()` 추가 고려).

---

## Appendix — Session handoff notes

- `setExactAndAllowWhileIdle` 는 Android 12+ 에서 `SCHEDULE_EXACT_ALARM` 권한 필요. PIPA 앱이므로 이미 선언되어 있어야 함. 만약 없다면 receiver 가 `canScheduleExactAlarms()` fallback 으로 `setWindow` 로 degrade.
- Deep link scheme `becalm://` 는 hardcoded. 테스트 용 link: `adb shell am start -a android.intent.action.VIEW -d "becalm://commitments/abc123"`.
- Notification body 의 `직접 말한 / 상대가 말한` 용어는 `direction` 필드 기반. `direction='give'` → 내가 한 / `'take'` → 상대가 한.
- Receiver 에서 `goAsync` 타임아웃 10초 안에 Room 질의 + 알림 표시 완료해야 함 (Android 정책). DB 질의가 I/O 에 묶이지 않도록 반드시 `Dispatchers.IO` 사용.
