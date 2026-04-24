# JVM Test Layout

- `java/com/becalm/android/unit/...`
  - pure function, validator, mapper, ViewModel orchestration
  - Android framework 없이 빠르게 도는 spec-first unit tests
- `java/com/becalm/android/integration/...`
  - fake repository, in-memory Room, worker orchestration 등 hermetic JVM integration tests
  - 실제 외부 네트워크/기기 API는 붙이지 않음

`src/androidTest/java/...` 는 Compose, Navigation, WorkManager+Android framework, permission flow 같은
device/emulator 검증 전용으로 사용한다.
