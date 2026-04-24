# External Integration — Gmail / Outlook Mail

목표:
- 앱 안에서 Gmail / Outlook Mail OAuth 연결이 실제로 성공하는지 확인
- 연결 후 foreground catch-up / worker 경로로 실제 메일이 Room에 들어오는지 확인
- Source 상태와 person/commitment 파이프라인이 기대대로 움직이는지 확인

전제:
- Android `local.properties` 에 staging 값이 들어 있어야 한다.
- `google.web.client.id` 가 올바르게 설정돼 있어야 한다.
- `msal.client.id` 가 올바르게 설정돼 있어야 한다.
- 실기기 또는 에뮬레이터가 필요하다.
- 로그인 가능한 BeCalm 테스트 계정이 필요하다.

관련 owner:
- Gmail connect
  - `ui/onboarding/GmailOAuthScreen.kt`
  - `ui/onboarding/OnboardingEmailActionHandler.kt`
  - `data/remote/gmail/GoogleAuthTokenProviderImpl.kt`
- Outlook Mail connect
  - `ui/onboarding/OutlookMailOAuthScreen.kt`
  - `ui/onboarding/OnboardingEmailActionHandler.kt`
  - `data/remote/msgraph/MsGraphTokenProviderImpl.kt`
- Fetch workers
  - `worker/ingestion/GmailWorker.kt`
  - `worker/ingestion/OutlookMailWorker.kt`

## 1. Gmail smoke

1. 앱 실행
2. 테스트 계정으로 BeCalm 로그인
3. 온보딩에서 Gmail 단계 진입
4. `[연결]` 탭
5. Google consent / account picker 완료
6. 성공 시 다음 단계로 자동 이동 확인

Pass 조건:
- `gmail_connected=true`
- Sources 화면에서 Gmail 이 `connected`
- `ForegroundCatchUpScheduler` 또는 Gmail worker 실행 후 Gmail 상태가 `syncing -> synced`
- `raw_ingestion_events` 에 `source_type='gmail'` row 생성
- subject/body 기반 snippet 이 person 흐름으로 보임

실패 관찰 포인트:
- `user_cancelled`
- `scope_denied`
- `network`
- `play_services_unavailable`
- `unknown`

확인 포인트:
- Settings → Sources → Gmail
- Today source strip 에서 Gmail 상태
- Persons / Person Detail 에 Gmail-origin item 노출

## 2. Outlook Mail smoke

1. Outlook Mail 단계 진입
2. `[연결]` 탭
3. MSAL browser flow 완료
4. 성공 시 다음 단계로 자동 이동 확인

Pass 조건:
- `outlook_mail_connected=true`
- Sources 화면에서 Outlook Mail 이 `connected`
- `OutlookMailWorker` 실행 후 상태가 `syncing -> synced`
- `raw_ingestion_events` 에 `source_type='outlook_mail'` row 생성
- person 화면에서 Outlook mail-origin item 노출

실패 관찰 포인트:
- `user_cancelled`
- `scope_denied`
- `network`
- `unknown`

## 3. Worker 실행 확인

앱 쪽 PRIMARY 경로:
- foreground 진입 시 `ForegroundCatchUpScheduler`
- 또는 Source Detail 의 `[지금 동기화]`

기대:
- Gmail 은 `GmailWorker`
- Outlook Mail 은 `OutlookMailWorker`
- 둘 다 성공 시 `SourceStatusRepository.recordSyncSuccess(...)`

## 4. 최소 성공 기준

Gmail / Outlook Mail 각각에 대해 아래 4개가 맞으면 external smoke pass:

1. OAuth connect 성공
2. Source 상태 `connected`
3. 메일 fetch 후 `synced`
4. `raw_ingestion_events` 와 person-facing projection 에 실제 반영

## 5. IMAP 는 별도 문서

이 문서는 Gmail / Outlook Mail 만 다룬다.

Naver / Daum IMAP external integration 은 아래 문서로 분리했다.
- [external-integration-imap.md](/home/jakek/without/becalm-android/docs/external-integration-imap.md)
