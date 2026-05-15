# 현 코드베이스 기반 제품 목표와 기술 갭 QA

날짜: 2026-05-16 KST

범위: 현재 `becalm-android`, `becalm-backend` 코드베이스를 새로 읽고 작성한
리뷰다. 저장소 밖의 제품 설명이나 의도는 근거로 사용하지 않았다.

## QA 요약

현재 코드만 기준으로 보면 BeCalm은 "업무 관계 기억 + 약속 관리" 앱이다.
사용자가 승인한 업무 증거 소스에서 action/schedule/decision을 추출하고,
사람 중심 관계 메모리로 정리한 뒤 Today, People, Commitments 화면에서
오늘 기억하거나 처리해야 할 일을 보여주는 제품이다.

10명 / 5일 beta의 추천 범위:

- 필수 소스: Gmail, Google Calendar, voice/meeting audio, manual/evidence
  import.
- 선택 소스: Outlook Mail, Outlook Calendar, IMAP, screenshots.
- beta promise: 완전 자동 정답 보장이 아니라, "승인된 업무 소스에서
  관계/약속 기억을 만들고 사용자가 검토/수정할 수 있다"로 제한.
- blocker급 오류: wrong person, wrong give/take, invented due date, privacy
  leakage.
- 사용자 QA에서 확인된 핵심 gap: person matching은 단순히 동명이인/alias를
  잘못 합치는 문제가 아니라, 시스템이 "현재 사용자 본인(self)이 누구인지"를
  충분히 모르기 때문에 모든 관계/약속이 사용자 중심으로 정렬되지 않는
  self-anchoring 결함을 포함한다.
- 운영 필수 관측: crashes, OAuth failures, source sync failures, extraction
  failures/filtered counts, unresolved participants, PMF responses.

현재 점수: **7.73 / 10, conditional go with live self-anchoring proof required**.

조건:

1. 현재 commit 기준 Android test/lint/release build/backend tests/emulator
   smoke 재실행.
2. 실제 기기에서 필수 OAuth source의 provider round trip, 앱 복귀, status
   refresh, sync enqueue, restart 후 connected 상태 유지 확인.
3. source connection/extraction funnel analytics 추가.
4. extraction/person matching golden eval 기준 1차 반영. Backend deterministic
   severe fixture와 beta eval gate runner가 wrong person, wrong
   self/counterparty, wrong give/take, invented due date, privacy leakage,
   noisy automation blocker class를 포함한다. 2026-05-16 KST 기준 Gmail/Naver
   mixed private live eval 500건은 `500 / 500 pass`다. 남은 조건은 이 결과를
   fresh no-user-data E2E와 self identity/person matching proof에 연결하는 것이다.
5. beta 운영 runbook과 dashboard view 적용 확인.

아직 완료되지 않은 부분:

- 사용자와 beta scope/quality bar/support/release 기준 QA.
- 그 QA 결과를 반영한 문서 또는 코드 보완.

반영 완료된 QA:

- Person matching/self anchor gap. 사용자가 누구인지 모르는 상태에서 사람/관계/약속이
  사용자 중심으로 정리되지 않는 문제가 manual QA에서 확인되었고, 이 문서의 주요
  기술 gap과 score에 반영했다.
- Source connection/extraction funnel analytics와 beta dashboard/runbook. Android/backend
  analytics contract가 source OAuth, status refresh, sync, extraction event를 수집하도록
  확장되었고, Supabase dashboard view와 운영 runbook이 추가되었다.
- Extraction golden eval 1차 live proof. Backend private Gmail/Naver mixed 500건
  phase-aware live eval이 `500 / 500 pass`로 끝났다. Private report 원문은 PII 가능성이
  있어 git에 올리지 않고 summary만 readiness evidence로 기록한다.
- Samsung 실기기 source-state smoke. Windows Android SDK `adb.exe` 경로로
  SM-F721N에 최신 APK를 설치/실행했고, Gmail과 Google Calendar가 connected/synced로
  표시되며 force-stop 후 재실행해도 상태가 유지되는 것을 확인했다.
- Fresh local reset smoke. 최신 debug APK로 `pm clear com.becalm.android` 후 Terms ->
  Login 화면까지 fatal/ANR/OOM 없이 진입했다. Google button은 활성화되고
  CredentialManager 호출까지 진행된다. 기기에서는 Google 계정 설정이 필요하다는
  사용자-facing 메시지가 보였고, 같은 시도에서 Google Play Services가 `[28444] Developer
  console is not set up correctly`를 반환해 Google sign-in 완료는 blocked다. 현재 debug
  signing SHA-1은 `99:E0:79:3C:F2:E2:E5:C2:96:FE:A8:4E:45:75:29:DB:CA:AC:7F:5E`다.
  추가 확인 결과 `google-services.json`의 Firebase project number는 `648339651268`이고
  `oauth_client`가 비어 있지만, `local.properties`의 Web client ID는 다른 project number
  prefix를 가진다. 2026-05-16 KST 재검증에서 Google Play Services는
  `This android application is not registered to use OAuth2.0`를 반환했다. Google sign-in
  E2E는 Google Cloud Console에서 Android OAuth client와 Web client를 같은 project로
  정렬한 뒤 재검증해야 한다. 테스트 기기에 Google 계정을 추가한 뒤 재시도해도 동일한
  OAuth2 등록 오류가 남아, 현재 blocker는 기기 계정 부재가 아니라 Console의 Android
  OAuth client/package/SHA-1 등록 문제로 좁혀졌다. 이후 package/SHA-1을 GCP에 추가하고
  재빌드하자 CredentialManager의 Google 계정 선택 UI는 열렸지만, 선택 후
  `You must use a Web client as the server client ID`가 반환되었다. 현재 남은 blocker는
  `google.web.client.id`에 Android client가 아니라 같은 project의 `Web application`
  OAuth client ID를 넣는 것이다. 기존 Web application client ID로 교체한 뒤에는 Google
  계정 선택과 앱 복귀가 통과했고, Supabase Auth가 400
  `Provider (issuer "https://accounts.google.com") is not enabled`를 반환했다. 현재
  남은 외부 설정 blocker는 Supabase Auth Providers에서 Google을 활성화하고 같은 Web
  client ID/client secret을 등록하는 것이다. 앱 쪽은 이 provider-disabled setup failure를
  네트워크 오류가 아니라 Google 로그인 설정 필요 메시지로 매핑하도록 보완했다. Supabase
  Auth Google provider 활성화 후 fresh reset에서 Google CredentialManager account picker,
  계정 선택, 앱 복귀, Supabase Google sign-in이 통과했고, 앱은 onboarding source selection
  화면으로 진입했다.
