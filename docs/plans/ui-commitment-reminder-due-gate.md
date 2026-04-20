# UI / Commitment / reminder-due-gate — 리마인더 게이트 / 채널 / 본문 / silent drop 스펙 drift

**Branch**: `feat/ui/commitment`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 plan doc 이외의 코드 커밋 금지.
**E2E Stage**: 5 — CommitmentDetailSheet 액션 + AlarmManager 런타임
**Severity**: High (스펙의 알람 시점·채널·본문·silent drop 4가지 모두 drift. 사용자는 "마감 1시간 전" 알람이 아니라 잘못된 시점에 리마인더를 받고, direction 배지 없는 generic 본문을 보며, 이미 완료/취소한 약속에도 알림이 울린다.)
**Type**: Drift (CMT-005 / CMT-008 요구가 4가지 축에서 코드와 불일치)

---

## 1. Finding

현재 `ReminderScheduler.schedule(commitmentId, triggerAt)` 은 **호출자가 넘긴 `at` 시각 그대로** 알람을 등록한다 (`ReminderScheduler.kt:49`). CMT-005 는 "due_at - 1h" 게이트를 명시하지만 이 계산은 어디에도 없고, 호출 지점 `CommitmentManagementViewModel.onSchedule` 은 외부에서 `at: Instant` 를 받아 전달만 한다. 또한:

1. **채널 ID/Importance drift** — 현재 `CHANNEL_ID="reminders"`, `IMPORTANCE_DEFAULT` (`ReminderBroadcastReceiver.kt:88`, `:100`). 스펙은 `"commitment_due_soon"`, `IMPORTANCE_HIGH`.
2. **Notification 본문 drift** — 현재 title `"BeCalm 약속 알림"`, body `"확인할 약속이 있습니다."` generic (`ReminderBroadcastReceiver.kt:102-103`). 스펙은 title `"곧 마감되는 약속 (1시간 뒤)"`, body `"[내가 한/상대가 한] {commitment.title}"`.
3. **Silent drop 미구현** — `AlarmReceiver.onReceive` 는 commitment_id 만 받고 Room 재조회 없이 항상 notify (`ReminderBroadcastReceiver.kt:36-77`). 스펙은 "AlarmReceiver가 최신 action_state를 Room에서 재조회 → completed/cancelled 면 silent drop".
4. **due_at - 1h 게이트 미구현** — CMT-005 의 "due_at != null && due_at - 1h > now()" 조건문이 없음. 호출자가 임의 `at` 전달.

---

## 2. Spec Contract (무엇이어야 하는가)

- **`.spec/commitment-management.spec.yml:47-54` CMT-005**:
  > "[리마인드] 버튼 탭 시 본인(사용자)에게 마감 1시간 전 local push 알림을 예약한다. action_state를 'reminded'로 변경하고 Railway PATCH /v1/commitments/{id}를 호출한다. AlarmManager는 due_at - 1h 시점에 알람을 등록한다 — 당일 오전 9시 일괄 발송이 아니라 마감 임박 시점 기반. 마감이 이미 지났거나 due_at이 null이면 AlarmManager 등록 없이 action_state만 변경"
  > expected: "due_at != null && due_at - 1h > now() 인 경우 AlarmManager에 setExactAndAllowWhileIdle(alarm_time = due_at - 1h, PendingIntent.FLAG_IMMUTABLE) 등록됨. due_at이 이미 과거거나 null이면 alarm 등록 생략"

- **`.spec/commitment-management.spec.yml:77-83` CMT-008**:
  > "AlarmManager 알림 — CMT-005에서 예약된 alarm이 due_at - 1h 시점에 local push notification을 발송한다. action_state='completed'|'cancelled'인 약속은 사용자가 이미 처리/취소한 것이므로 알람 시각에 도달했더라도 notification 발송 없이 silent drop"
  > expected: "AlarmReceiver가 최신 action_state를 Room에서 재조회 → pending/reminded/followed_up 중 하나면 notification 발송, completed/cancelled면 silent drop. 발송 시 title: '곧 마감되는 약속 (1시간 뒤)', body: '[내가 한/상대가 한] {commitment.title}' — direction 배지는 실제 direction 값으로 치환. 탭 시 CommitmentDetailSheet(id={commitment.id})로 딥링크됨. Notification channel: 'commitment_due_soon' (IMPORTANCE_HIGH)"

