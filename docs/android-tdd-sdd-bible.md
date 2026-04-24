# Android TDD & SDD Bible

작성일: 2026-04-22  
적용 범위: `becalm-android/` 전체  
상태: `binding`

## 1. 목적

이 문서는 `becalm-android`에서 Android Test Driven Development(TDD)와
Spec Driven Development(SDD)를 수행할 때 절대 어기지 않는 기준 문서다.

- 구현보다 spec이 먼저다.
- spec보다 test가 먼저다.
- green이 되기 전 refactor는 금지다.
- spec, test, code 셋 중 하나라도 드리프트가 나면 작업은 완료가 아니다.

이 문서에서 `MUST`는 예외 없는 강제 규칙이다. 위반은 스타일 이슈가 아니라
프로세스 실패로 간주한다.

## 2. 해석 원칙

### 2.1 우선순위

규칙이 충돌하면 아래 순서로 해석한다.

1. 이 저장소의 명시 계약: `.spec/`, repo 문서, CTO/plan 문서
2. Android 공식 문서
3. TDD/Specification by Example의 1차 개념 문서
4. 도구 문법 문서(Gherkin/Cucumber)

### 2.2 Confidence Score

- `1.00`: 사실상 반박 여지 없는 저장소/공식 표준
- `0.90-0.99`: 강하게 신뢰 가능한 1차 출처
- `0.80-0.89`: 실무 표준으로 유효하지만 Android 단일 정전은 아님
- `0.79 이하`: 보조적 해석. 단, 이 저장소 정책이 승격하면 `MUST`가 된다

주의: Android TDD는 공식 Android 문서와 잘 맞물리지만, Android SDD는 단일
공식 정전이 없다. 따라서 SDD 규칙은 `Specification by Example + Gherkin +
repo contract`를 결합해 본 저장소의 강제 정책으로 정의한다.

## 3. Reference Register