- Gmail OAuth 브라우저 동의 후 backend callback/status는 성공했다. Logcat 기준
  `/v1/oauth/mail/gmail:status`가 callback 완료 전에는 `connected=false`를 반환했지만,
  약 2초 뒤 `connected=true`, `LINK_GMAIL -> COMPLETE`, `source_connections` refresh,
  `onboarding_email_connected` event로 수렴했다. 사용자가 본 `동의 필요` 재표시는 backend
  연결 실패가 아니라 Setup entry 화면이 완료된 source step state를 무시하고 기본
  `ConsentRequired`를 projected한 Android UI bug였다. Setup/Onboarding은 step state를
  존중하고 Settings만 재연결을 위해 과거 step state를 무시하도록 수정했으며, 최신 APK를
  같은 Samsung 기기에 재설치한 뒤 Gmail이 `연결됨`으로 표시되는 것을 확인했다.
- 같은 Setup projection bug는 Google Calendar에도 영향을 줄 수 있다. Calendar callback/status가
  완료되어 `LINK_GOOGLE_CALENDAR -> COMPLETE`가 되더라도 Setup entry가 step state를
  무시하면 사용자는 다시 `준비됨` 상태를 볼 수 있었다. Google Calendar complete state가
  Setup에서 `연결됨`으로 projected되는 단위 테스트를 추가했고, 첫 onboarding Setup 화면의
  추천 섹션 아래에 Google Calendar를 노출하도록 바꿨다. Outlook Calendar는 Settings
  source connection 경로에 남긴다.
- Google Calendar OAuth는 사용자 실기기 확인과 logcat 기준으로 통과했다.
  `/v1/oauth/calendar/google_calendar:status connected=true`, `LINK_GOOGLE_CALENDAR -> COMPLETE`,
  `GoogleCalendarWorker SUCCESS`, `source_sync_completed` event를 확인했다. Calendar sync는
  `synced=0`으로 끝나 현재 tester calendar에서는 empty/filtered 상태로 보인다.
- Gmail first sync는 같은 Samsung 기기에서 manual sync 경로로 재검증했고 pass했다.
  이전 blocker였던 `42P10`은 `source_events` upsert를 stable `id` 기준으로 바꿔 제거했다.
  그 다음 실제 데이터에서 `source_event_participants` self-resolution constraint 위반이
  드러나 `relation_to_user=self`이지만 active self anchor가 없는 participant를
  counterparty person으로 만들지 않고 `suggested_self`로 저장하도록 backend relation
  builder를 수정했다. 최종 logcat 기준 `POST /v1/mail_sources:sync?provider=gmail`는
  200, raw 3건, source participant 13건, commitment 7건이 Android 로컬 DB에 반영되었다.
  `SourceSyncPort`는 sync 성공 후 `source_connections`, `self_identity_anchors`,
  `source_status`를 모두 refresh한다. 로컬 `source_connections` 최종 상태는
  `google/mail synced`, `google/calendar connected`로 수렴했고 stale `failed`/fallback
  duplicate row는 재현되지 않았다.

## 코드 기반 제품 목표

BeCalm은 한국 B2B 업무 관계를 대상으로 하는 Samsung-first Android
assistant다. 현재 코드가 실제로 뒷받침하는 제품 목표는 다음이다.

> 사용자가 승인한 업무 증거 소스를 연결하고, 추적 가능한
> action/schedule/decision을 추출하며, 사람 중심 관계 메모리를 만들고,
> 사용자가 오늘 기억하거나 처리해야 할 일을 source provenance, 동의 제어,
> beta 운영에 필요한 telemetry와 함께 보여준다.

이 목표는 문서상 의도가 아니라 실제 코드 근거를 가진다.

- Android README는 전화, 오프라인 미팅, 이메일, 캘린더에서 give/take 업무
  약속을 수집/구조화하고 raw event를 Supabase에 도달시키는 것을 MVP
  성공 기준으로 둔다.
- Android route는 auth, onboarding, source connection, Today, People,
  commitments, privacy management, processing status, activity log, source
  detail을 포함한다.
- Android Room schema는 raw events, commitments, calendar events, people,
  identities, interactions, unmatched interactions, semantic memory index,
  source artifacts, email bodies, user profile, schedule-event links를 가진다.
- WorkManager facade는 periodic source sync, upload drain, backend mail
  sync, enrichment, person interaction index, profile memory generation,
  retention sweep, overdue sweep, cold sync, source upload worker를 다룬다.
- Backend `/v1`은 raw ingestion, commitments, source status,
  calendar/mail sync, Google/Outlook OAuth start/status/callback, persons,
  person memory, source evidence lists, schedule links, analytics events, PMF
  survey, extraction endpoints를 제공한다.
- Backend extraction은 meeting/call audio transcription에 CLOVA를 사용하고,
  structured action/schedule/decision extraction에 Vertex AI Gemini를
  사용한다. Email classification/extraction은 deterministic filter와 optional
  Anthropic classifier fallback을 포함한다.
- Backend relation intelligence는 `persons`, `person_identities`,
  `source_event_participants`, `commitment_participants`,
  `person_interactions`에 사람 중심 관계 row를 저장한다.
- Analytics와 crash observability는 business logic과 분리되어 있다. Android
  `ProductAnalyticsClient`, bounded composite analytics client, Amplitude SDK
  adapter, backend mirror, Crashlytics port, PII validation, backend
  `product_events`/PMF ingestion이 그 경계다.

## 현재 코드로 beta-test 가능한 범위

현재 코드는 controlled beta에서 아래 작업을 검증할 수 있다.