- **`.spec/commitment-management.spec.yml:139` invariant**:
  > "AlarmManager 알림은 action_state IN ('completed','cancelled') 약속에 대해 발송하지 않는다 (AlarmReceiver가 최신 action_state 재조회 후 silent drop)"

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `android/app/src/main/java/com/becalm/android/domain/reminder/ReminderScheduler.kt:49-70`
```kotlin
public fun schedule(commitmentId: String, triggerAt: Instant) {
    val requestCode = commitmentIdToRequestCode(commitmentId)
    val pi = buildPendingIntent(commitmentId, requestCode)
    val triggerMs = triggerAt.toEpochMilliseconds()

    val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        alarmManager.canScheduleExactAlarms()

    if (canExact) {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
    } else {
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
    }
}
```
→ due_at - 1h 계산, null 체크, past-due 게이트 모두 부재. `triggerAt` 을 호출자가 책임지게 하되 호출자 어디에도 게이트가 없음.

### 3.2 `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt:241-251`
```kotlin
public fun onSchedule(id: String, at: Instant) {
    launchAction(
        name = "onSchedule",
        id = id,
        effect = {
            reminderScheduler.schedule(id, at)
        },
    ) {
        commitmentRepository.transitionState(id, CommitmentEvent.Schedule(at))
    }
}
```
→ `at: Instant` 는 호출자가 넘기는 값. due_at 을 자동으로 "-1h" 로 계산하는 곳 없음. UI (아직 구현 전이지만 향후 [리마인드] 핸들러) 가 잘못된 시각을 넘기면 그대로 등록됨.

### 3.3 `android/app/src/main/java/com/becalm/android/receiver/ReminderBroadcastReceiver.kt:67-77`
```kotlin
val notification = NotificationCompat.Builder(context, CHANNEL_ID)
    .setSmallIcon(android.R.drawable.ic_dialog_info)
    .setContentTitle(NOTIFICATION_TITLE)
    .setContentText(NOTIFICATION_BODY)
    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    .setAutoCancel(true)
    .setContentIntent(tapPendingIntent)
    .build()

NotificationManagerCompat.from(context).notify(notificationId, notification)
```
→ Room 재조회 없이 항상 notify. action_state 를 본 적이 없음.

### 3.4 `android/app/src/main/java/com/becalm/android/receiver/ReminderBroadcastReceiver.kt:85-90`
```kotlin
val channel = NotificationChannel(
    CHANNEL_ID,
    CHANNEL_NAME,
    NotificationManager.IMPORTANCE_DEFAULT,
)
```
→ IMPORTANCE_DEFAULT.

### 3.5 `android/app/src/main/java/com/becalm/android/receiver/ReminderBroadcastReceiver.kt:100-103`
```kotlin
private const val CHANNEL_ID = "reminders"
private const val CHANNEL_NAME = "리마인더"
private const val NOTIFICATION_TITLE = "BeCalm 약속 알림"
private const val NOTIFICATION_BODY = "확인할 약속이 있습니다."
```
→ 4개 상수 모두 drift.

