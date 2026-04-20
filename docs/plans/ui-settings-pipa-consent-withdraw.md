# UI / Settings / PIPA Consent Withdraw — 동의 철회 (PIPA-003 동의철회권)

**Branch**: `feat/ui/settings/pipa-rights`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 6 (Settings → PIPA 권리 실행)
**Severity**: Critical (PIPA 제39조 동의철회권 — MVP 법규 블로커)
**Type**: Gap (ConsentWithdrawScreen 부재, 선택적 동의별 Switch 없음, OAuth revoke 경로 없음)

---

## 1. Finding

PIPA-003 은 **각 동의 항목별 Switch 로 세분화된 철회** 를 요구하며, 일괄 off 만 제공 금지한다는 **invariant** 를 걸고 있다 (`.spec/pipa-rights.spec.yml:88`).
현재 `SettingsPipaSection.kt:48-52` 에 PIPA 제3자 제공 단일 Switch 만 존재하며, Gmail/Outlook/IMAP/Calendar 각 OAuth 동의를 **개별 Switch 로 철회할 UI 는 전무**하다.

OAuth token revoke API 호출 경로(`revokeToken`, Google OAuth revocation endpoint) 코드도 없음:
```bash
grep -rn "oauth2.googleapis.com/revoke\|revokeToken\|revokeConsent" android/app/src/main/java/
# → 0 hits
```

본 PR 은 umbrella 의 `PipaConsentWithdraw` placeholder 를 실체화하며, 세분화된 Switch 목록 + 각 off 시의 부수효과(토큰 revoke, cursor 초기화, WorkManager 취소, sync_status='awaiting_consent' 전환) 를 구현한다.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/pipa-rights.spec.yml:36-44` — PIPA-003 전문
> "[동의 철회] — 현재 활성화된 모든 동의(이용약관, PIPA 제3자 제공, 각 소스 OAuth 동의)를 사용자가 선택적으로 철회한다. 철회 화면은 각 동의 항목을 별개 Switch로 나열하며 각 철회의 영향(예: '음성 자동 처리 중단됨')을 사전 고지한다"
>
> "ConsentWithdrawScreen 표시됨. Switch 항목 및 off 시 영향: (1) PIPA 제3자 제공 동의 off → DataStore pipa_third_party_consent=false + 모든 voice raw event sync_status='awaiting_consent'로 즉시 전환(이미 synced된 것은 유지), VoiceUploadWorker 후속 호출 차단. (2) Gmail OAuth 연결 해제 → DataStore gmail_connected=false, token revoke(Google OAuth revocation endpoint 호출), 이후 Gmail 어댑터 실행 중단. (3) Outlook / IMAP / Calendar 동일 패턴. 이용약관 철회는 사실상 '계정 삭제'와 동등하므로 PIPA-005로 안내. 각 토글 off 시 해당 소스의 sync_status는 그대로 두되 새 업로드 차단 + UI 배너 '이 소스는 동의가 철회되어 동기화가 중단되었습니다'"

### 2.2 `.spec/pipa-rights.spec.yml:88` — invariant
> "[동의 철회]는 각 동의 항목별 Switch로 세분화되어야 하며 일괄 off만 제공 금지 — 사용자가 선택적 철회할 권리 보장"

### 2.3 `.spec/pipa-rights.spec.yml:76-83` — PIPA-007 감사 로그
> "action: 'consent_withdraw', … details: {consent_type?: 'pipa_third_party', source?: 'gmail', …}"

### 2.4 관련 참조 — Voice 재동작 invariant
`SettingsViewModel.kt:220-264` 이 이미 `pipa_third_party` toggle off 시 `parkAndCancelPendingVoice` + `cancelVoiceUpload` 를 수행 — 재사용.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 단일 토글만 존재
`android/app/src/main/java/com/becalm/android/ui/settings/SettingsPipaSection.kt:43-52`:
```kotlin
SettingsToggleRow(
  label = stringResource(R.string.settings_notifications_label),
  checked = notificationsEnabled,
  onCheckedChange = onToggleNotifications,
)
SettingsToggleRow(
  label = stringResource(R.string.settings_pipa_toggle_label),
  checked = pipaConsentEnabled,
  onCheckedChange = onTogglePipa,
)
```
— 이것이 **전부**. 소스별 OAuth 철회 Switch 없음.

### 3.2 OAuth revoke API 호출 없음
`grep -rn "revoke\|accounts\\.google\\.com/o/oauth2/revoke" android/app/src/main/java/` — 0 hit.
Gmail/Outlook/IMAP/Calendar 각 adapter 는 cursor 와 토큰을 읽기만 하며, 로그아웃/revoke 은 `AuthRepository.signOut()` 전역 wipe 경로만 존재.

### 3.3 소스별 `connected` flag 없음
DataStore `UserPrefsStore` 에 `gmail_connected` / `outlook_connected` 같은 per-source consent flag 가 없다. 대신 `SourceStatusRepositoryImpl.Keys` 는 last_synced_at / last_error / in_progress 3 가지만 보유.

### 3.4 "이미 synced 는 유지" invariant — 코드 경로 확인
`RawIngestionEventDao.kt:212-284` 에서 voice row 상태 쿼리 `sync_status='awaiting_consent'` 패턴 존재. `parkAndCancelPendingVoice` 가 **pending / queued** 상태만 다루고 synced 는 안 건드리므로 spec 과 정합 — 재사용 가능.

### 3.5 이용약관 철회 → 계정 삭제 유도 경로 없음
spec 은 "이용약관 철회는 사실상 '계정 삭제'와 동등하므로 PIPA-005로 안내" — 현재 UI 에 이 안내 없음.

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| 세분화된 Switch | 각 동의(PIPA / Gmail / Outlook / IMAP / GCal / OutlookCal) 독립 토글 | 단일 PIPA 토글만 | 5+ Switch 신규 |
| OAuth revoke | Google/MS revocation endpoint 호출 | 없음 | `OAuthRevokeService` 신규 |
| 소스별 connected 플래그 | DataStore gmail_connected 등 | 없음 | 6 keys 추가 |
| "동의 철회됨" 배너 | 해당 소스 SourceDetailScreen 상단 | 없음 | Banner state 추가 |
| 이용약관 철회 안내 | "계정 삭제로 안내" 링크 | 없음 | 안내 Text 추가 |
| Worker skip | 철회된 소스 adapter 후속 실행 차단 | 없음 | doWork() 초반 consent 체크 |

---

## 5. Proposed Fix

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/ui/navigation/BecalmNavHost.kt`**
   - `PipaConsentWithdraw` placeholder → `ConsentWithdrawScreen(navController)` 로 교체.