1. 로그인, 약관/동의 수락, optional mail/calendar source 연결.
2. Voice/meeting audio, screenshots, Gmail/Outlook Mail, IMAP, Google
   Calendar, Outlook Calendar, contacts, local manual/evidence surface에서 업무
   증거 수집 또는 import.
3. Quote/source evidence를 가진 action, schedule, decision item 추출.
4. Today, People, person detail, raw source event, commitments, source
   processing status 검토.
5. Commitment edit, complete, cancel, dispute, soft-delete, supersede.
6. Processing pause, consent withdrawal, local account data deletion, local
   privacy data export, local activity 확인.
7. Crashlytics, Amplitude, backend mirrored product events, PMF survey,
   source status, smoke metrics로 beta 동작 관측.

## 기술 갭과 QA 리스크

### 1. Extraction quality engine이 prompt-heavy다

Extraction engine은 schema와 prompt 제약을 갖고 있지만, 제품 품질은 여전히
모델 동작에 크게 의존한다. Korean B2B obligation, meeting multi-speaker
ownership, ambiguous schedule, email thread context에 대해 precision/recall을
증명하는 code-level scoring gate가 없다.

필요한 hardening:

- Korean B2B email, meetings, calls, screenshots, calendar evidence를 포함한
  대표 private fixture set 유지.
- False positive, false negative, wrong give/take direction, wrong person,
  wrong due date, noisy automation에 대한 extraction evaluation threshold 추가.
- Beta regression 추적을 위해 extraction output에 model/version/prompt hash
  저장.
- User correction feedback loop를 future extraction/person matching 결정에
  연결.

### 2. Relationship memory는 아직 완전한 identity-resolution product가 아니다

Backend는 strong email, phone, organization anchor가 있을 때만 canonical
person을 만든다. Name-only participant는 unresolved로 남는다. Android에는
semantic matching과 semantic index가 있지만, 이것은 아직 merge/split/review
engine이 아니라 heuristic layer에 가깝다.

사용자 manual QA에서 확인된 더 큰 문제는 self-anchoring이다. 제품 목표는
"나를 기준으로 누구에게 무엇을 줘야/받아야 하는가"인데, 현재 코드는
사용자 본인을 canonical person graph의 중심 node로 충분히 모델링하지 않는다.
Android `UserProfileEntity`는 `user_id`, optional `phone_e164_self`,
timezone/locale 중심의 작은 bootstrap row이고, OAuth로 연결된 Gmail/Calendar
account email, Supabase auth email, 사용자 이름/alias, 내 전화번호, meeting
self speaker 선택값이 하나의 verified self identity set으로 합쳐지는 구조가
보이지 않는다. `PersonMatchingEngine`은 participant와 candidate people을 맞추는
local heuristic이고, "이 identity는 사용자 본인이므로 counterparty/person memory로
만들면 안 된다"는 전역 self 검증자가 아니다. Backend도
`source_event_participants.relation_to_user`를 저장하지만,
`relation_to_user=self` 판정은 folder/role/model output 또는 meeting
`self_speaker_id`에 크게 기대며, provider account identity를 canonical self anchor로
사용해 relation/direction/person_ref를 재검증하는 단계가 약하다.

따라서 현재 QA에서 사용자가 느낀 "정작 사용자가 누구인지 몰라서 사용자 중심으로
정리되지 않는다"는 현상은 제품 핵심 gap이다. 이것은 People 화면의 merge 품질
문제만이 아니라 Today/Commitments의 give/take direction, person_ref, unresolved
participants, 관계 memory ranking까지 전파될 수 있다.

필요한 hardening:

- `SelfIdentitySet` 또는 동등한 self profile graph를 도입한다. 최소 anchor는
  Supabase auth user, 연결된 provider account email, Gmail/Calendar account email,
  `phone_e164_self`, 사용자가 확인한 display name/alias, meeting self speaker
  mapping이다.
- Backend relation builder와 extraction post-processing에서 self identity set으로
  `relation_to_user`, `person_ref`, `direction`을 재검증한다. Self로 판정된
  identity는 counterparty person으로 만들지 않고, commitment의 상대방 후보에서도
  제외한다.
- Android person matching 전에 participant가 self anchor와 충돌하는지 먼저
  판정한다. 충돌하면 People candidate matching으로 보내지 않고 self/unknown
  resolution으로 남긴다.
- Onboarding 또는 source 연결 완료 후 provider account email/display name을 사용자
  self profile에 연결하는 명시적 sync를 추가한다.
- Meeting speaker review는 `SPEAKER_XX` label만 self로 보내지 말고, 해당 선택을
  사용자 self anchor history로 보존해 이후 같은 source/session의 speaker/person
  resolution에 사용한다.
- 명시적인 person merge/split flow와 identity change audit trail 추가.
- Unresolved 또는 competing people에 대한 confidence band와 reviewer UI 추가.
- Backend canonical identity와 Android local semantic match가 충돌할 때의
  conflict model 정의.
- Same-name people, organization-only identities, aliases, Korean/English name
  variants, role/title ambiguity fixture 추가.

### 3. Source connection state는 end-to-end reliability proof가 필요하다

Gmail, Outlook Mail, Google Calendar, Outlook Calendar에 대해 OAuth
start/status/callback route와 backend endpoint는 존재한다. Android에도 shared
source connection screen이 있다. 남은 리스크는 endpoint 부재가 아니라 state
propagation이다. Provider callback success, Android return/resume, backend
status refresh, source status merge, worker scheduling이 모두 visible connected
state로 수렴해야 한다.

필요한 hardening:

- Provider별 OAuth return/resume, status refresh instrumentation test 추가.
- Callback persistence와 status endpoint가 같은 provider/account state를
  반환하는지 검증하는 backend test 추가.
- PII 없는 reason code로 source-state transition log 추가:
  `start_requested`, `provider_opened`, `callback_received`, `status_connected`,
  `sync_enqueued`, `sync_succeeded`, `sync_failed`.
- App process lifecycle timing에 의존하지 않고 status refresh와 sync를 강제하는
  repair action 추가.

### 4. Sync orchestration은 넓지만 operationally fragmented하다