검증 grep:
```bash
grep -n "due_at - 1h\|due_at.minus\|minus(ONE_HOUR)\|Duration.ofHours(1)" \
  android/app/src/main/java/com/becalm/android/domain/reminder/
# → empty

grep -rn "IMPORTANCE_HIGH\|commitment_due_soon" android/app/src/main/java/
# → empty

grep -rn "action_state\|actionState" \
  android/app/src/main/java/com/becalm/android/receiver/ReminderBroadcastReceiver.kt
# → empty (Receiver 는 Room 을 전혀 읽지 않음)
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| 게이트 | `due_at != null && due_at - 1h > now()` | 무조건 호출자의 `at` 등록 | Scheduler 에서 게이트 내재화 or Handler 에서 계산 |
| 알람 시각 | `due_at - 1h` | 호출자 `at` | ViewModel / Sheet 에서 `due_at.minus(1.hours)` |
| Channel ID | `commitment_due_soon` | `reminders` | 상수 교체 + 기존 채널 마이그레이션 (재생성) |
| Importance | `IMPORTANCE_HIGH` | `IMPORTANCE_DEFAULT` | enum 교체 |
| Title | `곧 마감되는 약속 (1시간 뒤)` | `BeCalm 약속 알림` | 문자열 교체 |
| Body | `[내가 한/상대가 한] {title}` | `확인할 약속이 있습니다.` | direction + title 파라미터화 |
| Silent drop | Room 재조회 후 `completed`/`cancelled` 이면 notify 생략 | 항상 notify | Receiver 에서 DAO 조회 추가 |
| Tap 딥링크 | `CommitmentDetailSheet(id={commitment.id})` | MainActivity 로만 진입 | deeplink/extra 처리 (Sheet 라우팅) |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/domain/reminder/ReminderScheduler.kt`**
   - `schedule(commitmentId: String, triggerAt: Instant)` 시그니처는 유지하되, 호출자가 `due_at - 1h` 를 이미 계산해서 넘겼다는 invariant 를 KDoc 에 명시.
   - 추가 고려: 차라리 `schedule(commitmentId: String, dueAt: Instant)` 로 바꾸어 scheduler 내부에서 `dueAt.minus(1.hours)` 를 계산하고 `dueAt <= now()` 면 등록 생략 — 이 접근이 호출자에게 더 안전. 구현 세션에서 최종 결정.
   - 기존 `setExactAndAllowWhileIdle` 경로 유지. 게이트 추가만.

