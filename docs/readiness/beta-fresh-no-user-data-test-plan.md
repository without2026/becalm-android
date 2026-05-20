# Beta Fresh No-User-Data Test Plan

날짜: 2026-05-16 KST

목표: 기존 로컬 앱 상태나 기존 backend user graph에 기대지 않고, 10명 / 5일
controlled beta에서 첫 사용자가 겪는 경로를 처음부터 검증한다.

## 현재 기준

- Backend Gmail/Naver mixed private live eval: `500 / 500 pass`.
- Android Samsung 실기기 smoke: 최신 APK 설치, 실행, Gmail/Google Calendar connected
  state 유지, force-stop 후 재실행 통과.
- Fresh local reset smoke: 2026-05-16 KST에 Samsung SM-F721N에서
  `qa/device/scripts/fresh_no_user_data_smoke.sh` 통과. Terms -> Login 진입도 통과.
  Google login button은 최신 APK에서 활성화되고 CredentialManager를 호출하지만,
  기기에서는 Google 계정 설정이 필요하다는 사용자-facing 메시지가 보였다. 같은 시도에서
  logcat에는 Google Play Services의 `[28444] Developer console is not set up correctly`
  및 Android OAuth client 등록 불일치 메시지도 함께 남아 로그인 완료는 blocked 상태다.
  Google 계정 추가 후 재시도에서는 계정 없음 복구 경로가 아니라 Android OAuth client 등록
  불일치 오류가 재현되어 blocker가 Google Cloud Console 설정으로 좁혀졌다.
- 남은 gap: fresh user가 로그인부터 source OAuth, sync, extraction, self identity
  anchor, Today/People/Commitments 표시까지 사용자 중심으로 수렴하는지 증명해야 한다.

## Gap 5 이후 다음 Slice

500개 live eval 통과 이후 gap 5의 다음 slice는 prompt를 더 늘리는 일이 아니다. 현재
상태를 고정하고, fresh user E2E에서 실제 실패 지점을 찾는 순서로 간다.

1. Eval evidence freeze
   - 500개 report summary를 readiness 문서에 기록한다.
   - prompt/rule 변경은 여기서 멈추고, 이후 변경은 failing fixture 또는 blocker
     class가 있을 때만 한다.
   - private report 원문은 PII 가능성이 있으므로 git에 올리지 않는다.

2. Fresh no-user-data Android run
   - 앱 데이터를 지우고 첫 화면부터 시작한다.
   - 가능하면 backend도 새 tester auth user를 사용한다.
   - 같은 이메일을 재사용해야 하면 backend user rows reset 또는 account deletion
     path가 필요하다. 로컬 `pm clear`만으로는 backend no-user-data가 아니다.

3. Required source E2E
   - Google login 또는 email/password login.
   - Onboarding 필수 동의.
   - Gmail connect -> provider consent -> app return -> status connected.
   - Google Calendar connect -> provider consent -> app return -> status connected.
   - 앱 재시작 후 connected state 유지.

4. First sync and extraction proof
   - Gmail/Calendar sync가 source status에 반영된다.
   - Today/People/Commitments에 항목이 생기거나, 데이터가 없으면 empty state가 명확하다.
   - 실패 시 source status, product event, logcat, backend dashboard에서 원인이 보인다.

5. Self identity proof
   - Auth email, provider account email, user profile alias/phone, calendar attendee,
     meeting self speaker 중 확인 가능한 anchor가 `self_identity_anchors`로 잡힌다.
   - self로 판정되어야 하는 email/attendee/speaker가 counterparty person으로 생성되지
     않는다.
   - give/take direction이 self 기준으로 맞다.

## 실행 전제

### Google sign-in console prerequisite

Fresh E2E는 Google 로그인에서 시작하므로 Google Cloud Console 설정이 먼저 맞아야 한다.

현재 debug APK 기준:

- Package: `com.becalm.android`
- Debug SHA-1: `99:E0:79:3C:F2:E2:E5:C2:96:FE:A8:4E:45:75:29:DB:CA:AC:7F:5E`
- Debug SHA-256: `D9:D5:B5:6C:A6:CA:5A:71:CD:4F:65:6D:40:9C:5F:E6:F5:25:DC:27:FC:DA:B7:14:AB:E8:65:54:9C:07:8A:F0`

Google Cloud Console에서 같은 project 안에 Android OAuth client가 위 package/SHA-1로
등록되어 있어야 한다. 앱의 `google.web.client.id`는 같은 project의 Web client ID여야
한다. 이 조건이 맞지 않으면 Android CredentialManager는 account picker를 열기 전에
`Developer console is not set up correctly`로 실패한다.

현재 로컬 파일 기준으로 확인된 불일치:

- `android/app/google-services.json`의 Firebase project number는 `648339651268`이다.
- `android/app/google-services.json`의 `oauth_client` 배열은 비어 있다.
- `android/local.properties`의 `google.web.client.id`는 다른 project number prefix를
  가진 Web client ID로 보인다.