| Ref | Source | Scope | Confidence |
|---|---|---|---:|
| `R1` | [becalm-android/README.md](../README.md) | `.spec/`가 source of truth임을 repo 차원에서 명시 | 1.00 |
| `R2` | [docs/round6-plan.md](./round6-plan.md) | `spec-first`, `test-preserving`을 repo 원칙으로 명시 | 0.99 |
| `A1` | [Fundamentals of testing Android apps](https://developer.android.com/training/testing/fundamentals) | Android 테스트 유형과 기본 원칙 | 0.97 |
| `A2` | [What to test in Android](https://developer.android.com/training/testing/fundamentals/what-to-test) | 무엇을 테스트해야 하는지와 피해야 할 저가치 테스트 | 0.99 |
| `A3` | [Use test doubles in Android](https://developer.android.com/training/testing/fundamentals/test-doubles) | fake/mock/hermetic test/DI | 0.99 |
| `A4` | [Recommendations for Android architecture](https://developer.android.com/topic/architecture/recommendations) | Android app architecture와 테스트 최소 기준 | 0.98 |
| `A5` | [Automate UI tests](https://developer.android.com/training/testing/ui-tests) | UI 테스트의 역할과 회귀 방지 가치 | 0.96 |
| `A6` | [Screenshot testing](https://developer.android.com/training/testing/ui-tests/screenshot) | Compose 시각 회귀 검증 | 0.95 |
| `A7` | [Test navigation](https://developer.android.com/guide/navigation/navigation-testing) | navigation 로직에서 무엇을 테스트해야 하는지 | 0.95 |
| `A8` | [Set up continuous integration](https://developer.android.com/studio/projects/continuous-integration) | 체크인마다 build/test 자동화 | 0.95 |
| `A9` | [AndroidJUnitRunner / Android Test Orchestrator](https://developer.android.com/training/testing/junit-runner.html) | 테스트 격리, shared state 최소화 | 0.93 |
| `A10` | [Big test stability](https://developer.android.com/training/testing/instrumented-tests/stability) | 큰 UI/integration test의 안정화 원칙 | 0.93 |
| `T1` | [Martin Fowler: Test Driven Development](https://martinfowler.com/bliki/TestDrivenDevelopment.html) | red-green-refactor의 핵심 정의 | 0.92 |
| `S1` | [Martin Fowler: Specification By Example](https://martinfowler.com/bliki/SpecificationByExample.html) | example 기반 specification 개념 | 0.86 |
| `S2` | [Cucumber Gherkin Reference](https://cucumber.io/docs/gherkin/reference/) | executable spec 문법과 사용자 언어 원칙 | 0.85 |

## 4. Non-Negotiable Rules

### Rule 1. `.spec`가 code보다 앞선다

- 모든 사용자 가시적 동작 변경은 먼저 spec delta로 표현해야 한다.
- 이 저장소에서는 `.spec/`이 source of truth다.
- spec이 없으면 구현을 시작하지 않는다.

분류: `SDD`  
강제도: `MUST`  
Rule confidence: `1.00`  
Refs: `R1`, `R2`

### Rule 2. Spec change 없이는 behavior change를 merge하지 않는다

- 동작이 바뀌었는데 spec이 그대로면 drift다.
- spec이 바뀌었는데 test가 안 바뀌면 미완료다.
- test가 바뀌었는데 code가 안 맞으면 regression이다.

분류: `SDD/TDD`  
강제도: `MUST`  
Rule confidence: `0.98`  
Refs: `R1`, `R2`, `S1`

### Rule 3. 구현은 항상 failing test에서 시작한다

- 다음 기능의 가장 작은 예제를 고른다.
- 먼저 failing test를 쓴다.
- 그 테스트를 통과시키는 최소 구현만 추가한다.
- 그 다음에만 refactor한다.

분류: `TDD`  
강제도: `MUST`  
Rule confidence: `0.96`  
Refs: `T1`, `R2`

### Rule 4. Red-Green-Refactor 순서를 바꾸지 않는다

- `red` 없이 code-first 금지
- `green` 없이 refactor 금지
- refactor는 behavior-preserving change여야 한다

분류: `TDD`  
강제도: `MUST`  
Rule confidence: `0.96`  
Refs: `T1`, `R2`

### Rule 5. 테스트 대상은 Android가 권장하는 seam에 집중한다

- 최소 기준:
  - ViewModel unit test
  - data layer unit test
  - domain/use case unit test
  - critical navigation/UI regression test
- framework 자체를 재검증하는 테스트는 우선순위가 낮다.
- Activity/Fragment에 business logic를 넣고 그걸 unit test로 메우는 방식은 금지다.

분류: `TDD`  
강제도: `MUST`  
Rule confidence: `0.98`  
Refs: `A2`, `A4`, `A7`

### Rule 6. `test/`와 `androidTest/`를 섞지 않는다

- 순수 JVM에서 검증 가능한 것은 `test/`에 둔다.
- device/framework 통합 검증이 필요한 것은 `androidTest/`에 둔다.
- 느린 테스트를 빠른 테스트로 위장하지 않는다.

분류: `TDD`  
강제도: `MUST`  
Rule confidence: `0.98`  
Refs: `A1`, `A2`

### Rule 7. Fake를 기본값으로 하고, mock는 예외로만 쓴다

- 기본 원칙은 `prefer fakes to mocks`다.
- repository, data source, clock, dispatcher, network boundary는 fake로 대체 가능해야 한다.
- testability를 위해 DI가 필수다.

분류: `TDD`  
강제도: `MUST`  
Rule confidence: `0.98`  
Refs: `A3`, `A4`, `A5`

### Rule 8. UI test는 hermetic이어야 한다

- UI/integration test는 live network, 외부 시간, 외부 계정 상태에 의존하지 않는다.
- 외부 의존성은 fake/in-memory implementation으로 대체한다.
- 비결정적 데이터에 기대는 테스트는 merge blocker다.

분류: `TDD`  
강제도: `MUST`  
Rule confidence: `0.97`  
Refs: `A3`, `A5`, `A9`

### Rule 9. Critical flow는 반드시 navigation/UI regression test가 있다

- 주요 온보딩, 핵심 task flow, 저장/동기화/복구 경로는 UI 또는 navigation regression test로 잠근다.
- Navigation component 자체를 다시 테스트하지 말고, 우리 code와 `NavController` 상호작용을 테스트한다.

분류: `TDD`  
강제도: `MUST`  
Rule confidence: `0.96`  
Refs: `A4`, `A5`, `A7`

### Rule 10. Compose 시각 검증은 screenshot test를 우선 사용한다

- 색상, 간격, typography, 상태별 렌더링 같은 visual attribute는 screenshot test가 기본이다.
- 다만 golden 수를 최소화한다.
- screenshot fail은 무시하지 않고, 버그 수정 또는 golden 승인 중 하나로 반드시 처리한다.

분류: `TDD`  
강제도: `MUST`  
Rule confidence: `0.93`  
Refs: `A6`

### Rule 11. Big test는 flake를 관리 가능한 비용으로 통제해야 한다

- wait는 임의 sleep 대신 condition 기반 API를 사용한다.
- shared state를 줄인다.
- 필요 시 Orchestrator, retry, emulator restart를 사용하되 flaky root cause 수정을 미루는 핑계로 쓰지 않는다.

분류: `TDD`  
강제도: `MUST`  
Rule confidence: `0.93`  
Refs: `A9`, `A10`

### Rule 12. 모든 체크인은 CI에서 build + test를 통과해야 한다

- 로컬 green만으로는 충분하지 않다.
- CI는 매 체크인/PR마다 자동 실행되어야 한다.
- failing test 상태의 merge는 금지다.

분류: `TDD`  
강제도: `MUST`  
Rule confidence: `0.96`  
Refs: `A8`, `R2`

### Rule 13. SDD는 example로 쓴다. 그러나 example만으로 끝내지 않는다

- spec은 사용자 시나리오와 example를 중심으로 쓴다.
- 하지만 invariants, edge case, error case, state transition 제약도 함께 정의한다.
- example는 spec의 선두 수단이지 유일 수단이 아니다.

분류: `SDD`  
강제도: `MUST`  
Rule confidence: `0.90`  
Refs: `S1`, `R2`

### Rule 14. Spec 언어는 사용자/도메인 언어를 따른다

- spec의 용어는 사용자와 도메인 전문가가 실제로 쓰는 언어를 따른다.
- 코드 용어를 spec에 강요하지 않는다.
- 번역 비용이 큰 이중 용어 체계는 금지다.

분류: `SDD`  
강제도: `MUST`  
Rule confidence: `0.88`  
Refs: `S2`

### Rule 15. 모든 spec은 검증 가능한 example 또는 acceptance path를 가진다

- 모호한 선언문만 있는 spec은 금지다.
- 각 spec은 적어도 하나의 자동화 가능한 example, scenario, acceptance criterion으로 내려와야 한다.
- 테스트에서 spec reference를 추적할 수 있어야 한다.

분류: `SDD/TDD`  
강제도: `MUST`  
Rule confidence: `0.93`  
Refs: `S1`, `S2`, `R2`

## 5. Operational Workflow

모든 feature/bugfix는 아래 순서를 따른다.

1. spec 작성 또는 수정
2. acceptance/example 식별
3. failing unit test 또는 UI test 작성
4. 최소 구현으로 green 달성
5. green 유지 상태에서 refactor
6. screenshot/navigation/integration regression 보강
7. CI green 확인 후 merge

이 순서가 깨지면 완료로 인정하지 않는다.

## 6. Forbidden Behaviors

아래는 명시적으로 금지한다.

- spec 없이 바로 코드를 쓰는 행위
- failing test 없이 구현을 시작하는 행위
- red 상태에서 구조개선을 하는 행위
- mock 남용으로 설계를 테스트에 종속시키는 행위
- live network에 붙는 UI test를 회귀 테스트라고 부르는 행위
- flaky test를 `@Ignore`, 삭제, 재시도로만 숨기는 행위
- behavior 변경 후 `.spec` 갱신을 미루는 행위
- CI red 상태 merge

## 7. PR / Commit Gate Checklist

아래 질문 중 하나라도 `No`면 merge 금지다.

- behavior change가 `.spec`에 먼저 반영되었는가?
- 가장 작은 failing test가 먼저 작성되었는가?
- ViewModel/data/domain seam 중 적절한 unit test가 존재하는가?
- critical flow에 UI/navigation regression test가 존재하는가?
- UI test가 hermetic한가?
- Compose 시각 변경이면 screenshot test/golden 검토가 되었는가?
- refactor가 green 상태에서만 수행되었는가?
- 로컬과 CI 모두 green인가?

## 8. This Repo에서의 최종 선언

`becalm-android`에서는 다음 문장을 규율로 채택한다.

- `.spec` 없이는 구현하지 않는다.
- test 없이는 기능을 추가하지 않는다.
- green 없이는 refactor하지 않는다.
- spec, test, code가 동시에 맞지 않으면 완료가 아니다.

이 문서는 권고문이 아니라 운영 규칙이다.
