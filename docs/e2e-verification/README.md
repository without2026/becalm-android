# E2E Verification — `becalm-android`

이 폴더는 `becalm-android/.spec/*.spec.yml` 의 behavior 각각이 **실제 소스 파일의 어떤 함수/클래스를 타고 E2E 로 흘러가는지** 를 CTO 가 grep 한 번으로 검증할 수 있도록 정리한 문서 세트다.

각 문서는 다음 구조를 따른다:
1. **전체 흐름**: UI → VM → Repo → Remote/DAO/Worker ASCII 다이어그램 (파일:심볼 매핑 포함)
2. **Behavior 별 trace**: 각 behavior ID 마다 단계별 `파일 경로 → 심볼명` 표
3. **Verify**: grep 명령 또는 수동 검증 체크리스트
4. **Invariants**: spec 의 invariants 를 검증 가능한 grep/테스트로 변환
5. **Tests 상태**: 현재 존재하는 테스트 파일과 **gap** (spec `tests: []` 비어있음) 표시

---

## 모듈 인덱스

| # | 모듈 | Spec 파일 | Verification 문서 | Behavior 수 |
| --- | --- | --- | --- | --- |
| 01 | auth | `.spec/auth.spec.yml` | [01-auth.md](./01-auth.md) | 7 (AUTH-001~007) |
| 02 | onboarding | `.spec/onboarding.spec.yml` | [02-onboarding.md](./02-onboarding.md) | 10 (ONB-001~008 + ONB-PIPA + ONB-CONTACTS) |
| 03 | today-timeline | `.spec/today-timeline.spec.yml` | [03-today-timeline.md](./03-today-timeline.md) | 10 (TDY-001~010) |
| 04 | voice-pipeline | `.spec/voice-pipeline.spec.yml` | [04-voice-pipeline.md](./04-voice-pipeline.md) | 7 (VOI-001~007) |
| 05 | data-ingestion | `.spec/data-ingestion.spec.yml` | [05-data-ingestion.md](./05-data-ingestion.md) | 15 (ING-001~015) |
| 06 | backend-sync | `.spec/backend-sync.spec.yml` | [06-backend-sync.md](./06-backend-sync.md) | 6 (SYNC-001~006) |
| 07 | commitment-management | `.spec/commitment-management.spec.yml` | [07-commitment-management.md](./07-commitment-management.md) | 10 (CMT-001~010) |
| 08 | person-enrichment | `.spec/person-enrichment.spec.yml` | [08-person-enrichment.md](./08-person-enrichment.md) | 8 (ENR-001~008) |
| 09 | source-management | `.spec/source-management.spec.yml` | [09-source-management.md](./09-source-management.md) | 5 (SMG-001~005) |
| 10 | source-viewer | `.spec/source-viewer.spec.yml` | [10-source-viewer.md](./10-source-viewer.md) | 8 (SRC-001~008) |

Contracts (shape of truth):
- `.spec/contracts/api-contract.yml` — Retrofit 엔드포인트 shape (Supabase Auth + Railway)
- `.spec/contracts/data-model.yml` — Room/서버 엔티티
- `.spec/contracts/ui-map.yml` — Compose route/screen 매핑

---

## 사용법 (CTO workflow)

1. **spec → code** 를 검증하고 싶을 때: 해당 모듈 문서를 열고 behavior ID 를 찾아 단계별 파일/심볼을 grep.
2. **invariant 자동 후크 만들고 싶을 때**: 각 문서의 "Invariants" 섹션의 grep 명령을 `.github/scripts/` 에 추가해 CI gate 로 올린다 (예: "persons_enrichment → 네트워크 leak grep" 같은 패턴).
3. **테스트 gap 보완 지시할 때**: 각 문서 끝의 "Tests" 섹션에 `(없음)` 으로 표기된 항목을 test-automator agent 에 넘긴다.

---

## 전체 검증 스윕 (한 번에 돌리고 싶을 때)

```bash
cd becalm-android/android
./gradlew testDebugUnitTest
# 모든 ViewModel / Worker / Repository 단위 테스트 실행
```

Contract-drift 검사 (서버 shape 이 바뀌었을 때):
```bash
# api-contract.yml 과 실제 RailwayApi.kt Retrofit 선언 비교
grep -nE '@(GET|POST|PATCH|DELETE|PUT)' becalm-android/android/app/src/main/java/com/becalm/android/data/remote/api/RailwayApi.kt
# .spec/contracts/api-contract.yml 의 endpoints 와 대조
```

---

## 발견된 주요 gap / 후속 액션 (CTO 결정 필요)

다음 항목은 이 E2E verification 스윕 중에 **스펙과 구현 사이 불일치가 의심되는** 지점이다. 작성 시점 기준이며, CTO 판단 후 티켓화 권장.

| # | 항목 | 파일/근거 | 설명 |
| --- | --- | --- | --- |
| G1 | SMS / CallLog observer 의 spec 부재 | `worker/ContentObserverBootstrap.kt:98/128` `registerSmsObserver` / `registerCallLogObserver` | 현재 9개 spec 어디에도 매핑 없음. PIPA invariant ("SMS/통화 기록 접근 없음") 와 충돌 가능 |
| G2 | Sentry 연동 존재 여부 불확실 | ONB-007 / VOI-006 의 `onboarding_step_failed` / `voice_upload_quarantined` 이벤트 | `grep -rn "Sentry" becalm-android/android/app/src/main/java` 결과 확인 필요. 없으면 spec 과 구현 gap |
| G3 | spec `tests: []` 전부 비어 있음 | 모든 9개 spec 파일 | 각 behavior ID 를 실제 테스트 함수에 annotation (e.g., `// spec: AUTH-001`) 으로 연결하고 spec 파일의 tests 배열을 채울 것 |
| G4 | `SourceDetailViewModel.kt:110/141` "API gap: filter by sourceType in-memory" | 서버측 filter endpoint 부재 | perf 리스크 티켓 등록 |
| G5 | `transcript` 필드가 `RawIngestionEventEntity` 에 존재하는지 불확실 | SRC-004 spec 은 voice 상세에서 transcript 표시 | 엔티티 확인 필요. voice-pipeline invariant ("transcript 영속 금지") 와 충돌하면 spec 수정 |

---

## 작성 규칙 (문서 유지보수)

- 새 behavior 가 spec 에 추가되면 동일 포맷으로 표 1행만 추가하면 된다.
- 심볼 line number 는 refactor 시 drift 가능 — file path + 함수명까지가 stable contract.
- invariant grep 명령은 구현을 모르고도 안전하게 돌 수 있어야 한다 (false positive 최소화).