WorkScheduler가 많은 worker를 중앙에서 다루는 점은 좋다. 하지만 제품은 local
source adapter, backend-managed mail/calendar sync, upload drain, person
indexing, profile memory, retention, overdue sweep을 동시에 가진다. 10명 beta는
어떤 worker는 성공했지만 dependent index나 status projection이 지연되는 race를
노출할 수 있다.

필요한 hardening:

- Android와 backend가 공유하는 source state machine 정의:
  `idle`, `connecting`, `connected`, `syncing`, `synced`, `needs_reauth`,
  `failed`, `paused`, `disconnected`.
- 모든 worker가 같은 repository/endpoint를 통해 state transition을 기록.
- `pending`, `failed`, `quarantined`, unresolved participants, stale source
  status row를 찾는 dashboard query를 beta 운영 runbook에 유지.
- Paid AI call을 명시적으로 재실행하지 않고 single user source graph를 replay할
  수 있는 tooling 추가.

### 5. Analytics는 구조적으로 분리되어 있지만 product funnel이 얇다

Analytics는 extraction/domain engine 내부에 섞여 있지 않다. Android는 bounded
channel, non-blocking Amplitude call, backend batch mirror, PII validation,
Crashlytics sanitization을 사용한다. Backend는 product event를 저장하기 전에
event name, source, client ID, property shape, depth, PII-looking field를 검증한다.

남은 갭:

- 현재 allowed event는 유용하지만 source connection과 extraction failure 진단에는
  부족하다. 빠진 funnel event 예시는 OAuth start, OAuth callback/status, source
  sync start/end/failure, extraction start/end, extraction filtered, commitment
  correction, person merge/split, consent withdrawal, processing pause다.
- Reviewed files 기준으로 bounded analytics queue behavior, backend mirror failure
  isolation, Amplitude opt-out, Crashlytics sanitization, backend analytics
  validation 전용 unit test가 보이지 않는다.
- Backend product event 저장, dashboard view, beta runbook은 정의되었다. 남은 부분은
  alert 자동화와 실제 beta report owner 지정이다.

### 6. Privacy control은 존재하지만 beta evidence가 필요하다

코드는 encrypted credential/token store, per-user Room database naming,
local-only email body ownership, full email original backend omission, telemetry
opt-out, Crashlytics value/key redaction, retention sweep scheduling 같은 중요한
privacy control을 가진다.

남은 갭:

- Unit/local integration path뿐 아니라 실제 upgraded-device data로 retention sweep
  증명.
- OAuth, extraction, source sync, Crashlytics breadcrumb, backend exception log에
  source title, quote, email body, token, search query가 들어가지 않는지 확인.
- Privacy copy와 Play policy declaration이 실제 source collection, telemetry
  behavior와 일치하는지 확인.
- User deletion/export/support request beta runbook 추가.

### 7. Release readiness는 live environment proof에 의존한다

Build file은 Firebase Crashlytics, Amplitude dependency, target SDK 35, release
minification, signing config gate, runtime config field, protected release check를
이미 연결한다. 하지만 prior CI/smoke evidence는 source/OAuth/telemetry 변경 이후
현재 tree를 fresh run한 증거를 대체하지 못한다.

필요한 hardening:

- 현재 commit에서 full Android unit tests, lint, release assemble, backend tests,
  emulator readiness smoke 재실행.
- 현재 cold start, memory, fatal/ANR/OOM, source OAuth smoke evidence 기록.
- Production/staging secrets 확인: API base URL, Supabase, Google web client ID,
  Amplitude key, Firebase config, release signing, backend provider OAuth
  credentials.
- Paid AI extraction과 telemetry에 대한 rollback 및 feature-flag/off switch 정의.

## Beta 기준별 fresh scoring

이 점수는 코드 구조와 reviewed evidence 기준이다. 이 pass에서 focused verification은
일부 실행했다. Backend private Gmail/Naver mixed live eval은 `500 / 500 pass`,
Android focused auth/onboarding unit test는 pass, Samsung 실기기 connected-state
smoke는 pass다. Fresh local reset은 Terms -> Login까지 pass했지만 Google Console
OAuth 설정 mismatch로 Google login 완료가 blocked다. 앱/Backend 모두 사용자 데이터가
없는 상태에서 시작하는 full fresh E2E는 아직 통과하지 않았다.

| 영역 | 점수 | 근거 |
|---|---:|---|
| 기능 요구사항 | 7.5 | Core flow와 endpoint는 넓게 구현되어 있지만 OAuth/source connection convergence, extraction correctness, self 기준 person matching이 fresh E2E proof가 필요하다. 사용자 QA에서 self anchor 부재가 실제 product gap으로 확인되었다. |
| 비기능 요구사항 | 7.8 | WorkManager, bounded analytics queue, retry/backoff, prior smoke number는 강점이다. 다만 telemetry/source 변경 후 current-tree performance 재측정이 필요하다. |
| 아키텍처 | 8.4 | UI/data/domain separation, repository, Room, Hilt, WorkManager, backend service split, analytics facade, observability port가 credible하다. |
| 설계 패턴 | 8.5 | MVVM/state-holder/repository/DI/reactive pattern이 일관되고 backend service도 beta 수준에서는 충분히 modular하다. |
| 테스트 | 7.45 | Unit/local/instrumentation/backend test가 많고, backend에는 self anchor/person matching/extraction severe fixture gate와 beta threshold runner가 추가되었다. Gmail/Naver mixed 500건 live eval은 통과했다. 다만 analytics/OAuth convergence와 no-user-data fresh E2E proof는 아직 필요하다. |
| 코드 품질 | 8.0 | 구조는 유지보수 가능하고 privacy-aware하다. 다만 worker orchestration과 source status propagation의 complexity가 높다. |
| 보안 / 개인정보 | 8.1 | Token/credential storage, transient email body policy, PII filtering, consent control이 있다. Beta에는 live log/policy verification이 필요하다. |
| 릴리스 엔지니어링 | 7.6 | SDK, signing gate, release config, CI docs, staging path가 있다. Fresh current-tree release proof와 rollback runbook이 필요하다. |
| 관측성 | 8.1 | Crashlytics, Amplitude, backend event mirror, PMF endpoint, source status가 있다. Source/extraction funnel과 dashboard view는 보강되었고, 남은 리스크는 alert 자동화와 실제 운영 owner 지정이다. |
| UX / 제품 준비도 | 7.7 | Main surface와 privacy/source management가 존재한다. Connected-state feedback, correction flow reliability, "나를 기준으로 정리됨"에 대한 신뢰성은 QA가 필요하다. |