2. **`android/app/src/main/java/com/becalm/android/data/local/datastore/UserPrefsStore.kt`**
   - 6 개 booleanPreferencesKey 추가: `source_consent.gmail`, `source_consent.outlook_mail`, `source_consent.naver_imap`, `source_consent.daum_imap`, `source_consent.google_calendar`, `source_consent.outlook_calendar`.
   - 각 `observeSourceConsent(sourceType): Flow<Boolean>` + `setSourceConsent(sourceType, granted)`.
   - default **true** (onboarding 시 OAuth 연결을 완료한 순간 consent granted — **단, OAuth 연결이 없었던 소스는 false 로 초기화**. 초기화 로직: OAuth 성공 flow 에서 `setSourceConsent(sourceType, true)` 호출 추가).

3. **모든 ingestion worker (`worker/ingestion/*Worker.kt`)** — 6 개 파일
   - `doWork()` 진입부에 `val consent = userPrefsStore.observeSourceConsent(SourceType.XXX).first(); if (!consent) return@withContext Result.success()` 추가.
   - 파일: `GmailWorker.kt`, `OutlookMailWorker.kt`, `ImapNaverWorker.kt`, `ImapDaumWorker.kt`, `GoogleCalendarWorker.kt`, `OutlookCalendarWorker.kt`.
   - `VoiceUploadWorker.kt` 는 이미 `pipa_third_party` 체크 경로 존재 — 그대로 둠.

4. **`android/app/src/main/java/com/becalm/android/ui/sources/SourceDetailScreen.kt`**
   - 상단에 "이 소스는 동의가 철회되어 동기화가 중단되었습니다" 배너 슬롯 추가 (consent=false 일 때만).
   - (본 PR 은 배너만 추가; full SMG-002..005 작업은 `ui-settings-source-actions.md` 별도 PR)

### 5.2 Files to add

1. **`android/app/src/main/java/com/becalm/android/ui/settings/pipa/ConsentWithdrawScreen.kt`**
   - 역할: PIPA-003 Switch 목록 화면. 2 섹션:
     - 섹션 1 — 필수 동의: "이용약관" (off 시 "계정 삭제로 안내" TextButton → `navigate(PipaAccountDeletion.path)`; off 자체는 불가 — disabled Switch + 설명).
     - 섹션 2 — PIPA 제3자 제공 (Voice) 토글 — 기존 `SettingsPipaSection` 과 동일 로직 재사용 (ViewModel 에 동일 `onTogglePipa` 호출).
     - 섹션 3 — 소스 OAuth 동의 6 Switch.
   - 각 Switch off 클릭 시 AlertDialog — 영향 고지 ("이 소스의 자동 동기화가 중단됩니다"). 확인 시 ViewModel 호출.

