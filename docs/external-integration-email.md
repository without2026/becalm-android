# External Integration — Gmail / Outlook Mail

목표:
- 앱 안에서 Gmail / Outlook Mail 연결이 실제로 성공하는지 확인
- 연결 후 Railway backend가 메일 source를 동기화하는지 확인
- Source 상태와 person-facing projection이 기대대로 반영되는지 확인

전제:
- Android `local.properties` 에 staging 값이 들어 있어야 한다.
- `BECALM_API_BASE_URL` 이 Railway dev/staging backend를 가리켜야 한다.
- 실기기 또는 에뮬레이터가 필요하다.
- 로그인 가능한 BeCalm 테스트 계정이 필요하다.
- backend 쪽 mail OAuth env / callback URI 등록이 끝나 있어야 한다.

현재 owner:
- Android connect UI
  - `ui/onboarding/GmailOAuthScreen.kt`
  - `ui/onboarding/OutlookMailOAuthScreen.kt`
  - `ui/onboarding/EmailOAuthConnector.kt`
- Railway backend OAuth / sync
  - `becalm-backend/app/api/v1.py`
  - `becalm-backend/app/services/mail_oauth.py`
  - `becalm-backend/app/services/mail_sync.py`

중요:
- Gmail / Outlook Mail은 더 이상 Android on-device worker가 primary owner가 아니다.
- 앱은 backend `start -> browser -> callback -> status -> sync` 흐름만 담당한다.
- `raw_ingestion_events`, `source_status` 반영은 Railway backend owner다.

## 1. Gmail smoke

1. 앱 실행
2. 테스트 계정으로 BeCalm 로그인
3. 온보딩에서 Gmail 단계 진입
4. `[연결]` 탭
5. 브라우저에서 Google consent / account picker 완료
6. 성공 시 다음 단계로 자동 이동 확인

Pass 조건:
- Gmail 단계가 성공 이벤트로 끝난다
- backend `GET /v1/oauth/mail/gmail:status` 가 `connected=true`
- backend `POST /v1/mail_sources:sync?provider=gmail` 성공
- `source_status` 에서 `gmail = synced`
- `raw_ingestion_events` 에 `source_type='gmail'` row 생성

실패 관찰 포인트:
- `oauth_start_failed`
- `browser_unavailable`
- `oauth_timeout`
- provider callback mismatch

## 2. Outlook Mail smoke

1. Outlook Mail 단계 진입
2. `[연결]` 탭
3. 브라우저에서 Microsoft consent 완료
4. 성공 시 다음 단계로 자동 이동 확인

Pass 조건:
- Outlook Mail 단계가 성공 이벤트로 끝난다
- backend `GET /v1/oauth/mail/outlook_mail:status` 가 `connected=true`
- backend `POST /v1/mail_sources:sync?provider=outlook_mail` 성공
- `source_status` 에서 `outlook_mail = synced`
- 메일이 존재하면 `raw_ingestion_events` 에 `source_type='outlook_mail'` row 생성

실패 관찰 포인트:
- `redirect_uri` mismatch
- `oauth_start_failed`
- `browser_unavailable`
- `oauth_timeout`

## 3. 확인 포인트

앱 쪽:
- 온보딩 단계 성공/실패
- Sources 화면에서 연결 상태
- Today source strip / person 화면 반영

backend 쪽:
- `/v1/oauth/mail/{provider}:status`
- `POST /v1/mail_sources:sync`
- `/v1/source_status`
- `raw_ingestion_events`

## 4. 최소 성공 기준

Gmail / Outlook Mail 각각에 대해 아래 4개가 맞으면 external smoke pass:

1. OAuth connect 성공
2. backend status `connected`
3. backend sync 후 `source_status = synced`
4. 메일 존재 시 `raw_ingestion_events` 반영

## 5. IMAP 는 별도 문서

이 문서는 Gmail / Outlook Mail만 다룬다.

Naver / Daum IMAP external integration 은 아래 문서로 분리했다.
- [external-integration-imap.md](/home/jakek/without/becalm-android/docs/external-integration-imap.md)
