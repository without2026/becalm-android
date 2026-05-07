# Live Emulator UI Check, 2026-05-07

Device: `emulator-5554` (`sdk_gphone16k_x86_64`)

Build: `:app:assembleDebug`

Locale: `ko-KR`

Validated:

- People tab matching banner uses Korean tab names: `사람`, `오늘`, `약속`.
- Source list hides raw repository error details and shows `연결 실패`.
- Naver source detail hides raw repository error details and shows `마지막 오류: 연결 실패. 설정을 확인해 주세요.`
- Person detail timeline opens from a contact row and does not show source artifact filenames as primary event titles.
- No BeCalm fatal crash appeared in logcat during the smoke pass.

Residual note:

- The emulator network was DNS-failing during the pass, so background upload logs still showed `UnknownHostException`. The UI now maps that into generic recovery copy.