2. **`android/app/src/main/java/com/becalm/android/ui/settings/pipa/ConsentWithdrawViewModel.kt`**
   - `@HiltViewModel`. UiState: 각 소스 consent 상태 + PIPA toggle + 에러.
   - 의존성: `UserPrefsStore`, `SyncCursorStore`, `OAuthRevokeService`, `WorkScheduler`, `RawIngestionRepository`, `PipaActionLogStore`, `AuthRepository` (userId).
   - Action `onSourceConsentWithdraw(sourceType)`:
     1. `userPrefsStore.setSourceConsent(sourceType, false)`
     2. `oauthRevokeService.revoke(sourceType)` — Google / MS endpoint 호출. IMAP 은 서버 revoke 없음 → 로컬 Keystore credentials clear 만.
     3. `syncCursorStore.clearCursor(sourceType)` — 재연결 시 stale cursor 방지.
     4. WorkManager cancel — `cancelUniqueWork(UniqueWorkKeys.<source>)`. (신규 `WorkScheduler.cancelSource(sourceType)` 추가 필요.)
     5. `pipaActionLogStore.appendAction(CONSENT_WITHDRAW, mapOf("source" to sourceType))`.

3. **`android/app/src/main/java/com/becalm/android/data/remote/oauth/OAuthRevokeService.kt`**
   - 역할: Google revoke URL `https://oauth2.googleapis.com/revoke?token={token}` + MS Graph `https://graph.microsoft.com/v1.0/me/revokeSignInSessions` 호출.
   - Retrofit 인터페이스 + `@POST`. 입력 token 은 `EncryptedTokenStore` 에서 조회.
   - 실패 시 `BecalmResult.Failure` 반환. UI 는 Snackbar 로 노출하되 로컬 consent=false 는 **이미 커밋됨** (서버 revoke 실패 ≠ 사용자 의사 철회 취소; spec 우선순위는 사용자 권리).

4. **`android/app/src/main/java/com/becalm/android/worker/WorkScheduler.kt`**
   - `public fun cancelSource(sourceType: String)` 메서드 추가 (인터페이스 + `WorkSchedulerImpl`).

5. **`android/app/src/main/java/com/becalm/android/data/local/datastore/PipaActionLogStore.kt`** — 이미 `ui-settings-pipa-data-export.md` 에서 신규 도입. 머지 순서에 따라 여기서 만들 수도 있음 — 첫 merge sub-PR 이 생성.

6. **`strings.xml`** — 섹션별 문구, 각 소스 라벨, off 확인 문구, 이용약관 안내.

7. **Tests**:
   - `ConsentWithdrawViewModelTest — 각 소스 off 시 4 단계(DataStore write / revoke / cursor clear / cancel) 순서대로 호출`.
   - `GmailWorkerConsentSkipTest — consent=false 일 때 doWork early-return`.

### 5.3 Files to delete (dead code)

없음.

### 5.4 Non-code changes

- OAuth provider 설정: Google Cloud Console 의 OAuth 2.0 client 에 revoke 가 허용되는지 확인 (default 허용). MS Graph 는 admin consent 불요.
- Supabase — per-source revoke 는 서버 상태와 무관 (클라이언트만 동기화 중단).

---

## 6. Acceptance Criteria

- [ ] **Compose test**: `ConsentWithdrawScreenTest — 최소 6 Switch + PIPA toggle + 이용약관 안내 행 렌더링`.
- [ ] **Unit test**: `ConsentWithdrawViewModelTest — gmail off 시 setSourceConsent(gmail,false) + revoke + clearCursor(gmail) + cancelSource(gmail) 4 호출 확인`.
- [ ] **Worker early-return**: `GmailWorkerConsentSkipTest — consent=false 시 Result.success 반환 + GmailClient.listHistory 호출 0`.
- [ ] **Grep invariant (일괄 off 금지)**: ConsentWithdrawScreen 에 "모두 철회" 같은 bulk button 이 없는지 수작업 리뷰 (spec invariant). 자동 grep 부적합 — PR 리뷰 항목.
- [ ] **Grep invariant (revoke URL)**: `grep -rn "oauth2.googleapis.com/revoke\|revokeSignInSessions" android/app/src/main/java/ | wc -l` ≥ 2.
- [ ] **Grep invariant (consent 체크 전수)**: `grep -rn "observeSourceConsent\|source_consent\\." android/app/src/main/java/com/becalm/android/worker/ingestion/ | wc -l` ≥ 6 (6 worker 모두 체크).
- [ ] **감사 로그**: `PipaActionLogStoreTest — consent_withdraw append 확인`.

