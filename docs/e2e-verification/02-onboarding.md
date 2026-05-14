# E2E Verification — `onboarding` 모듈

Spec: `becalm-android/.spec/onboarding.spec.yml` (12단계 시퀀스)

---

## 0. 전체 흐름 (Navigation sequence)

```
SplashScreen → TermsScreen → LoginScreen → PipaThirdPartyConsentScreen
  → RecordingFolderScreen → ContactsPermissionScreen
  → GmailOAuthScreen → OutlookMailOAuthScreen → ImapSetupScreen
  → GoogleCalendarOAuthScreen → OutlookCalendarOAuthScreen
  → BatteryOptimizationScreen → ColdSyncScreen → TodayTimelineScreen
```

Driver VM: `ui/onboarding/OnboardingViewModel.kt`
Nav host: `ui/navigation/BecalmNavHost.kt:75-140` (모든 composable 진입점)
Route sealed class: `ui/navigation/Routes.kt:39` (`BecalmRoute`)

DataStore flags (`data/local/datastore/UserPrefsStore.kt`):
- `observeTermsAccepted` / `setTermsAccepted` (L164/169)
- `observeOnboardingCompleted` / `setOnboardingCompleted` (L63/70)
- `observeThirdPartyProvisionConsent` / `setThirdPartyProvisionConsent` (L142/156) ← PIPA

---

## ONB-001 — Terms 동의 후 Login 이동

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Screen | `ui/auth/TermsScreen.kt` | `[동의]` 버튼 콜백 |
| DataStore | `UserPrefsStore.kt:169` | `setTermsAccepted(true)` |
| Nav | `BecalmNavHost.kt:79` + `ui/navigation/Routes.kt` | `BecalmRoute.Terms` → `BecalmRoute.Login` |

**Verify**: `grep -n "setTermsAccepted" becalm-android/android/app/src/main/java/com/becalm/android/**/*.kt` → TermsScreen 에서만 호출.

---

## ONB-PIPA — 제3자 제공 + 국외 이전 동의

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Screen | `ui/onboarding/PipaThirdPartyConsentScreen.kt` | [동의]/[동의안함] 2버튼 |
| VM | `ui/onboarding/OnboardingViewModel.kt:271/279` | `onPipaConsentGranted()` / `onPipaConsentDeclined()` |
| Write | `UserPrefsStore.kt:156` | `setThirdPartyProvisionConsent(granted)` |
| Side-effect (동의시) | 없음 → RecordingFolderScreen 으로 이동 |
| Side-effect (거부시) | `data/local/db/dao/RawIngestionEventDao.kt:215` | 기존 voice raw events 가 `findVoiceAwaitingConsent` / `flipAwaitingConsentVoiceToPending` 경로와 연동됨 (VOI-004 참조) |

**Verify**:
```
grep -rn "pipa_third_party_consent\|ThirdPartyProvisionConsent" becalm-android/android/app/src/main/java
```
- [ ] 고지문 6가지 항목(제공받는자/항목/목적/리전/보유기간/거부권) 모두 Screen 에 리터럴 또는 strings.xml 로 존재.
- [ ] 거부 시 `RecordingFolderScreen` 를 **skip** 하고 바로 `ContactsPermissionScreen` 으로 갔는지 Nav 분기 확인.

---

## ONB-002 — 녹음 폴더 자동 탐지

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Screen | `ui/onboarding/RecordingFolderScreen.kt` | 진입 시 `/storage/emulated/0/VoiceRecorder` 자동 탐색 |
| Permission call | Android `ActivityResultContracts.RequestPermission` + SAF `OpenDocumentTree` | READ_MEDIA_AUDIO |

**Verify**: PIPA=false 시 Nav 에서 이 route 건너뛰는지 (`BecalmNavHost.kt:102`).

---

