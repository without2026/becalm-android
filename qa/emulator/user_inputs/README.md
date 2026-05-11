# BeCalm QA User Inputs

이 폴더는 실제 QA에 사용할 계정 정보와 예시 데이터를 직접 넣는 곳입니다.

## 폴더 구조

- `accounts/`: 연결 계정, IMAP 테스트 계정, OAuth 테스트 메모 등
- `message_screenshots/`: 카카오톡, Slack, 문자 등 메신저 스크린샷 이미지
- `meeting_audio/`: 회의 녹음 파일
- `expected/`: 사람이 검수한 기대 결과, ground truth JSON

## 권장 파일명

```text
accounts/connected_accounts.local.json
message_screenshots/2026-05-09_kakao_launch_followup.png
meeting_audio/2026-05-09_partner_call.m4a
expected/2026-05-09_kakao_launch_followup.expected.json
```

## 주의

이 폴더에는 실제 개인정보나 계정 정보가 들어갈 수 있으므로 git에 커밋하지 않습니다.
필요하면 `qa/emulator/fixtures/`의 샘플 파일을 참고해서 같은 형식으로 넣어주세요.