Controlled 10명 / 5일 beta readiness weighted score: **7.78 / 10**.

판정: controlled 10명 / 5일 beta는 fresh current-tree verification이 통과하고
실제 기기에서 OAuth/source state convergence와 self-anchored person matching이
증명될 때만 **conditional go**다. Person matching이 현재 사용자 중심으로 고정되지
않는 상태라면 relationship memory beta promise는 **no-go**에 가깝다. Public/open
beta는 여전히 **no-go**다.

## 사용자와 진행할 QA 계획

추가 구현 전에 다음 질문에 답해야 한다.

1. 위 제품 목표가 정확한 beta promise인가, 아니면 beta 범위를 더 좁힐 것인가?
2. 첫 10명 사용자에게 필수인 source는 무엇인가: voice/meeting, Gmail,
   Outlook Mail, IMAP, Google Calendar, Outlook Calendar, screenshots?
3. Beta에서 허용 가능한 extraction error 기준은 무엇인가: false positives,
   missed obligations, wrong due date, wrong person, wrong give/take direction?
4. User correction을 beta 기간에는 local/person matching heuristic에만 사용할 것인가,
   아니면 backend evaluation data로도 mirror할 것인가?
5. Beta 사용자 본인(self)을 어떤 anchor로 확정할 것인가: auth email, provider
   account email, phone, display name/alias, meeting self speaker 중 무엇을
   verified로 볼 것인가?
6. Day one에 필수인 operational dashboard는 무엇인가: crashes, OAuth failures,
   sync failures, extraction failures, PMF answers, 또는 전체?

### QA 워크시트

이 워크시트는 review를 구현 결정으로 바꾸기 위한 것이다. 추천 기본값은 현재 코드가
이미 지원하는 범위와 가장 위험한 gap을 기준으로 한다.

| 결정 항목 | 추천 beta 기본값 | 이유 |
|---|---|---|
| Beta promise | "승인된 업무 소스에서 relationship memory + commitments를 만들고 사용자가 검토/수정할 수 있다"로 좁힌다. 완전 자동 정답은 약속하지 않는다. | Extraction과 identity resolution은 존재하지만 golden-eval quality gate와 correction loop가 아직 필요하다. |
| 첫 10명 필수 source | Gmail, Google Calendar, voice/meeting audio, manual/evidence import. Outlook/IMAP/screenshots는 target user가 요구하지 않으면 optional/recovery path로 둔다. | 5일 beta에서 provider별 OAuth/sync permutation을 줄이면서 main value loop를 검증할 수 있다. |
| Extraction acceptance | Wrong person, wrong give/take, invented due date, private source leakage 같은 severe error는 blocker-class finding으로 취급한다. | Low-value false positive는 beta에서 수정 가능하지만 identity/direction/privacy error는 신뢰를 훼손한다. |
| Self identity policy | 사용자의 auth/provider account email, `phone_e164_self`, 사용자가 확인한 display name/alias, meeting self speaker 선택을 verified self anchor로 저장하고 person matching 전에 먼저 적용한다. | 현재 QA에서 "사용자가 누구인지 몰라 사용자 중심으로 정리되지 않는" 문제가 확인되었고, 이는 relationship memory의 중심 설계 결함이다. |
| Correction handling | Structured correction을 backend eval/feedback data로 mirror하되, model/prompt 변경은 offline eval 통과 후 적용한다. | Android에는 correction surface가 있고, backend에는 품질 측정을 위한 feedback record가 필요하다. Live prompt drift는 피해야 한다. |
| Day-one dashboard | 최소 crash-free users, OAuth failures, source sync failures, extraction failures/filtered counts, unresolved participants, PMF responses. | 이 review에서 발견한 beta 최고위험 영역과 직접 연결된다. |
| Beta go/no-go gate | Fresh Android tests/lint/release build, backend tests, emulator smoke, required provider별 실제 기기 OAuth round trip 통과 후 go. | 기존 green run은 유용하지만 current-tree source/OAuth/telemetry convergence를 증명하지 않는다. |

### QA 수락 기준

사용자 QA 결정은 아래 답변이 기록된 뒤에만 점수에 반영한다.

- 범위: 10명 beta의 required source type 목록.
- 품질 기준: 사용자 1명 / 1일 기준 허용 가능한 severe extraction/identity error
  최대치.
- Self identity: 사용자의 본인 anchor로 인정할 source와 사용자가 직접 수정/확정할
  UI 경로.
- 운영: day-one beta dashboard의 owner와 위치.
- 지원: tester report, account deletion, data export, OAuth reconnect, bad
  extraction correction의 channel과 SLA.
- 릴리스: 정확한 staging build, production/staging backend, rollback build.

답이 하나라도 unknown이면 beta score는 conditional로 유지하고 objective도 open으로
둔다.

### QA 결정 기록

사용자 QA review 중 이 섹션을 채운다. 아래 row가 명시되기 전에는 beta score를
올리거나 broad implementation을 시작하지 않는다.

