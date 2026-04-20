# UI / Onboarding / imap-provider-selector — ImapSetupScreen 에 네이버/다음 SegmentedButton + 사전 설정 host + 실제 credential 저장 연결

**Branch**: `feat/ui/onboarding` (Gmail/Outlook OAuth plan 과 **같은 브랜치 — stack**)
**Status**: PLAN ONLY — 구현은 별도 세션. 이 plan doc 이외의 코드 커밋 금지.
**E2E Stage**: 2 — Onboarding (IMAP credential step, 온보딩 8단계)
**Severity**: **High** (사용자가 입력한 host/email/password 가 **저장되지 않고** 폐기됨. `SourceType.NAVER_IMAP` / `DAUM_IMAP` 라는 뚜렷한 enum 이 DTO 에 존재하나 UI 어느 경로에서도 provider 를 태깅하지 않아 두 소스가 영원히 "미연결" 상태로 남음.)
**Type**: Gap (provider selector 미구현) + Dead-code (ImapCredentialStore 는 이미 존재하나 연결선 없음)

---

## 1. Finding

`ImapSetupScreen.kt` 는 `.spec/contracts/ui-map.yml:59` 의 components 계약 `[ImapProviderSelector, UsernameField, AppPasswordField, ConnectButton, SkipButton]` 중 **UsernameField / AppPasswordField / ConnectButton / SkipButton** 만 구현. 핵심 누락:

1. **ImapProviderSelector 없음** — 현재 `host` 를 사용자가 직접 입력하는 generic form (`ImapSetupScreen.kt:120-149`). spec 은 Naver/Daum 중 택일 + 해당 provider 의 preset IMAP host (naver: `imap.naver.com:993`, daum: `imap.daum.net:993`) 가 자동 적용되어야 함.
2. **credentials 가 저장되지 않음** — `onSave = { /* TODO(BECALM-IMAP-001): persist credentials via ImapCredentialStore — deferred to next sprint */ ... }` (`ImapSetupScreen.kt:77-79`). 사용자 타이핑은 Composable local state 에만 존재 → 화면 이탈 시 증발.
3. **`SourceType.NAVER_IMAP`/`DAUM_IMAP` 이 UI 에서 선택되지 않음** — `SourceTypes.kt:25, 28` 에 enum 은 존재하나 화면이 어느 provider 인지 결정하지 않으므로 저장 시 sourceType string 을 붙일 수 없음. 결과적으로 `SourceStatusRepository` 에 두 row 가 영원히 생성되지 않음 (→ UI-EMAIL-013 과 연결).
4. **ONB-007 실패 경로 누락** — 저장 실패 (invalid credentials / network error) 시 Sentry breadcrumb + SKIPPED 처리 없음.

`ImapCredentialStore` (`data/local/secure/ImapCredentialStore.kt`) 는 이미 존재. 본 plan 은 **UI 만** 수정하고, `ImapCredentialStore` 의 per-provider API 확장은 별도 plan **#6 `repo-imap-per-provider-credentials.md`** (선행 blocker) 가 담당한다.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/contracts/ui-map.yml:56-60`
> "- path: /onboarding/imap / screen: ImapSetupScreen / data: [] / components: [**ImapProviderSelector**, UsernameField, AppPasswordField, ConnectButton, SkipButton] / auth: required"

### 2.2 `.spec/onboarding.spec.yml:110` — invariant
> "총 12단계: 약관 → 로그인 → PIPA제3자제공 → 녹음폴더 → 연락처 → Gmail → Outlook메일 → **IMAP** → Google캘린더 → Outlook캘린더 → 배터리최적화 → ColdSync"

Provider 는 **네이버 + 다음** 두 옵션 (한국 IMAP 사용자 대다수). Custom host 는 MVP 범위 밖 — UI 상 disabled segment 로 표시해 향후 확장을 인지시키되 기능은 막는다.

### 2.3 `.spec/onboarding.spec.yml:86-93` — ONB-007
> "온보딩 실패 이벤트가 Sentry에 전송된다 … OAuth 인증 실패 또는 권한 거부 발생 … `onboarding_step_failed` 이벤트"

IMAP 의 "실패" = 저장 단계에서 IMAP LOGIN 실패, 네트워크 오류, 잘못된 host 등. Skipped 나 Denied 가 아니므로 별도 분기.