2. **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentDetailViewModel.kt`** (`ui-commitment-detail-sheet.md` 가 먼저 만드는 VM)
   - `onRemind()` 핸들러 추가: Room 에서 `commitment.dueAt` 읽기 → null 이거나 `dueAt - 1h <= now()` 면 action_state 전이만 실행 + Scheduler 호출 생략. 그 외에는 `reminderScheduler.schedule(id, dueAt - 1h)`.
   - 기존 `CommitmentManagementViewModel.onSchedule(id, at)` 는 list-level 책임이 아니라 detail sheet 책임이므로 **본 plan 에서 제거 대상** — but Surgical Changes 원칙에 따라 "dead" 여부는 다른 호출처 확인 후 결정. 현재 `CommitmentManagementScreen` 에서 호출 없음 (아직 UI 미구현) → list VM 의 `onSchedule` 은 보고만 하고 별도 plan 에서 제거.

3. **`android/app/src/main/java/com/becalm/android/receiver/ReminderBroadcastReceiver.kt`**
   - `CHANNEL_ID = "commitment_due_soon"`, `CHANNEL_NAME = "곧 마감되는 약속"`, `IMPORTANCE_HIGH`.
   - **Silent drop 로직**: `onReceive` 에서 `commitmentId` 로 Room 재조회 → `action_state ∈ {"completed", "cancelled"}` 이면 `Log.i` 후 return (notify 생략). Hilt 주입으로 `CommitmentDao` 또는 `CommitmentRepository` 제공 (`@AndroidEntryPoint` 이미 선언됨, `EntryPointAccessors` 활용).
   - **본문 파라미터화**: Room 에서 읽은 `direction`, `title` 로 body 구성. direction 매핑: `"give"` → `"내가 한"`, `"take"` → `"상대가 한"`. title 은 verbatim.
   - PIPA note: 기존 KDoc 은 "Notification content is intentionally generic — the body never contains commitment text, names, or any personally identifiable information." 라고 되어 있음. 본 변경으로 title 이 notification body 에 포함됨 — 이는 스펙 요구이므로 KDoc 을 "local-only device notification 으로 PIPA 3자 제공에 해당하지 않음" 논거로 업데이트.
   - Tap PendingIntent: `MainActivity` 로 가면서 `EXTRA_COMMITMENT_ID` 를 그대로 전달 — MainActivity 가 Commitments 탭 + detail sheet open 상태로 네비게이트하는 로직은 `BecalmNavHost` / `MainActivity` 에 기존 intent 파싱 여부 확인 후 본 PR 에서 최소 추가.

4. **`android/app/src/main/java/com/becalm/android/MainActivity.kt`** (필요 시)
   - `intent.getStringExtra(ReminderBroadcastReceiver.EXTRA_COMMITMENT_ID)` 처리 — 이미 존재하면 no-op, 없으면 시작 시 deep-link state 로 저장 후 `CommitmentManagementScreen` 에 전달.

### 5.2 Files to add
- **테스트 파일**: `android/app/src/test/java/com/becalm/android/receiver/ReminderBroadcastReceiverTest.kt` — silent drop Unit test (Robolectric 또는 instrumentation).
- **테스트 파일**: `android/app/src/test/java/com/becalm/android/domain/reminder/ReminderSchedulerGateTest.kt` — due_at null / past / future 게이트.

### 5.3 Files to delete (dead code)
- `CommitmentManagementViewModel.onSchedule` 는 **이 PR 에서는 제거하지 않음**. CTO 의 "surgical changes" 원칙상 다른 호출처 검증이 필요. 보고만 하고 후속 refactor plan 에서 다룸.

### 5.4 Non-code changes
- `strings.xml` — title/body/channel name 한국어 리소스 추가 (`notif_commitment_due_soon_title`, `notif_commitment_due_soon_body_give`, `notif_commitment_due_soon_body_take`, `notif_channel_commitment_due_soon_name`).
- `AndroidManifest.xml` — 이미 `ReminderBroadcastReceiver` 선언되어 있음 (확인 필요). `POST_NOTIFICATIONS` 퍼미션도 이미 존재 (Receiver 가 체크함).
- Channel 변경 시 **기존 "reminders" 채널은 `NotificationManager.deleteNotificationChannel("reminders")`** 로 정리 — 이미 배포된 기기가 없다고 가정하지만 idempotent 삭제는 안전.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "commitment_due_soon" android/app/src/main/java/ | wc -l` ≥ 2
- [ ] **Grep invariant**: `grep -rn "\"reminders\"" android/app/src/main/java/com/becalm/android/receiver/ | wc -l` = 0
- [ ] **Grep invariant**: `grep -n "IMPORTANCE_HIGH" android/app/src/main/java/com/becalm/android/receiver/ReminderBroadcastReceiver.kt | wc -l` ≥ 1
- [ ] **Grep invariant**: `grep -n "IMPORTANCE_DEFAULT" android/app/src/main/java/com/becalm/android/receiver/ReminderBroadcastReceiver.kt | wc -l` = 0
- [ ] **Grep invariant**: `grep -n "action_state\|actionState" android/app/src/main/java/com/becalm/android/receiver/ReminderBroadcastReceiver.kt | wc -l` ≥ 1 (Room 재조회 확인)
- [ ] **Unit test**: `ReminderSchedulerGateTest — due_at == null 이면 AlarmManager.set*  호출 0회`
- [ ] **Unit test**: `ReminderSchedulerGateTest — due_at - 1h <= now() 이면 set* 호출 0회`
- [ ] **Unit test**: `ReminderSchedulerGateTest — due_at - 1h > now() 이면 setExactAndAllowWhileIdle 호출 정확히 1회, trigger 값 == due_at - 1h`
- [ ] **Unit test**: `ReminderBroadcastReceiverTest — action_state="completed" 이면 NotificationManager.notify 호출 0회`
- [ ] **Unit test**: `ReminderBroadcastReceiverTest — action_state="cancelled" 이면 notify 호출 0회`
- [ ] **Unit test**: `ReminderBroadcastReceiverTest — action_state="reminded" 이면 notify 1회, title="곧 마감되는 약속 (1시간 뒤)", body 에 direction 라벨 + title 포함`
- [ ] **Manual**: 채널 ID 가 `commitment_due_soon` 로 시스템 설정 > 앱 > 알림 에서 표시