| 결정 항목 | 현재 추천값 | 사용자 결정 | 후속 산출물 |
|---|---|---|---|
| Beta promise | Approved work source 기반 relationship memory + commitments, user review/correction 포함. | 미정 | 이 문서의 product goal과 score 갱신. |
| 필수 source 범위 | Gmail, Google Calendar, voice/meeting audio, manual/evidence import. | 미정 | Source QA matrix와 hardening order 갱신. |
| 선택 source 범위 | Outlook Mail, Outlook Calendar, IMAP, screenshots. | 미정 | Optional/deferred/required 여부를 별도 QA gate와 함께 표시. |
| Severe extraction error budget | Wrong person, wrong self/counterparty, wrong give/take, invented due date, privacy leakage, noisy automation을 blocker-class finding으로 취급. | 1차 확정: backend deterministic severe fixture, live eval constraint, beta threshold runner로 반영. 실제 mixed Gmail fixture에서 beta email eval set을 파생하는 스크립트가 추가됨. | Private/live eval fixture를 채우고 beta triage owner/response SLA 추가. |
| Self identity / person matching policy | Auth/provider account email, `phone_e164_self`, 사용자가 확인한 display name/alias, meeting self speaker 선택을 verified self anchor로 묶고, self anchor와 충돌하는 participant는 counterparty person으로 만들지 않는다. | 사용자 QA에서 문제 확인됨: 현재 사용자 본인이 누구인지 충분히 몰라 관계/약속이 사용자 중심으로 정리되지 않는다. | SelfIdentitySet 설계, backend relation post-processing, Android pre-match self guard, golden fixture 추가. |
| Correction feedback policy | Structured correction feedback을 backend eval data로 mirror하고, model/prompt 변경은 offline eval 뒤 적용. | 미정 | Backend feedback contract 추가 또는 명시적 defer. |
| Day-one dashboard | Crashes, OAuth failures, source sync failures, extraction failures/filtered counts, unresolved participants, PMF. | 기본 dashboard/runbook 확정: Supabase product/source/person PMF view + Crashlytics/Play Console 외부 dashboard 병행. | `beta-operations-dashboard-runbook.md`, backend `202605160002_beta_dashboard_observability_contract.sql`. |
| 지원 경로 | Tester report channel, account deletion/export 처리, OAuth reconnect 처리, bad extraction correction path 정의. | 미정 | Beta operations runbook 추가. |
| 릴리스/롤백 | 정확한 staging build, backend environment, rollback build, owner 지정. | 미정 | Build identifier가 포함된 release checklist entry 추가. |

### Source QA 매트릭스

실제 기기 QA pass에서 이 matrix를 사용한다. Required source는 controlled beta 전에
통과해야 한다. Optional source는 통과하거나 beta promise에서 명시적으로 제외해야 한다.

| Source | Beta 상태 | QA 경로 | 반드시 확인할 것 |
|---|---|---|---|
| Gmail | 필수 권장 | Onboarding Gmail connect -> provider consent -> app return -> status refresh -> backend mail sync -> Today/People/Commitments update. | Connected state가 app restart 후에도 유지된다. `/v1/oauth/mail/gmail:status`가 connected를 반환한다. 성공 sync 후 source status가 idle/failed에 머물지 않는다. Email body가 backend-side에 저장되지 않는다. 연결된 Gmail account email이 self anchor로 등록되고, inbox/sent participant relation과 give/take가 그 anchor 기준으로 검증된다. |
| Google Calendar | 필수 권장 | Onboarding Google Calendar connect -> provider consent -> app return -> status refresh -> calendar sync -> Today schedule appears. | Connected state가 app restart 후에도 유지된다. `/v1/oauth/calendar/google_calendar:status`가 connected를 반환한다. Schedule/event links가 refresh된다. Source status가 sync result를 반영한다. Calendar account email/organizer/attendee 중 self가 정확히 식별되고 self attendee가 counterparty person으로 생성되지 않는다. |
| Voice / meeting audio | 필수 권장 | Grant recording folder -> detect/import recording or meeting file -> speaker preview if meeting -> extraction upload -> commitment/person rows appear. | Audio limit과 429 retry path가 동작한다. CLOVA/Vertex failure가 visible하고 non-fatal이다. Wrong speaker/person이 reviewable하다. 사용자가 선택한 self speaker가 이후 extraction/person_ref/direction의 기준이 되고, speaker label만으로 별도 counterparty person이 생성되지 않는다. Consent withdrawal 후 upload가 없어야 한다. |
| Manual / evidence import | 필수 권장 | Add/import manual evidence -> create or supersede commitment -> edit/cancel/complete -> sync. | 사용자가 extracted/entered item을 수정할 수 있다. Quote/source provenance가 계속 보인다. Pending sync가 drain되거나 retryable failure를 보고한다. |
| Outlook Mail | Target user가 요구할 때만 필수, 기본은 선택 | Gmail과 같은 절차를 Outlook provider로 수행한다. | OAuth status, backend sync, connected state가 Gmail과 독립적으로 수렴한다. |
| Outlook Calendar | Target user가 요구할 때만 필수, 기본은 선택 | Google Calendar와 같은 절차를 Outlook provider로 수행한다. | Calendar status, sync, Today schedule이 Google Calendar와 독립적으로 수렴한다. |
| IMAP Naver/Daum | 선택 / recovery path | Email PIPA consent -> provider credential setup -> worker sync -> extraction -> source status. | Credential이 encrypted된다. Connection failure message가 user-safe하다. Email body의 backend/local ownership이 명확하다. |
| Screenshots | 선택 / recovery path | Import screenshot evidence -> image extraction -> commitment/person rows appear. | Image size/type limit이 동작한다. OCR/model failure가 non-fatal이다. Bad extraction을 수정할 수 있다. |

Required source에서 user flow는 성공했지만 observability가 잡히지 않으면 beta는 여전히
conditional이어야 한다. 이 beta는 failure를 발견하기 위한 단계이므로 invisible failure는
허용할 수 없다.

### Fresh no-user-data E2E 계획

Fresh user proof는 별도 runbook으로 분리했다:
`docs/readiness/beta-fresh-no-user-data-test-plan.md`.

핵심 원칙:

- Android `pm clear com.becalm.android`는 local no-user-data만 보장한다.
- 완전한 no-user-data test는 backend도 새 tester auth user이거나 user-scoped rows가
  reset된 상태여야 한다.
- 500개 live eval 통과 이후 prompt를 더 늘리는 것보다, fresh E2E에서 OAuth, sync,
  extraction, self identity anchor, person/counterparty 방향이 실제 사용자 경로에서
  수렴하는지 보는 것이 다음 slice다.

실행 헬퍼:

```bash
qa/device/scripts/fresh_no_user_data_smoke.sh \
  --adb /mnt/c/Users/jakek/AppData/Local/Android/Sdk/platform-tools/adb.exe \
  --device R5CT83SMP4P \
  --confirm-clear-data
```