- 2026-05-16 KST 재검증에서 사용자-facing UI는 기기에 Google 계정을 추가하라는 복구
  메시지를 보였다. 같은 시도에서 Google Play Services는
  `This android application is not registered to use OAuth2.0`를 반환했다. 즉 fresh E2E
  재개 전에는 테스트 기기에 사용할 Google 계정이 Android Settings 계정으로 추가되어
  있어야 하고, package name / SHA-1 Android OAuth client 등록도 현재 CredentialManager
  호출 기준과 맞아야 한다.
- 테스트 기기에 Google 계정을 추가한 뒤 같은 APK에서 재시도했을 때도 Google Play Services가
  동일한 OAuth2 등록 오류와 `[28444]`를 반환했다. 따라서 현재 남은 blocker는 package
  `com.becalm.android` / debug SHA-1 Android OAuth client가 `google.web.client.id`와
  같은 Google Cloud project에 등록되어 있지 않은 문제다.
- package/SHA-1을 GCP에 추가하고 재빌드한 뒤에는 CredentialManager의 Google 계정 선택 UI가
  열렸다. 그러나 선택 후 Google Play Services가
  `You must use a Web client as the server client ID`를 반환했다. 즉 Android OAuth client
  등록은 통과했지만, `google.web.client.id`에 넣은 값은 GCP에서 type이 `Web application`
  인 OAuth client ID여야 한다. Android OAuth client ID를 `setServerClientId`에 넣으면
  이 단계에서 다시 `[28444]`로 실패한다.
- 기존 Web application client ID로 교체한 뒤에는 Google 계정 선택과 앱 복귀가 통과했다.
  다음 blocker는 Supabase Auth가 400
  `Provider (issuer "https://accounts.google.com") is not enabled`를 반환하는 것이다.
  따라서 Supabase Auth Providers에서 Google을 활성화하고 같은 Web client ID/client secret을
  등록해야 한다. 앱도 이 400 setup failure를 더 이상 네트워크 오류로 표시하지 않도록
  `google_provider_disabled`로 매핑한다.
- Supabase Auth Google provider를 활성화한 뒤 fresh reset에서 Google CredentialManager
  account picker, 계정 선택, 앱 복귀, Supabase Google sign-in이 모두 통과했다. 앱은 로그인
  후 onboarding source selection 화면으로 진입했다.
- Gmail OAuth는 provider 브라우저 동의와 backend callback/status가 통과했다. Logcat에서
  `/v1/oauth/mail/gmail:status`가 처음에는 callback 완료 전 `connected=false`를 반환했고,
  약 2초 뒤 `connected=true`, `LINK_GMAIL -> COMPLETE`, `source_connections` refresh,
  `onboarding_email_connected` event까지 확인되었다. 이후 Setup entry 화면이 완료된
  step state를 무시하고 기본 `동의 필요` 상태를 다시 projected하는 Android UI bug를
  발견했고, Setup/Onboarding은 step state를 존중하고 Settings만 재연결을 위해 과거 step
  state를 무시하도록 수정했다. 최신 APK 재설치 후 같은 기기/계정 상태에서 Gmail은
  `연결됨`으로 표시된다.
- 같은 projection bug는 Google Calendar에도 적용될 수 있다. `LINK_GOOGLE_CALENDAR`가
  `COMPLETE`여도 Setup entry가 step state를 무시하면 다시 `준비됨`처럼 보일 수 있었기
  때문이다. Setup source projection test에 Google Calendar complete -> `연결됨` 케이스를
  추가했고, 첫 onboarding Setup 화면의 추천 섹션 아래에 Google Calendar를 노출하도록
  바꿨다. Outlook Calendar는 controlled beta의 기본 첫 화면 부담을 줄이기 위해 Settings
  source connection 경로에 남긴다.
- Google Calendar OAuth는 사용자 실기기 확인과 logcat 기준으로 pass했다.
  `/v1/oauth/calendar/google_calendar:status`가 `connected=true`를 반환했고,
  `LINK_GOOGLE_CALENDAR -> COMPLETE`, `GoogleCalendarWorker SUCCESS`,
  `source_sync_completed` event까지 확인했다. Calendar sync 결과는 `synced=0`으로,
  현재 tester calendar에서는 가져올 event가 없거나 filtered empty 상태에 가깝다.
- First sync proof에서 발견한 Gmail blocker는 수정 후 pass로 재검증했다.
  `42P10`은 `source_events` upsert를 stable `id` 기준으로 바꿔 제거했고, 실제 tester
  Gmail 데이터에서 추가로 드러난 `source_event_participants` self-resolution constraint
  위반은 active self anchor가 없는 inferred self participant를 `suggested_self`로 저장해
  해결했다. 최종 Samsung 실기기 manual sync 결과는 `/v1/mail_sources:sync?provider=gmail`
  200, raw Gmail 3건, source participant 13건, commitment 7건 반영이다. Android는 sync
  성공 후 `source_connections`, `self_identity_anchors`, `source_status`를 모두 refresh한다.
  로컬 mirror 최종 상태는 `google/mail synced`, `google/calendar connected`이며 stale
  `failed`/fallback duplicate row는 canonical `/v1/source_connections` 응답에서 제거된다.