## ONB-003 — READ_MEDIA_AUDIO + SAF URI

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Screen | `ui/onboarding/RecordingFolderScreen.kt` | 권한 런처 |
| Observer bootstrap (권한 후) | `worker/ContentObserverBootstrap.kt:73` | `start()` — 권한 있을 때만 MediaStore observer 등록 |

---

## ONB-CONTACTS — 연락처 권한

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Screen | `ui/onboarding/ContactsPermissionScreen.kt` | [허용]/[나중에] |
| Enrichment kick-off | `worker/EnrichmentWorker.kt:78` | `doWork()` — READ_CONTACTS granted 시 schedule 됨 |

---

## ONB-004 — Gmail OAuth 스킵

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Screen | `ui/onboarding/GmailOAuthScreen.kt` | [스킵] → `onSkipStep` |
| VM | `OnboardingViewModel.kt:166` | `onSkipStep()` — 다음 step 으로 전이 |

**Verify**: `gmail_connected` 플래그는 `false` 유지 (DataStore enabledSources set 에 "gmail" 없음).

---

## ONB-005 — 배터리 최적화 예외 (2단계, 삼성 분기)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Screen | `ui/onboarding/BatteryOptimizationScreen.kt` | Build.MANUFACTURER 분기 |
| VM | `OnboardingViewModel.kt:138` | `onNext()` — 시스템 다이얼로그 후 삼성 가이드 렌더 |

**Verify**: `grep -n "MANUFACTURER" becalm-android/android/app/src/main/java/com/becalm/android/ui/onboarding/BatteryOptimizationScreen.kt` → samsung 분기 상수 존재.

---

## ONB-006 — 완료 후 재실행 시 스킵

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Splash | `ui/auth/SplashScreen.kt` | `UserPrefsStore.observeOnboardingCompleted` + `AuthViewModel.onObserveSession` 병합 |
| VM | `AuthViewModel.kt:165` | `onObserveSession()` → SignedIn 분기 |
| Nav | `BecalmNavHost.kt:140` | `BecalmRoute.Today` 로 바로 이동 |

---

## ONB-007 — 온보딩 실패 Firebase Crashlytics 이벤트

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| VM error paths | `OnboardingViewModel.kt:207` | `setPipa` — Firebase Crashlytics breadcrumb 필요 |
| Logger | `core/util/Logger.kt` | Firebase Crashlytics wrapper (존재 여부 확인) |

**Verify — GAP 주의**:
```
grep -rn "Firebase Crashlytics\|Crashlytics" becalm-android/android/app/src/main/java
```
- [ ] Firebase Crashlytics SDK 연동이 실제 존재하는지 확인. 없으면 ONB-007 은 **스펙과 구현 gap** 으로 보고.

---

## ONB-008 — ColdSync gate: onboarding_completed 는 ColdSync 완료 후에만 true

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Screen | `ui/onboarding/ColdSyncScreen.kt` | [나중에 하기] / 완료 콜백 |
| VM | `ui/today/ColdSyncViewModel.kt:49` | ColdSyncViewModel — progress 집계 |
| Write gate | `UserPrefsStore.kt:70` | `setOnboardingCompleted(true)` — ColdSync 완료 또는 skip 시에만 |

**Verify invariant**:
```
grep -rn "setOnboardingCompleted(true)" becalm-android/android/app/src/main/java
```
- [ ] 호출 site 가 ColdSyncScreen 완료/skip 콜백 경로에 **1곳만** 존재해야 함. OnboardingViewModel 중간 step 에서 flip 하면 invariant 위반.

---

## Tests (현재 상태)

| 파일 | 커버 |
| --- | --- |
| `ui/onboarding/OnboardingViewModelTest.kt` | step 전이 / PIPA consent flip |
| `flow/PipaConsentReleaseFlowTest.kt` | PIPA consent 를 늦게 부여한 경로 (VOI-004 연동) |

모든 behavior 의 spec `tests: []` 가 비어 있으므로 **CTO 는 각 behavior ID 를 test 파일명/함수명에 annotation 으로 연결할 것을 지시** 해야 한다.
