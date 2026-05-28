# BeCalm Android Agent Rules

이 문서는 `becalm-android/` 작업에만 적용되는 규칙이다. 이 repo는 별도 Git root라서 하위 repo에서 바로 Codex를 시작하면 parent [AGENTS.md](../AGENTS.md)가 자동 로드되지 않을 수 있다. Android audit, QA, UI, refactor, 기능 개선을 하기 전에는 parent `AGENTS.md`와 [BECALM_ANDROID_AGENT_AUDIT_GOAL.md](../BECALM_ANDROID_AGENT_AUDIT_GOAL.md)를 함께 읽는다.

## Product Context

- `BECALM_ANDROID_AGENT_AUDIT_GOAL.md`는 `becalm-android`의 단일 제품 목표와 큰 로직 지도를 정의한다.
- 기능 제안, QA 지적, refactor, scoring은 이 목표에 도움이 되는지 기준으로 판단한다.
- 이 목표와 충돌하는 개선은 먼저 사용자에게 확인한다.

## Android 검증 Gate

- 가장 가까운 targeted test를 먼저 돌리고, 변경 범위가 넓거나 사용자-facing이면 더 넓은 gate로 확장한다.
- 기본 gate:
  - `./gradlew testDebugUnitTest`
  - `./gradlew lintDebug`
  - `./gradlew assembleDebug`
- onboarding, settings, source, commitment, today, navigation, input/keyboard, OAuth return, loading/success/error/retry UI 변경은 가능한 경우 instrumentation test와 실기기 smoke를 같이 한다.
- 테스트 실패를 무시하지 않는다. 변경과 무관한 기존 격리 문제라면 실패 원인과 범위를 보고한다.

## Samsung 실기기 규칙

실기기 검증은 WSL에서 Windows adb를 사용한다. device serial은 바뀔 수 있으므로 설치나 smoke 전에 `$ADB devices`로 현재 연결 상태를 먼저 확인한다.

```bash
ADB=/mnt/c/Users/jakek/AppData/Local/Android/Sdk/platform-tools/adb.exe
$ADB devices
```

현재 주 Samsung 기기:

```text
R5CT83SMP4P
```

앱 설치:

```bash
$ADB -s R5CT83SMP4P install -r app/build/outputs/apk/debug/app-debug.apk
```

앱 실행:

```bash
$ADB -s R5CT83SMP4P shell monkey -p com.becalm.android -c android.intent.category.LAUNCHER 1
```

crash/ANR smoke:

```bash
$ADB -s R5CT83SMP4P logcat -c
$ADB -s R5CT83SMP4P shell monkey -p com.becalm.android -c android.intent.category.LAUNCHER 1
sleep 5
$ADB -s R5CT83SMP4P logcat -d -v time | rg -i "FATAL EXCEPTION|AndroidRuntime|ANR|becalm"
```

실기기 테스트 원칙:

- Android Studio에 기기가 보이면 WSL에서도 Windows adb 경로로 접근할 수 있는지 먼저 확인한다.
- 기기가 잠겨 있거나 권한 dialog가 떠 있으면 사용자에게 기기 조작을 요청하고, 허용 후 이어서 진행한다.
- UI/UX 검증은 단순 실행이 아니라 실제 사용자처럼 눌러본다.
- onboarding, settings source connection, OAuth browser return, search bar keyboard input, loading/success/error/retry 상태는 실기기로 확인한다.
- logcat에는 개인정보/토큰이 찍히지 않게 주의하고, 보고할 때도 원문 이메일 주소는 노출하지 않는다.

## APK 산출물 규칙

QA용 debug APK 생성:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
mkdir -p /home/jakek/without/becalm-android/apk-outputs
cp app/build/outputs/apk/debug/app-debug.apk /home/jakek/without/becalm-android/apk-outputs/becalm-debug-YYYYMMDD-qa.apk
sha256sum /home/jakek/without/becalm-android/apk-outputs/becalm-debug-YYYYMMDD-qa.apk
```

Windows 전달용 복사:

```bash
cp /home/jakek/without/becalm-android/apk-outputs/becalm-debug-YYYYMMDD-qa.apk /mnt/c/Users/jakek/Downloads/
```

debug APK는 직접 설치 QA용이다. 5~10명 이상 베타 운영은 Firebase App Distribution 또는 Play Console Internal Testing을 우선 고려한다. release APK/AAB가 필요하면 signing config, versionCode/versionName, release notes, rollback 계획까지 확인한다.

## UI/UX 검증 관점

실제 사용자는 내부 구조를 모른다. UI는 아래를 항상 만족해야 한다.

- 사용자가 방금 누른 행동이 진행 중인지 알 수 있어야 한다.
- 완료/실패가 화면에 반영되어야 한다.
- 실패했을 때 다시 시도 가능한지 보여야 한다.
- settings에서 특정 항목을 연결하면 그 항목만 처리하고 이전 화면으로 돌아가야 한다.
- onboarding은 의도된 순서대로 다음 설정 항목으로 진행해도 된다.
- 검색창, 입력창, 키보드, back navigation, 권한 거부, OAuth return은 실기기에서 직접 확인한다.
- 개발자 문구를 사용자 문구로 바꾼다. 예: source는 출처, matching은 연결, local data는 기기 내부 데이터.
- Android 문구 품질 gate가 있으면 통과할 때까지 사용자-facing wording을 정리한다.

## Android 코드 스타일

- 기존 architecture와 패턴을 따른다.
- UI에 business logic을 넣지 않는다. ViewModel은 상태/이벤트 조율, domain/repository/usecase는 실제 판단 로직을 담당한다.
- error model과 loading state는 일관되게 유지한다.
- coroutine scope, Flow/StateFlow/SharedFlow 사용은 기존 패턴에 맞춘다.
- hardcoded string은 resource로 옮긴다.
- accessibility label, contentDescription, font scaling, touch target을 고려한다.