### 2.4 `.spec/onboarding.spec.yml:106` — invariant
> "온보딩은 순차 진행이며 건너뛴 OAuth 소스는 설정 화면에서 재연결 가능하다"

IMAP 도 동일. LINK_IMAP=SKIPPED 이어도 Settings → Sources → `naver_imap` / `daum_imap` row 는 `NEVER_CONNECTED` 로 노출되어야 함 (row 초기화는 **#5** 가 담당).

### 2.5 `.spec/source-management.spec.yml:40-48` — SMG-004 (재연결 해제 시 cursor/credentials 처리)
> "소스 연결 해제 시 해당 소스의 DataStore cursor 초기화, Keystore credentials 삭제 … Keystore Gmail OAuth 토큰 삭제됨"

→ Credential 은 Keystore 에 provider 별로 저장 — naver 해제가 daum 에 영향 주면 안 됨. 따라서 ImapCredentialStore API 도 per-provider (key=sourceType) 가 필수 (#6 의 역할).

### 2.6 `.spec/email-pipeline.spec.yml:79` (간접)
> "EmailBody.body_plain/body_html/attachments_meta는 Railway·Supabase로 업로드 금지 (로컬 only)"

→ IMAP 본문은 디바이스가 직접 IMAP 서버에 연결해 수신. 따라서 app password 가 디바이스에만 저장되어야 함 — `EncryptedSharedPreferences` / Keystore 기반 `ImapCredentialStore` 사용이 맞음.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 Generic host form
- **`android/app/src/main/java/com/becalm/android/ui/onboarding/ImapSetupScreen.kt:120-149`**:
  ```kotlin
  BecalmTextField(value = host, ..., label = "onb_imap_host_label", placeholder = "onb_imap_host_placeholder")
  BecalmTextField(value = email, ..., label = "onb_imap_email_label")
  BecalmTextField(value = password, ..., label = "onb_imap_password_label", visualTransformation = PasswordVisualTransformation())
  ```
- Provider enum 선택 UI 없음. `SegmentedButton` 또는 라디오 그룹 grep = 0.

### 3.2 Credential 폐기 경로
- **`ImapSetupScreen.kt:77-79`**:
  ```kotlin
  onSave = {
      // TODO(BECALM-IMAP-001): persist credentials via ImapCredentialStore — deferred to next sprint
      viewModel.onMarkStepStatus(OnboardingStep.LINK_IMAP, StepStatus.COMPLETE)
      navController.navigate(BecalmRoute.OnboardingGoogleCalendar.path)
  },
  ```
- `OnboardingViewModel` 에 `saveImapCredentials(...)` 없음 (grep 0).
- `ImapCredentialStore` inject 경로 없음.

### 3.3 SourceType enum 은 준비됨
- **`android/app/src/main/java/com/becalm/android/data/remote/dto/SourceTypes.kt:18, 22, 25, 28`**:
  ```kotlin
  public const val GMAIL: String = "gmail"
  public const val OUTLOOK_MAIL: String = "outlook_mail"
  public const val NAVER_IMAP: String = "naver_imap"
  public const val DAUM_IMAP: String = "daum_imap"
  ```
- 그러나 `ImapSetupScreen` 어디에서도 참조되지 않음 (grep 0).

### 3.4 ImapCredentialStore 존재
- `android/app/src/main/java/com/becalm/android/data/local/secure/ImapCredentialStore.kt` 존재 — 단 per-provider API 여부는 **#6** 가 확정.

### 3.5 FLAG_SECURE 는 이미 적용됨 (변경 불필요)
- `ImapSetupScreen.kt:61-69` — DisposableEffect 로 window flag 설정. **이 동작은 보존**.

검증 grep:
```bash
grep -n "BECALM-IMAP-001" android/app/src/main/java/com/becalm/android/ui/onboarding/ImapSetupScreen.kt
grep -rn "saveImapCredentials\|ImapCredentialStore" android/app/src/main/java/com/becalm/android/ui/onboarding/
grep -rn "SegmentedButton\|MultiChoiceSegmentedButton" android/app/src/main/java/com/becalm/android/ui/onboarding/
grep -rn "NAVER_IMAP\|DAUM_IMAP" android/app/src/main/java/com/becalm/android/ui/onboarding/
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| provider selector | ImapProviderSelector (Naver/Daum 택일) | freeform host 입력 | SegmentedButton 추가 |
| host field | 자동 설정 (provider → preset) | 사용자 타이핑 | host field 삭제, provider 선택이 host 를 주입 |
| credential 저장 | ImapCredentialStore.save(sourceType, host, email, appPassword) | TODO + state 폐기 | VM 메서드 + Store inject 필요 |
| SourceType 태깅 | naver_imap / daum_imap 중 하나 | 태깅 안 됨 | selector 값 → sourceType 문자열 매핑 |
| 저장 실패 처리 | Sentry `onboarding_step_failed` + SKIPPED | 실패 경로 없음 | 실패 분기 추가 |
| LINK_IMAP → COMPLETE 조건 | 저장 성공 시에만 | 즉시 COMPLETE | 성공 콜백에서만 마킹 |
| Custom host (post-MVP) | disabled segment | — | disabled chip 추가 |

---

## 5. Proposed Fix

### 5.1 Files to change
- **`android/app/src/main/java/com/becalm/android/ui/onboarding/ImapSetupScreen.kt`** — 재설계:
  - `host` state 제거. 대신 `selectedProvider: ImapProvider` state.
  - `Material3 SegmentedButtonRow` 3-segment: 네이버 / 다음 / 직접 입력(disabled). 디폴트 선택 = 네이버.
  - username + app password 필드는 유지 (label 만 provider-specific 로 교체 — "네이버 아이디 @naver.com" 등).
  - `onSave` 람다 → `viewModel.saveImapCredentials(selectedProvider.sourceType, selectedProvider.host, selectedProvider.port, username, appPassword)`.
  - VM 의 결과 Flow (`imapSaveResult: Flow<ImapSaveResult>`) 를 `LaunchedEffect` 로 구독. `Success` → SnackBar "연결 성공" + navigate. `Failure(e)` → SnackBar 에러 문자열 (string resource).
  - FLAG_SECURE `DisposableEffect` 는 보존.
- **`android/app/src/main/java/com/becalm/android/ui/onboarding/OnboardingViewModel.kt`** —
  - DI 에 `ImapCredentialStore` inject (#6 의 per-provider API 사용).
  - 신규 `fun saveImapCredentials(sourceType: String, host: String, port: Int, username: String, appPassword: String)`:
    - viewModelScope.launch
    - try: `imapCredentialStore.save(sourceType, ImapCredentials(host, port, username, appPassword))`. 저장 전 간단 validation (empty check, `@` 포함 여부 — IMAP 자체 LOGIN 검증은 post-MVP 로 **첫 동기화 시 확인**; 본 plan 은 저장까지만 동기 수행).
    - catch: Sentry breadcrumb `onboarding_step_failed` (step="LINK_IMAP", error=e.javaClass.simpleName) + emit Failure.
    - 성공 시: `sourceStatusRepository.markConnected(sourceType)` (if API 존재) **또는** initial row seed 는 **#5 의 `initializeDefaults` 에 위임** → 본 plan 에서는 상태 row 를 건드리지 않고 credential 만 저장.
    - `onMarkStepStatus(LINK_IMAP, COMPLETE)` 는 성공 시에만.
    - 실패 시: `onMarkStepStatus(LINK_IMAP, SKIPPED)` — 사용자가 재시도 가능하도록 SnackBar 표시 후 화면에 머무르게 할지, 아니면 바로 SKIPPED 처리할지 선택 지점. **권고: SnackBar + 화면 유지 (재시도 친화적)**. 사용자가 [스킵] 눌러야 navigation.

### 5.2 Files to add
- **`android/app/src/main/java/com/becalm/android/ui/onboarding/ImapProvider.kt`** — sealed class:
  ```
  sealed class ImapProvider(val sourceType: String, val host: String, val port: Int, val displayNameRes: Int) {
      object Naver : ImapProvider("naver_imap", "imap.naver.com", 993, R.string.onb_imap_provider_naver)
      object Daum  : ImapProvider("daum_imap",  "imap.daum.net",  993, R.string.onb_imap_provider_daum)
  }
  ```
  - preset host/port 는 이 파일에 **유일 source of truth**. worker 레이어 (#6) 는 runtime 에서 ImapCredentialStore 의 저장된 값을 읽으므로 여기의 preset 을 참조할 필요 없음.
- **`android/app/src/main/res/values/strings.xml`** + **`values-ko/strings.xml`**:
  - `onb_imap_provider_naver = "네이버"`, `onb_imap_provider_daum = "다음"`, `onb_imap_provider_custom = "직접 입력"`, `onb_imap_provider_custom_unavailable = "직접 입력은 추후 지원 예정입니다"`, `onb_imap_username_hint_naver = "naver.com 아이디"`, `onb_imap_username_hint_daum = "daum.net 아이디"`, `onb_imap_error_save_failed = "저장 중 오류가 발생했습니다. 다시 시도해주세요."`, `onb_imap_error_network = "네트워크 연결을 확인해주세요."`, `onb_imap_success = "%1$s 이메일이 연결되었습니다"`.
- **tests**:
  - `OnboardingViewModelTest.saveImapCredentials_success_persistsAndMarksComplete` — fake `ImapCredentialStore.save` 호출 검증, LINK_IMAP=COMPLETE.
  - `OnboardingViewModelTest.saveImapCredentials_failure_emitsSentryAndMarksSkipped` — exception-throwing fake, Sentry breadcrumb, LINK_IMAP=SKIPPED.
  - (선택) `ImapSetupScreenTest` (compose UI test) — SegmentedButton 선택 전환 시 username placeholder 가 naver/daum 로 바뀌는지.

### 5.3 Files to delete (dead code)
- **없음**. `ImapSetupScreen.kt` 의 host TextField 는 **삭제**되지만 이는 수정 파일 내부. 별도 delete 파일 없음.
- string 자원 `onb_imap_host_label`, `onb_imap_host_placeholder` 는 **본 plan 에서는 삭제하지 않고** 주석으로 deprecated 표시 — 향후 "직접 입력" 재활성 시 재사용 가능. (Karpathy surgical 원칙 — 동일 PR 내 cleanup 범위 최소화.)

### 5.4 Non-code changes
- `ImapCredentialStore` 의 API (sourceType 를 key 로 받는 `save` 오버로드) 는 **#6** 가 제공. 본 plan 은 그 API 시그니처를 기대만 한다:
  ```
  suspend fun save(sourceType: String, credentials: ImapCredentials)
  suspend fun clear(sourceType: String)
  suspend fun get(sourceType: String): ImapCredentials?
  ```
- DB 마이그레이션 없음.
- Permission 변경 없음.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -n "BECALM-IMAP-001" android/app/src/main/java/com/becalm/android/ui/onboarding/ImapSetupScreen.kt` = 0
- [ ] **Grep invariant**: `grep -n "NAVER_IMAP\|DAUM_IMAP" android/app/src/main/java/com/becalm/android/ui/onboarding/ImapProvider.kt | wc -l` ≥ 2
- [ ] **Grep invariant**: `grep -rn "SegmentedButton" android/app/src/main/java/com/becalm/android/ui/onboarding/ImapSetupScreen.kt | wc -l` ≥ 1
- [ ] **Grep invariant**: `grep -rn "imapCredentialStore\.save\|ImapCredentialStore" android/app/src/main/java/com/becalm/android/ui/onboarding/OnboardingViewModel.kt | wc -l` ≥ 1
- [ ] **Unit test**: `OnboardingViewModelTest.saveImapCredentials_success_persistsAndMarksComplete` 통과
- [ ] **Unit test**: `OnboardingViewModelTest.saveImapCredentials_failure_emitsSentryAndMarksSkipped` 통과
- [ ] **Compile gate**: `./gradlew :app:assembleDebug :app:testDebugUnitTest` 성공
- [ ] **Manual**: 네이버 선택 시 host label 이 숨겨지고 username placeholder 가 "naver.com 아이디" 로 변경됨. 다음 선택 시 동일 패턴. 직접 입력 segment 는 tap 무반응 (disabled).
- [ ] **Manual FLAG_SECURE**: 화면 재확인 — screenshot 시도 시 검은 화면 (기존 동작 유지).

---

## 7. Out of Scope

- **IMAP LOGIN 실시간 검증** — 저장 시 실제 IMAP 서버 connect + LOGIN 시도는 후속 worker 실행 시점에서. 본 plan 은 credential 저장 성공 = COMPLETE.
- **직접 입력(Custom) provider 활성** — disabled segment 로만 UI 표시. 기능 활성화는 post-MVP.
- **`ImapCredentialStore` per-provider API 구현** — PR #6 `docs/plans/repo-imap-per-provider-credentials.md`.
- **SourceStatusRow 초기화** (naver_imap / daum_imap 에 NEVER_CONNECTED seed) — `docs/plans/ui-sources-detail-actions-and-localization.md` (#5) 의 `initializeDefaults()` 에서.
- **PIPA IMAP 전용 동의** — `docs/plans/ui-onboarding-pipa-email-consent.md` (#15). 본 plan 은 PIPA 동의 수집이 이미 완료된 상태 전제.
- **OAuth 개편** (#12 Gmail / #13 Outlook) — 별도 plan.
- **Settings → Sources 에서 IMAP 재연결** — `docs/plans/ui-sources-detail-actions-and-localization.md`.

---

## 8. Dependencies

- **Blocked by**: PR #6 — `fix/repo/imap` 브랜치, `ImapCredentialStore` 의 per-provider API. 본 plan 은 그 시그니처를 기대. #6 은 plan doc 작성 후 별도 세션에서 구현.
- **Blocked by (soft)**: #12 Gmail + #13 Outlook — 같은 `OnboardingViewModel` 에 sentryClient inject 를 먼저 도입. 본 plan 은 그 DI 를 재사용.
- **Blocks**:
  - `docs/plans/ui-sources-detail-actions-and-localization.md` (#5) — 본 plan 이 credential 저장 경로를 마련해야 Settings 쪽 disconnect 가 의미 있음.

merge 순서: **#6 → #12 → #13 → 본 plan → #15 (PIPA) → #5 sources-detail**. 같은 `feat/ui/onboarding` 브랜치에 순차 커밋.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

revert 후:
- `ImapSetupScreen` 은 다시 freeform host form 으로 복귀.
- 이미 저장된 credential (`ImapCredentialStore` 내) 는 **그대로 유지** — revert 는 UI 쓰기 경로만 되돌림. 기존 `naver_imap` / `daum_imap` row 는 SourceStatusRepository 에서 Worker 동작을 통해 status=CONNECTED 유지.
- `ImapProvider.kt` 삭제 시 strings.xml 의 신규 key 는 고아 — 빌드 경고만 발생 (에러 아님). 필요시 후속 commit 으로 cleanup.

체크:
1. `onb_imap_host_label` 등 deprecated-comment 달아뒀던 string 이 revert 후 정상 사용 복원.
2. compile 성공.
3. `OnboardingViewModelTest` 의 두 신규 케이스만 제거됨.

---

## Appendix — Session handoff notes

- **왜 Naver + Daum 만**: 한국 IMAP 사용자의 90% 이상 커버. Gmail/Outlook 은 OAuth 경로가 별도 (#12, #13). 그 외 (네이트, 한메일 등) 는 사용자 피드백 이후 결정.
- **왜 host 를 preset 으로 강제**: generic host/port 조합에서 사용자 오타 → 연결 실패 → 온보딩 포기. spec 의 ImapProviderSelector 는 "provider 단위 UX" 를 의미.
- **Username placeholder 동적 변경**: `selectedProvider` state 에 `remember` + `derivedStateOf` 로 placeholder string resource id 를 계산. Composable recomposition 비용 무시 가능.
- **SSL/TLS 포트 993 고정**: naver/daum 모두 993 + STARTTLS 불필요 (implicit TLS). ImapProvider.port 는 Int 상수.
- **Sentry breadcrumb payload**: PIPA 상 host / username / password 는 breadcrumb 에 포함 금지. `sourceType` 과 `errorClass` 만.
- **ImapSaveResult sealed 설계**: `Success` / `Failure(errorStringRes: Int)` — errorStringRes 는 VM 이 throwable → string resource 매핑 후 emit (screen 쪽은 `stringResource()` 호출만). 이로써 테스트가 Throwable 종류에 독립적.
- **첫 동기화 전 검증 미루기**: worker 가 실제 LOGIN 을 시도 → 실패 시 `SourceStatusRepository.markError("invalid_credentials")` → Sources 화면이 빨간 경고 표시. 이 경로는 **#5 의 error taxonomy** 와 **worker 레이어 plan** 이 함께 실현.
- **SegmentedButton 접근성**: `Modifier.semantics { role = Role.RadioButton }` 로 TalkBack 지원. 본 plan 은 구현 세션에게 이를 상기시킴.
- **전체 규모 추정**: UI 2 파일 + VM 1 파일 수정, 신규 ImapProvider.kt 1 파일, strings 다수, 2 테스트. 1 세션 완주 가능.
