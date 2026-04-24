# External Integration — Naver / Daum IMAP

목표:
- 앱 안에서 Naver / Daum IMAP credential save 가 실제로 성공하는지 확인
- 저장된 app password 로 worker 가 실제 IMAPS fetch 를 수행하는지 확인
- fetch 후 `raw_ingestion_events`, `source_status`, person-facing projection 이 기대대로 갱신되는지 확인

전제:
- 실기기 또는 에뮬레이터가 필요하다.
- BeCalm 테스트 계정으로 앱 로그인 가능해야 한다.
- Naver / Daum 메일 계정이 준비돼 있어야 한다.
- 각 계정에서 IMAP 사용이 활성화돼 있어야 한다.
- 각 계정에서 일반 계정 비밀번호가 아니라 IMAP용 app password 가 준비돼 있어야 한다.

관련 owner:
- credential save
  - `ui/onboarding/ImapSetupScreen.kt`
  - `ui/onboarding/OnboardingViewModel.kt`
  - `ui/onboarding/OnboardingEmailActionHandler.kt`
  - `data/local/secure/ImapCredentialStore.kt`
- fetch workers
  - `worker/ingestion/ImapNaverWorker.kt`
  - `worker/ingestion/ImapDaumWorker.kt`
  - `data/remote/imap/ImapClient.kt`
- app-level trigger
  - `worker/ForegroundCatchUpScheduler.kt`
  - `worker/WorkSchedulerImpl.kt`
  - `ui/sources/SourceDetailScreen.kt`

## 1. Direct provider smoke

앱 내부 flow 전에 provider credential 자체가 맞는지 먼저 확인한다.

Naver:
- host: `imap.naver.com`
- port: `993`
- TLS: implicit TLS

Daum:
- host: `imap.daum.net`
- port: `993`
- TLS: implicit TLS

Python 표준 라이브러리로 바로 handshake 확인:

```bash
export IMAP_HOST='imap.naver.com'
export IMAP_PORT='993'
export IMAP_USER='your-account@example.com'
export IMAP_PASSWORD='your-app-password'

python3 - <<'PY'
import imaplib
import os

host = os.environ["IMAP_HOST"]
port = int(os.environ["IMAP_PORT"])
user = os.environ["IMAP_USER"]
password = os.environ["IMAP_PASSWORD"]

client = imaplib.IMAP4_SSL(host, port)
try:
    status, _ = client.login(user, password)
    print("LOGIN", status)
    status, mailboxes = client.list()
    print("LIST", status, len(mailboxes or []))
finally:
    try:
        client.logout()
    except Exception:
        pass
PY
```

Pass 조건:
- `LOGIN OK`
- `LIST OK`
- mailbox 목록이 1개 이상 나온다

이 단계가 실패하면 앱 문제가 아니라:
- IMAP 미활성화
- app password 불일치
- provider 보안 설정
중 하나다.

## 2. Naver IMAP in-app smoke

1. 앱 실행
2. BeCalm 테스트 계정 로그인
3. 온보딩에서 IMAP 단계 진입
4. provider 를 `Naver` 로 선택
5. email / app password 입력
6. `[연결]` 탭
7. 성공 시 다음 단계로 자동 이동 확인

Pass 조건:
- `naver_imap_connected=true`
- Sources 화면에서 Naver Email 이 `connected`
- foreground catch-up 또는 `[지금 동기화]` 후 `syncing -> synced`
- `raw_ingestion_events` 에 `source_type='naver_imap'` row 생성
- Person / Person Detail 에 Naver-origin item 이 보임

실패 관찰 포인트:
- `unknown_provider`
- `network`
- `save_failed`
- source status 의 `last_error`

## 3. Daum IMAP in-app smoke

1. 온보딩 또는 Sources 재연결에서 IMAP 단계 진입
2. provider 를 `Daum` 으로 선택
3. email / app password 입력
4. `[연결]` 탭
5. 성공 시 다음 단계 또는 source detail 복귀 확인

Pass 조건:
- `daum_imap_connected=true`
- Sources 화면에서 Daum Email 이 `connected`
- foreground catch-up 또는 `[지금 동기화]` 후 `syncing -> synced`
- `raw_ingestion_events` 에 `source_type='daum_imap'` row 생성
- Person / Person Detail 에 Daum-origin item 이 보임

## 4. Worker 실행 확인

앱 쪽 PRIMARY 경로:
- foreground 진입 시 `ForegroundCatchUpScheduler`
- 또는 Source Detail 의 `[지금 동기화]`

기대:
- Naver 는 `ImapNaverWorker`
- Daum 은 `ImapDaumWorker`
- 둘 다 성공 시 `SourceStatusRepository.recordSyncSuccess(...)`

추가 확인 포인트:
- INBOX + SENT 두 패스가 모두 허용되는지
- denylist 폴더가 무시되는지
- 최근 메일이 `raw_ingestion_events` 와 `email_body` 에 같이 들어가는지

## 5. 최소 성공 기준

Naver / Daum 각각에 대해 아래 4개가 맞으면 external smoke pass:

1. direct IMAP handshake 성공
2. app credential save 성공
3. worker fetch 후 `synced`
4. `raw_ingestion_events` 와 person-facing projection 반영

## 6. 디버깅 순서

1. direct provider smoke
2. 앱에서 credential save
3. Sources 화면에서 connected 확인
4. manual sync 또는 foreground catch-up
5. source status / person 화면 확인

즉:
- 1에서 실패하면 provider credential 문제
- 1은 되는데 2에서 실패하면 onboarding save/wiring 문제
- 2는 되는데 4에서 실패하면 worker/fetch 문제
