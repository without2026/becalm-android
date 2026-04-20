# Worker / Voice / pipa-insert-status — VoiceMediaStoreProbe inserts `pending` regardless of PIPA consent

**Branch**: `fix/worker/voice/pipa-insert-status`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 1 (MediaStore ingestion) — cold-sync.spec:49 및 VOI-004 와 직접 연계
**Severity**: High (PIPA 미동의 사용자의 voice row 가 즉시 업로드 후보가 되어 VoiceUploadWorker 가 전송 시도 — 업로드 워커의 2차 가드로 실제 유출은 막히나 spec invariant 위반 + race window 존재)
**Type**: Drift (insertion 시점 PIPA 분기 로직이 빠짐)

---

## 1. Finding

`VoiceMediaStoreProbe.insertVoiceRow` (파일 `android/app/src/main/java/com/becalm/android/worker/ingestion/VoiceMediaStoreProbe.kt:302`) 는 **모든 voice row 를 무조건 `syncStatus = "pending"` 으로 insert** 한다. 그러나 스펙은 insertion 시점의 `pipa_third_party_consent` 값에 따라 분기해야 함을 명시:

> cold-sync.spec.yml:49
> "source_type='voice'|'call_recording' raw_ingestion_events INSERT(**sync_status='awaiting_consent' if pipa_third_party_consent=false, else 'pending'**)"

현재는 항상 `pending` 으로 들어가므로, 이후 VoiceUploadWorker 의 PIPA gate (2차 defense-in-depth) 가 잡아 주기 전까지 `pending` 상태로 큐에 남아 있음. 이는 다음 invariant 를 깬다:

- `RawIngestionEventEntity.syncStatus` KDoc (`line 156`): "awaiting_consent — voice source only. pipa_third_party_consent=false at worker insert time"
- data-model.yml:71: "awaiting_consent: voice source만 사용. pipa_third_party_consent=false인 동안 VoiceUploadWorker가 업로드를 보류"
- `RawIngestionRepository.parkVoicePendingAsAwaitingConsent` — 이미 만들어진 pending 을 **후처리로** awaiting_consent 로 park 하는 경로가 존재 (즉, insertion-time 가드가 없다는 증거). 설계상 insertion-time 가드 + 상태 전이(consent flip) 둘 다 있어야 race-free.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/cold-sync.spec.yml:49`
> "source_type='voice'|'call_recording' raw_ingestion_events INSERT(sync_status='awaiting_consent' if pipa_third_party_consent=false, else 'pending')"

### 2.2 `.spec/voice-pipeline.spec.yml` VOI-004
> PIPA 제3자 제공 동의 연동 — 미동의 시 voice 업로드 차단, 동의 ON 시 자동 재개.

### 2.3 `.spec/contracts/data-model.yml:479`
> "PIPA 제3자 제공 + 국외 이전 동의(pipa_third_party_consent)는 DataStore에 저장되며 raw_ingestion_events.sync_status='awaiting_consent'와 연동"

### 2.4 `.spec/contracts/data-model.yml:70-71` (enum 정의)
> "enum: pending | synced | failed | awaiting_consent — Room-side tracking column, not uploaded to Supabase"

---

## 3. Code Reality (지금 무엇인가)

### 3.1 Insertion 분기 없음 — `VoiceMediaStoreProbe.kt:293-303`
```kotlin
private suspend fun insertVoiceRow(row: VoiceRow, userId: String): VoiceInsertResult {
    val entity = RawIngestionEventEntity(
        id = UUID.randomUUID().toString(),
        userId = userId,
        clientEventId = row.clientEventId,
        sourceType = SourceType.VOICE,
        sourceRef = row.audioUri,
        durationSeconds = row.durationSec,
        timestamp = Instant.fromEpochSeconds(row.dateAddedSec),
        syncStatus = "pending",   // ← 항상 하드코딩
    )
    ...
}
```

### 3.2 PIPA consent Flow 는 이미 존재 — `UserPrefsStore.kt:260-261`
```kotlin
override fun observeThirdPartyProvisionConsent(): Flow<Boolean> =
    dataStore.data.map { it[pipaThirdPartyConsentKey] ?: false }