따라서 fresh E2E를 계속하려면 둘 중 하나로 맞춰야 한다.

1. `becalm-e4ddc` / `648339651268` project에 Android OAuth client와 Web OAuth client를
   모두 만들고, 그 Web client ID로 `google.web.client.id`를 교체한다.
2. 현재 `google.web.client.id`가 속한 project에 package `com.becalm.android`와 위
   debug SHA-1의 Android OAuth client를 만들고, 앱의 Firebase/Google config도 같은
   project로 맞춘다.

### Android local reset

실기기에서 local no-user-data 상태를 만들려면 앱 데이터를 삭제한다.

```bash
qa/device/scripts/fresh_no_user_data_smoke.sh \
  --adb /mnt/c/Users/jakek/AppData/Local/Android/Sdk/platform-tools/adb.exe \
  --device R5CT83SMP4P \
  --confirm-clear-data
```

이 명령은 `pm clear com.becalm.android`를 실행한다. 현재 기기의 앱 로그인, local Room
DB, encrypted local token, DataStore 상태가 삭제된다.

### Backend clean user

완전한 no-user-data 테스트는 backend도 깨끗해야 한다. 추천 순서는 다음이다.

1. beta 전용 신규 tester 계정을 만든다.
2. 신규 계정으로 Google/Gmail/Calendar 권한 동의를 진행한다.
3. 같은 계정을 반복 테스트해야 하면 account deletion 또는 admin reset 절차로 아래
   user-scoped rows를 제거한 뒤 다시 시작한다.

검증해야 하는 backend scope:

- `source_connections`
- `self_identity_anchors`
- `user_profiles`
- `source_status`
- `raw_ingestion_events`
- `source_event_participants`
- `persons`, `person_identities`, `person_interactions`
- `commitments`, `commitment_participants`
- `calendar_events`, `schedule_event_links`
- `product_events`, `pmf_survey_responses`

## Manual QA Checklist

| 단계 | 수락 기준 | 증거 |
|---|---|---|
| Fresh launch | 로그인/약관 또는 first-run 화면으로 진입. Fatal/ANR/OOM 없음. | script report `summary.env`, `ui-initial.xml`, `logcat-initial.txt` |
| Login | Google login은 Credential Manager UI를 열거나 email/password login이 성공한다. | 화면 녹화 또는 uiautomator dump, auth user id |
| Onboarding | 필수 동의와 source connection 화면을 지나 Today로 갈 수 있다. | 화면 dump, local state |
| Gmail OAuth | provider consent 후 앱으로 돌아오고 Gmail이 connected/synced로 보인다. | source UI, `/oauth/mail/gmail:status`, product event |
| Google Calendar OAuth | provider consent 후 앱으로 돌아오고 Google Calendar가 connected/synced로 보인다. | source UI, `/oauth/calendar/google_calendar:status`, product event |
| Restart persistence | force-stop 후 재실행해도 connected state가 유지된다. | UI dump |
| First sync | source sync start/completed/failure가 관측된다. | source status, dashboard view |
| Extraction | 업무 메일/캘린더가 있으면 commitment/person row가 생긴다. 없으면 empty state가 명확하다. | Today/People/Commitments |
| Self anchor | provider account identity가 self anchor로 잡히고 self가 counterparty로 생성되지 않는다. | backend self/source/person rows |
| Privacy | logcat/product events에 email body, token, full source title/quote가 없다. | sanitized log scan |

## Pass/Fail 기준

Pass:

- required source인 Gmail과 Google Calendar가 fresh 상태에서 연결되고, restart 후에도
  connected state가 유지된다.
- 실패나 empty 상태가 UI와 dashboard에서 관측된다.
- self identity anchor가 provider account 기준으로 생성되고, self/counterparty가
  뒤집히지 않는다.
- fatal/ANR/OOM이 없다.

Conditional:

- source 연결은 되지만 sync/extraction이 지연되고 dashboard에서 원인을 볼 수 있다.
- data가 없는 tester mailbox/calendar라서 extraction proof가 부족하다.

Fail:

- Google login 버튼 또는 source connect 버튼이 아무 동작도 하지 않는다.
- OAuth provider consent 후 app return/status connected로 수렴하지 않는다.
- self identity가 counterparty person으로 생성된다.
- wrong person, wrong give/take, invented due date, privacy leakage가 fresh path에서
  발생한다.
- 실패가 UI/dashboard/log 어디에도 보이지 않는다.

## 산출물

각 fresh run은 아래를 남긴다.

- `qa/device/reports/fresh-no-user-data-*/summary.env`
- `qa/device/reports/fresh-no-user-data-*/ui-initial.xml`
- `qa/device/reports/fresh-no-user-data-*/logcat-initial.txt`
- manual notes: source OAuth, backend rows, dashboard query 결과
- PII 없는 issue summary: source, phase, expected, actual, blocker class