---

## 7. Out of Scope

- 전체 PIPA 토글 UI 재설계 — 기존 `SettingsPipaSection` 의 동의 Switch 는 유지 (duplicate 허용; spec 우선은 세분화).
- SourceDetailScreen 의 전체 SMG-002..005 wiring — `ui-settings-source-actions.md` 별도 PR. 본 PR 은 배너 slot 만 추가.
- Account Deletion 본체 — `ui-settings-pipa-account-deletion.md`.
- Processing Pause — `ui-settings-pipa-processing-pause.md`.
- IMAP 서버측 세션 revoke — IMAP 프로토콜에 revoke 개념 없음 → 로컬 credentials clear 로 충분.

---

## 8. Dependencies

- **Blocked by**: `ui-settings-privacy-management.md` (umbrella) — Route `PipaConsentWithdraw` placeholder 가 선행.
- **Blocks**:
  - `ui-settings-pipa-account-deletion.md` — PIPA-005 의 "이용약관 철회 → 계정 삭제" 링크는 본 PR 이 건 nav hook. 반대로 account deletion 실제 flow 는 PIPA-005 PR 에서.
- **파일 겹침**:
  - `UserPrefsStore.kt` — `ui-settings-pipa-processing-pause.md` 와 **동일 파일 수정**. 브랜치 정책에 따라 같은 모듈 브랜치에 linear commit → 자연 linear stack.
  - `worker/ingestion/*Worker.kt` 6 개 — `ui-settings-pipa-processing-pause.md` 의 `processing_paused` early-return 과 **동일 파일**. 두 PR 은 **머지 순서 주의** 또는 **한 PR 에 병합**. 본 doc 은 `consent` early-return, Pause doc 은 `processing_paused` early-return — 두 체크 모두 필요하므로 **같은 worker doWork() 상단 블록** 으로 묶어서 작성하는 것이 정석. 구현 세션은 두 체크를 단일 helper (`SkipCheck.shouldSkip(sourceType): Reason?`) 로 통합할 것을 권장.

- **병렬 가능**: Export / Activity log / Account deletion 과는 파일 겹침 없음.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

Revert 시 UI 의 세분 Switch 만 사라짐. 이미 set 된 `source_consent.*` DataStore key 는 남고 worker early-return 도 revert 되어 정상 동작. OAuth revoke API 호출은 이미 서버 반영 → forward-only (사용자가 revoke 된 토큰으로 로그인 시도하면 ERR-003 경로로 재연결 유도). 데이터 손상 없음.

---

## Appendix — Session handoff notes

- **이용약관 철회 = 계정 삭제** spec 문구의 해석: 이용약관 자체 Switch 는 **disabled (off 불가)**. 대신 "이용약관에 동의하지 않으려면 계정을 삭제하세요" TextButton 으로 PIPA-005 navigate — 이것이 spec "연결 안내 문구로 처리" 의도와 정합.
- **이미 synced 인 row 는 유지** invariant: `parkAndCancelPendingVoice` 가 synced 를 안 건드리는 것과 동일 — 소스 OAuth 철회 시에도 Room 의 raw_ingestion_events 전부 유지. **"새 업로드 차단"** 만이 목적.
- **OAuthRevokeService 실패 처리**: 네트워크 에러 시 로컬 consent=false 는 이미 기록됨. 서버 측 revoke 재시도를 WorkManager 로 예약할지(`RevokeRetryWorker`) vs 다음 재연결 시도 때 refresh 401 경로로 자연 정리될지 선택. 후자가 단순 — spec 은 명시하지 않음. MVP 는 fire-and-log 방식 권장.
- **소스 재연결 경로** (SMG-003): consent=false 인 소스 card 를 재탭 시 OAuth 재인증 플로우로 진입. 재인증 성공 시 `setSourceConsent(sourceType, true)` 호출 필요. 본 PR 과 `ui-settings-source-actions.md` 사이의 hand-off 지점.
- **PIPA-005 이용약관 철회 링크 경로**: `navController.navigate(BecalmRoute.PipaAccountDeletion.path)` — umbrella PR 이 심는 route object 이름이 sub-PR 간 공유되므로 네이밍 타이밍 주의.