---

## 7. Out of Scope

- CommitmentDetailSheet 본체 / [리마인드] 버튼 UI 배치 — `ui-commitment-detail-sheet.md`
- [취소] 시 `AlarmManager.cancel` 연동 — `ui-commitment-cancel-action.md`
- OverdueSweepWorker (CMT-011) — 별도 plan
- Notification 탭 → 상세 시트 딥링크의 Sheet open 로직 상세 — `ui-commitment-detail-sheet.md` 가 다룸
- 기존 `reminders` 채널이 배포된 기기의 마이그레이션 — 베타 배포 전 구현이므로 기기 zero 가정

---

## 8. Dependencies

- **Blocked by**:
  - `ui-commitment-detail-sheet.md` — [리마인드] 버튼을 호스트할 CommitmentDetailSheet 가 먼저 필요 (같은 브랜치 commit 순서로 해결).
  - PR #17 (`feat/db/commitment/due-at-hint-approximate`) — `due_at` 필드 존재 전제. PR #17 머지 전에는 Entity 의 `dueDate` (LocalDate) 를 `Instant` 로 올릴 수 없음. PR #17 머지 후 구현 시작.
- **Blocks**:
  - CMT-011 OverdueSweepWorker — 동일 채널 사용 여부 결정 (별도 plan)

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

Revert 시 Receiver 상수 / Scheduler 게이트 원복. 기기에 이미 생성된 `commitment_due_soon` 채널은 앱에 남아 있으나 unused — 사용자 영향 없음. 기존 `reminders` 채널이 복귀하며 IMPORTANCE_DEFAULT 로 동작. Safe.

---

## Appendix — Session handoff notes

- **Scheduler API 선택**: `schedule(id, dueAt)` (scheduler 내부에서 -1h 계산) vs `schedule(id, triggerAt)` (호출자 계산). 후자는 현재 시그니처 유지 장점, 전자는 호출자 실수 방지. 현재 호출처가 1곳뿐이므로 **전자로 변경 권장**. KDoc + Unit test 에서 "-1h" invariant 명시.
- **Silent drop 구현**: `BroadcastReceiver` 는 `goAsync()` 로 DAO 조회 비동기화 필요. 또는 `WorkManager` 1회성 OneTimeWorkRequest 로 위임 — 후자는 alarm 트리거 시점과 notify 시점 사이 지연 증가. 전자 (goAsync + CoroutineScope) 권장.
- **Hilt entrypoint**: `BroadcastReceiver` 에 `@AndroidEntryPoint` 이미 있고 필드 주입 가능. `@Inject lateinit var commitmentDao: CommitmentDao` 추가.
- **Direction 라벨 매핑**: 하드코딩 대신 `strings.xml` 리소스 키로. 다국어 확장 대비.
- **PIPA 재검토**: 로컬 알림 본문에 title 포함은 클라이언트 디바이스에서만 렌더되므로 "제3자 제공" 에 해당하지 않음. ONB-PIPA 동의 범위 내. 관련 우려를 PR description 에 적어 CTO 검토 요청.
- **Notification tap 딥링크**: `MainActivity` 에 `intent.getStringExtra(EXTRA_COMMITMENT_ID)` 파싱이 이미 있는지 먼저 grep 후 없으면 추가. 파싱 후 Compose 측에 전달하는 채널은 현재 `SavedStateHandle` 또는 별도 `NavigationCommand` 패턴 중 기존 관례 따름.