```

### 3.3 VoiceMediaStoreProbe 는 이미 `userPrefsStore` 를 DI 로 보유
- `VoiceMediaStoreProbe.kt:45` 에 `userPrefsStore: UserPrefsStore` 주입됨.
- 현재 `observeCurrentUserId().first()` 만 사용 (line 66).
- **PIPA Flow 호출은 새 라인 하나 추가로 완료**.

### 3.4 DAO 는 이미 준비 완료
- `RawIngestionEventDao.kt:232-244`: `releaseAwaitingConsentToPending(userId)` — ON 전환 시 parked row 재개
- `RawIngestionEventDao.kt:309-317`: `parkIdsAsAwaitingConsent(ids, now)` — 후처리 park 경로
- 즉, **insertion-time 가드 하나만 추가하면 consent flip 파이프라인과 맞물림**.

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| Insert 시점 syncStatus | `pipa=false → 'awaiting_consent'` / `true → 'pending'` | 항상 'pending' | 분기 로직 1줄 추가 |
| Race window | 없음 (insert 자체가 gated) | insert 후 park 까지 윈도우 존재 | insert-time gate 로 0 |
| 후처리 park 경로 | 옵션 (consent OFF 전환 시) | 구현됨 | 그대로 유지 |

---

## 5. Proposed Fix

### 5.1 Files to change

**`android/app/src/main/java/com/becalm/android/worker/ingestion/VoiceMediaStoreProbe.kt`**

1. `ingestVoiceRecordings` 진입 직후 `userId` 조회 바로 아래에 PIPA Flow 값을 한 번 스냅샷:
   ```kotlin
   val pipaConsented = userPrefsStore.observeThirdPartyProvisionConsent().first()
   ```
2. `insertVoiceRow` 시그니처에 `pipaConsented: Boolean` 파라미터 추가.
3. Entity 생성 시:
   ```kotlin
   syncStatus = if (pipaConsented) "pending" else "awaiting_consent",
   ```
4. 호출부 (`insertVoiceRow(row, userId)` → `insertVoiceRow(row, userId, pipaConsented)`) 갱신.
5. KDoc 업데이트 — insertion-time PIPA gate 명시.

### 5.2 Files to add
없음. 테스트 파일은 `android/app/src/test/java/com/becalm/android/worker/ingestion/VoiceMediaStoreProbeTest.kt` 가 이미 존재하면 케이스만 추가, 없으면 신규 작성.

### 5.3 Files to delete
없음.

### 5.4 Non-code changes
- `RawIngestionRepository.parkVoicePendingAsAwaitingConsent` 는 **유지**. Consent OFF 전환 시점이나 기존 pending rows backfill 용. Insertion-time gate 와 coexist (defense-in-depth).
- 동의 ON 전환 시 `releaseAwaitingConsentToPending(userId)` 호출 흐름은 기존대로 (별도 스코프).

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -n "syncStatus\s*=\s*\"pending\"" android/app/src/main/java/com/becalm/android/worker/ingestion/VoiceMediaStoreProbe.kt` = 0 (조건식 안쪽은 OK 지만 무조건 `"pending"` 은 금지)
- [ ] **Grep invariant**: `grep -n "if.*pipaConsented.*pending.*awaiting_consent\|awaiting_consent" android/app/src/main/java/com/becalm/android/worker/ingestion/VoiceMediaStoreProbe.kt` ≥ 1
- [ ] **Unit test**: `VoiceMediaStoreProbeTest — inserts awaiting_consent when pipa consent is false`
- [ ] **Unit test**: `VoiceMediaStoreProbeTest — inserts pending when pipa consent is true`
- [ ] **Unit test**: 한 번 읽은 pipaConsented 값을 배치 전체에 일관 적용 (mid-batch 토글 시 race-free — 배치는 스냅샷 기준으로 결정)
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공

---

## 7. Out of Scope

- Consent OFF → ON 전환 시 rows 재개 (`releaseAwaitingConsentToPending`) — 별도 흐름, 이미 구현
- Consent ON → OFF 전환 시 후처리 park (`parkVoicePendingAsAwaitingConsent`) — 이미 구현
- `sms_mms` / `email` / `calendar` source 의 consent 연동 — voice 전용 invariant (spec 명시)
- VoiceUploadWorker 의 2차 gate 제거 — defense-in-depth 이므로 유지
- call_recording source_type 분기 — PR #12 / #15 담당

---

## 8. Dependencies

- **Blocked by**: 없음 (완전 독립, 현재 DI + DAO + DataStore 로 즉시 구현 가능)
- **Blocks**: 없음 직접적으로; 단 Stage 2 PIPA invariant 감사 완료 게이트.

- **병렬 가능**:
  - `fix/repo/voice/commitment-source-type-inherit` (PR #16) — 파일 겹침 없음
  - `feat/db/commitment/due-at-hint-approximate` (PR #17) — 파일 겹침 없음
  - `feat/db/voice/call-recording-enum` (PR #12) — 파일 겹침 없음
  - `refactor/worker/sms/remove-dead-code` (PR #13) — 파일 겹침 없음
  - `feat/worker/voice/ingestion-realign` (PR #14) — **VoiceMediaStoreProbe 파일 겹침 있음** → 순차 merge 필요
  - `feat/worker/voice/call-recording` (PR #15) — **VoiceMediaStoreProbe 파일 겹침 있음** → 순차 merge 필요

**권장 merge 순서**: PR #12 (enum) → **본 PR** (insertion gate) → PR #14 (SAF 재정렬) → PR #15 (call_recording 분기). 본 PR 은 작고 독립이라 가장 먼저 넣기에 가장 적합.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

Revert 후 다시 모든 voice row 가 `pending` 으로 insert 됨 — VoiceUploadWorker 의 2차 gate 가 실제 유출은 막으므로 사용자 데이터 유출 위험은 없으나 spec drift 로 돌아감. 기존 `awaiting_consent` 로 insert 된 row 는 그대로 유지 (DB enum 이 이미 수용).

---

## Appendix — Session handoff notes

- **가장 작은 PR 중 하나** (단일 파일, ~5 줄). 빠른 merge 추천.
- Test 작성 시 `FakeUserPrefsStore` 에서 `observeThirdPartyProvisionConsent()` 를 `flowOf(true)` / `flowOf(false)` 로 mock.
- Batch 중 mid-batch consent toggle 시나리오는 **not a race** — `first()` 로 배치 시작 시점 1회 읽음. 사용자 UX 상 Settings 토글은 cold-sync 배치 중에 일어나지 않는다고 가정 가능. 만약 real-time 반영이 필요해지면 `collect` 로 변경하는 별도 PR.
- `VoiceMediaStoreProbe` 는 internal class — 테스트는 `MediaStoreWorker` 경유 E2E 로만 가능할 수도 있음. 파일 맨 끝 KDoc 도 "exercised solely through `MediaStoreWorker.doWork` tests" 라고 명시. 구현자는 가시성 변경 vs Worker 레벨 테스트 추가 중 선택.
- KDoc `VoiceMediaStoreProbe.kt:22-37` 의 "sync_status='pending'" 문구도 같이 갱신 필요 — 조건부 값임을 반영.