이 스크립트는 실수로 데이터를 지우지 않도록 `--confirm-clear-data` 없이는 실행을
거부한다.

## QA 이후 구현 백로그

QA 답변이 합의된 뒤에는 아래 코드 위치를 기준으로 implementation scope를 잡는다.
Business logic, telemetry, source sync, privacy 변경을 명시적 이유 없이 섞지 않기
위함이다.

| Gap | Android에서 건드릴 가능성이 높은 코드 | Backend에서 건드릴 가능성이 높은 코드 | 완료 증거 |
|---|---|---|---|
| Self identity / person matching anchor | `data/local/db/entity/UserProfileEntity.kt`, `data/repository/UserProfileRepository.kt`, `domain/person/PersonIdentityResolver.kt`, `domain/person/PersonMatchingEngine.kt`, `data/repository/PersonManualMatchRepository.kt`, `data/repository/PersonMemoryInputCollector.kt`, `worker/PersonInteractionIndexWorker.kt`, `ui/onboarding/EmailOAuthConnector.kt`, `ui/onboarding/CalendarOAuthConnector.kt`, `ui/evidence/MeetingSpeakerMappingsJson.kt`, `ui/persons/*` | `app/api/v1.py` user profile/source status endpoints, `app/services/mail_oauth.py`, `app/services/calendar_oauth.py`, `app/services/mail_sync.py`, `app/services/calendar_sync.py`, `app/services/participant_normalizer.py`, `app/services/relation_intelligence.py`, `app/services/commitment_extract.py`, self-identity migration/tests | Auth/provider account email, phone, alias, self speaker가 verified self anchor로 저장된다. Self anchor와 충돌하는 participant는 counterparty person으로 생성되지 않는다. `relation_to_user=self` row, `person_ref`, give/take direction이 self 기준 fixture에서 통과한다. |
| Source connection funnel analytics | `core/analytics/ProductAnalyticsClient.kt`, `core/analytics/ProductAnalyticsValidation.kt`, `ui/onboarding/EmailOAuthConnector.kt`, `ui/onboarding/CalendarOAuthConnector.kt`, `ui/onboarding/OnboardingViewModel.kt`, `ui/sources/SourceSyncPort.kt` | `app/services/analytics_contract.py`, `app/api/v1.py`, `supabase/migrations/202605140002_beta_observability.sql`, `tests/test_v1_api.py` | Unit test가 PII를 reject하고 새 event name을 accept하며 source connect/sync event가 UI/business logic을 block하지 않음을 증명한다. |
| Unified source state machine | `data/remote/dto/SourceStatusDto.kt`, `data/repository/SourceStatusRepository.kt`, `data/repository/internal/SourceStatusMerger.kt`, `ui/sources/*`, `worker/*` source workers | `app/services/source_persistence.py`, `app/services/mail_sync.py`, `app/services/calendar_sync.py`, `app/api/v1.py`, source-status migrations | Android/backend가 같은 state와 transition을 노출한다. 실제 기기 OAuth status가 restart 후 visible connected/synced state로 수렴한다. |
| OAuth return/resume reliability | `ui/onboarding/EmailOAuthConnector.kt`, `ui/onboarding/CalendarOAuthConnector.kt`, `ui/onboarding/OnboardingSourceConnection*`, `ui/sources/SourceAdministrationPort.kt`, `data/repository/SourceStatusRepository.kt` | `app/services/mail_oauth.py`, `app/services/calendar_oauth.py`, `tests/test_v1_api.py`, new focused OAuth tests | Required provider별 provider callback, status endpoint, Android refresh, worker enqueue가 검증된다. |
| Extraction golden evals | QA ID를 UI에 노출하지 않는 한 Android 변경은 필수 아님. Optional touch: `ui/commitments`, `ui/persons` correction screen | `app/services/commitment_extract.py`, `app/services/email_extract.py`, `app/services/llm_eval_expectations.py`, `tests/test_llm_contract_cases.py`, `tests/test_beta_severe_eval_cases.py`, `tests/test_beta_llm_eval_gate.py`, `tests/test_build_beta_email_eval_cases.py`, `tests/external/test_llm_live_eval.py`, `scripts/check_beta_llm_eval_gate.py`, `scripts/build_beta_email_eval_cases.py`, `scripts/eval_email_cases_live.py`, `tests/fixtures/beta_severe_extraction_person_matching_cases.json`, private fixture exporter/eval data | Deterministic gate가 wrong person, wrong self/counterparty, wrong give/take, invented due date, privacy leakage, noisy automation blocker class를 고정한다. Live eval은 forbidden person/candidate/due date constraint를 지원하고 beta runner가 zero-tolerance threshold를 적용한다. |
| Correction feedback loop | `ui/commitments/CommitmentEditViewModel.kt`, person matching/correction screens, `data/remote/api/RailwayApi.kt`, `data/remote/dto` DTO | New feedback endpoint/table, `app/api/v1.py`, relation/person services, migration, tests | User correction이 structured feedback으로 저장된다. Live prompt는 offline eval 전까지 변경하지 않는다. |
| Beta dashboard/runbook | Onboarding/source/extraction/commitment flow의 Android event call site | Supabase `product_events`, `pmf_survey_responses`, `source_connections`, `source_event_participants`, extraction failure path, dashboard SQL/runbook docs | Day-one dashboard가 crash-free users, OAuth failures, sync failures, extraction failures/filtered counts, unresolved participants, PMF responses를 보여준다. |

Implementation은 QA scope가 required provider를 확정한 뒤 analytics/source-state work부터
시작한다. 그렇지 않으면 optional provider에 과적합해 5일 beta 안정성을 낮출 수 있다.

## 즉시 hardening 순서

1. Fresh current-tree verification 실행: Android unit tests, lint, release build,
   backend tests, emulator smoke.
2. Gmail, Outlook Mail, Google Calendar, Outlook Calendar에 대해 실제 기기 OAuth
   QA 실행: connect, app return, status update, source sync start, restart 후
   visible connected state 유지.
3. Self identity set 설계와 QA fixture 추가: auth/provider email, phone, alias,
   meeting self speaker를 self anchor로 묶고 self/counterparty/direction
   regression을 만든다.
4. Source connection/extraction funnel analytics event와 test 추가.
5. Beta-specific acceptance threshold가 포함된 extraction/person matching golden
   eval 추가. 1차 완료: backend severe fixture, live eval constraint, zero-tolerance
   runner, Gmail/Naver mixed private live eval 500건 `500 / 500 pass`. 남은 작업:
   beta triage owner/SLA 지정과 fresh user self-anchoring fixture 연결.
6. Source state machine 문서화 및 Android/backend state name 정렬.
7. Beta operations runbook 준비: dashboard query, crash triage, source reconnect
   support, privacy deletion/export support, rollback.

## 요청 목표 대비 완료 감사

목표를 concrete deliverable로 분해하면 다음과 같다.

| 요구사항 | 확인한 증거 | 상태 |
|---|---|---|
| 현재 코드 기준으로 `becalm-android`를 이해한다. 저장소 밖 설명에 의존하지 않는다. | Android README goal, route graph, Room database, WorkScheduler, analytics/observability wiring, build config, readiness scorecard를 확인했다. 대표 파일: `README.md`, `android/app/src/main/java/com/becalm/android/ui/navigation/Routes.kt`, `android/app/src/main/java/com/becalm/android/data/local/db/BeCalmDatabase.kt`, `android/app/src/main/java/com/becalm/android/worker/WorkScheduler.kt`, `android/app/build.gradle.kts`, `android/app/src/main/java/com/becalm/android/core/analytics/*`, `android/app/src/main/java/com/becalm/android/core/observability/*`. 현재 checkout의 전체 경로는 `becalm-android/android/app/...`다. | 완료 |
| 현재 코드 기준으로 `becalm-backend`를 이해한다. 저장소 밖 설명에 의존하지 않는다. | Backend README, FastAPI auth middleware, `/v1` router, OAuth services, extraction services, relation intelligence, persistence, source sync, analytics contract를 확인했다. 대표 파일: `README.md`, `app/main.py`, `app/api/v1.py`, `app/services/commitment_extract.py`, `app/services/email_extract.py`, `app/services/relation_intelligence.py`, `app/services/source_persistence.py`, `app/services/mail_sync.py`, `app/services/calendar_sync.py`, `app/services/analytics_contract.py`. | 완료 |
| 현재 코드베이스가 실제로 지원할 수 있는 궁극적 목표를 작성한다. | 이 문서의 `코드 기반 제품 목표` 섹션이 Android route, Room entity, worker, backend API, extraction service, relation persistence, telemetry에 한정된 목표를 정의한다. | 완료 |
| 현재 코드로 beta-test 가능한 범위를 명시한다. | `현재 코드로 beta-test 가능한 범위` 섹션이 현재 UI, worker, backend endpoint, privacy control, telemetry로 지원되는 concrete job을 나열한다. | 완료 |
| 기술적으로 부실하거나 누락된 engine/design을 지적한다. | `기술 갭과 QA 리스크` 섹션이 prompt-heavy extraction quality, self-anchoring 없는 incomplete identity-resolution product, OAuth/source state convergence risk, fragmented sync orchestration, thin analytics funnel, privacy evidence gaps, release proof gaps를 지적한다. | 완료 |
| Beta 기준으로 scoring한다. | `Beta 기준별 fresh scoring` 섹션이 functional, non-functional, architecture, patterns, tests, code quality, security/privacy, release engineering, observability, UX/product readiness를 scoring한다. | 완료 |
| Analytics/observability가 business logic과 분리되어 있는지 assessment에 반영한다. | Assessment는 analytics가 `ProductAnalyticsClient`, bounded `CompositeProductAnalyticsClient`, `AmplitudeProductAnalyticsClient`, `BackendProductEventsMirrorClient`, `ProductAnalyticsValidation`, `CrashlyticsObservabilityClient`, backend `/v1/analytics/events:batch`를 통해 구현되어 있고 extraction/domain engine 내부에 있지 않다고 기록한다. | 완료 |
| 추가 구현 전 사용자 QA 질문을 포함한다. | `사용자와 진행할 QA 계획`, `QA 워크시트`, `QA 결정 기록`, `Source QA matrix`가 beta promise와 scope를 확정하기 위한 질문과 기록 표를 포함한다. | 완료 |
| 사용자 QA에서 발견한 person matching/self anchor gap을 문서에 반영한다. | 사용자가 manual QA에서 확인한 "사용자 본인을 몰라 사용자 중심 정리가 안 됨" 문제를 `QA 요약`, `Relationship memory`, scoring, QA decision record, source matrix, backlog, hardening order에 반영했다. | 완료 |
| 사용자와 QA를 실제로 수행하고 그 결과를 코드/문서에 반영한다. | Person matching/self anchor gap, day-one dashboard/runbook, 500건 live eval pass, Samsung 실기기 connected-state smoke, fresh no-user-data runbook을 반영했다. 다만 beta source scope, support, release/rollback 결정과 full fresh E2E는 아직 남아 있다. | 진행 중 |

Audit 시점의 repository 상태:

- `becalm-android`: branch `chore/beta-readiness-score-gates-clean`; 새 uncommitted
  file `docs/readiness/current-codebase-product-goal-and-gap-qa.md`.
- `becalm-backend`: branch `feature/backend-beta-observability`; 기존 dirty file
  `app/services/analytics_contract.py`는 이 review에서 수정하지 않았다.
- 이 pass에서 focused verification은 실행했다. Backend private Gmail/Naver mixed live
  eval은 `500 / 500 pass`, Android focused unit test는 pass, Samsung 실기기
  connected-state smoke는 pass다. Scoring은 full fresh no-user-data E2E를 아직 남은
  hardening item으로 취급한다.

감사 판정: 문서는 사용자 QA에서 확인된 person matching/self anchor gap까지 반영했다.
다만 전체 thread objective는 **아직 완료가 아니다**. 남은 beta scope, error budget,
support, release/rollback QA 결정이 기록되어야 완료된다.
