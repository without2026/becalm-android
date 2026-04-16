# L0–L2 결함 5건 — 수정 계획

이 문서는 zero-to-deploy / without-cto / 세션·스키마 검토에서 식별된 **5가지 결각**에 대한 수정 계획이다. 구현은 단계별로 PR을 나누는 것을 권장한다.

**구현 상태 (CTO 요청 반영):** 결함 **1·3·4·5**는 저장소에 반영됨 (`spec.contracts_profile`, `l0-result-schema.json` 계약 분기, `segment_complete` + 5–7 웨이브, Phase 5 게이트, `required-pr-checks.yml` + L1 스니펫, 세션 스키마 확장). 결함 **2**(`platform.yml` 전용 게이트)는 계획만 유지 — 별도 승인 시 적용.

---

## 결함 1 — Phase 2 스키마가 API·DB 최소 단위를 강제 (`l0-result-schema.json`)

**문제:** `contracts`에 `data_model.tables`·`api_contract.endpoints`가 각각 `minItems: 1`이라, 순수 클라이언트·로컬만·BFF 없음 등에서 형식 맞추기용 가짜 계약이 생길 수 있다.

**목표:** 플랫폼에 맞는 계약 모드를 허용하되, “계약 없이 구현”은 여전히 막는다.

| 단계 | 작업 | 산출물 |
|------|------|--------|
| 1.1 | `platform.yml`에 `contracts_profile` (또는 `platform` 값과 매핑되는 내부 규칙) 추가: `fullstack` \| `client_primary` \| `api_only` | `platform.yml` 예시 + `references/contract-format.md` 한 절 |
| 1.2 | `l0-result-schema.json`의 Phase 2 `contracts`를 `oneOf`로 분기: `fullstack`은 현행과 동일; `client_primary`는 `api_contract` 또는 `local_data_contract`(신규 최소 스키마) 중 하나 필수, `data_model`은 “로컬/동기화 엔티티” 허용·빈 배열 시 별 필드로 근거 요구 | `l0-result-schema.json` |
| 1.3 | without-cto Phase 2 문구에 “프로필별 필수 계약” 명시; 서브에이전트가 잘못된 프로필로 채우면 게이트에서 CTO가 거절 가능 | `without-cto/SKILL.md` |
| 1.4 | `validate_generated_changes.py` 또는 스펙 검증 스크립트가 있다면 동일 규칙 정렬 | `.github/scripts/` (해당 시) |

**의존성:** 1.1 → 1.2 → 1.3 순 권장.

---

## 결함 2 — Phase 1.5 `platform.yml` 전면 수정이 zero-to-deploy 사용자 게이트 밖

**문제:** Phase 2·4만 하드 게이트라, CI·배포·모니터링을 바꾸는 `platform.yml` 덮어쓰기가 구조적으로 덜 보인다.

**목표:** `platform.yml` diff가 **명시적 확인**을 거치도록 한다.

| 단계 | 작업 | 산출물 |
|------|------|--------|
| 2.1 | zero-to-deploy에 **Gate 1.5**(이름은 `platform_contract` 등): L0 서브에이전트가 `platform.yml`을 변경한 커밋이 있으면, PR 생성 **전** 또는 **직후** CTO에게 “변경 요약 + 어댑터·배포 영향”만 `AskQuestion`으로 확인 | `zero-to-deploy/SKILL.md`, `session-schema.json` (`gates_confirmed.platform_yml` 등) |
| 2.2 | L0 결과 스키마에 선택 필드 `platform_digest` (해시 또는 변경 파일 목록) 추가해 세션에 남김 | `l0-result-schema.json`, `session-schema.json` |
| 2.3 | without-cto Phase 1.5 끝에 “게이트 모드에서는 `platform_changed: boolean`을 complete/gate_blocked에 포함” 규칙 | `without-cto/SKILL.md`, `l0-brief.tmpl.md` |

**의존성:** 2.1이 세션 스키마 확장을 요구하면 2.2와 같은 PR에 묶어도 됨.

---

## 결함 3 — Layer 0 전체가 단일 서브에이전트에 담김 (문맥·품질 리스크)

**문제:** Phase 1–7이 한 `Task`에 몰리면 후반(구현·리뷰) 품질이 떨어질 수 있다.

**목표:** **페이즈 경계에서 재디스패치** 또는 **최소 2분할**(설계 블록 vs 구현 블록)으로 리스크를 낮춘다.

| 단계 | 작업 | 산출물 |
|------|------|--------|
| 3.1 | 정책 결정: **A)** Phase 4 게이트 승인 후 새 서브에이전트로 Phase 5–7만 실행, **B)** Phase 2 게이트 후 3–4와 5–7 분리 | `without-cto/SKILL.md` + `zero-to-deploy/SKILL.md` |
| 3.2 | zero-to-deploy: `gate_blocked` 해소 후 **resume 시** `phase_range: "5-7"` 같은 힌트를 브리프에 주입 | `l0-brief.tmpl.md` 플레이스홀더, 오케스트레이터 프롬프트 |
| 3.3 | 세션 `layer0.phase`를 실제로 갱신하도록 오케스트레이터 규칙 명문화 (폴링/재개 시) | `zero-to-deploy/SKILL.md`, `session-schema.json` (이미 `phase` 필드 있음) |

**의존성:** 2.x와 충돌 없음. 3.2는 3.1 결정 후.

---

## 결함 4 — Phase 5 비수렴 시 서브에이전트가 임의로 “최저 리스크” 선택

**문제:** 토론 미수렴이 `risks` 한 줄로 묻히면 고비용 결정이 약하게 고정될 수 있다.

**목표:** **비수렴 = 게이트** 또는 **문서화된 에스컬레이션**으로만 종료.

| 단계 | 작업 | 산출물 |
|------|------|--------|
| 4.1 | without-cto: 서브에이전트 모드에서 `NO CONVERGENCE`이면 **자동 최저안 선택 제거** → `gate_blocked` + `gate: "phase5"` + 페이로드에 대립안 2–3개 | `without-cto/SKILL.md` |
| 4.2 | `l0-result-schema.json`에 `phase5Payload` (선택적) 또는 `gate` enum에 `phase5` 추가 | `l0-result-schema.json` |
| 4.3 | zero-to-deploy: Gate 1.5와 별도로 **Gate 5**(토론 타브레이크) — 짧은 선택지 | `zero-to-deploy/SKILL.md`, `session-schema.json` `gates_confirmed` |

**의존성:** 4.2 → 4.1 → 4.3 (스키마 먼저 확장하면 오케스트레이터가 파싱 가능).

---

## 결함 5 — L1: 체크 이름 매핑·`skipped`=통과·실패 시 얕은 관측

**문제:** 필수 잡 이름 변경 시 누락 가능; `skipped`가 정책상 실패여야 하는 경우 구분 안 됨; 오케스트레이터가 로그를 깊게 읽지 않음.

**목표:** **필수 체크 목록을 설정 파일로 고정**하고, L1 실패 시 **최소 한 단계 RCA**를 스킬에 넣는다.

| 단계 | 작업 | 산출물 |
|------|------|--------|
| 5.1 | `.pipeline/orchestrator/required-pr-checks.yml` (또는 `platform.yml` 하위 키)에 카테고리별 **필수 `name` 정규식 또는 정확한 이름** 나열 | 신규 YAML |
| 5.2 | zero-to-deploy L1: 매핑 테이블 + **필수 목록 교차 검증** — 필수 중 하나도 매핑되지 않으면 `L1_FAILED` + 메시지 | `zero-to-deploy/SKILL.md` |
| 5.3 | `skipped` 정책: `merge_gate`·`deterministic_gates`·`adapter_tests`는 depth/플랫폼에 따라 “skip 허용 화이트리스트”만 success와 동급; 그 외 skip은 실패로 취급 옵션 | 동일 |
| 5.4 | L1 실패 후: `gh run view`로 실패 잡 하나 골라 **로그 마지막 80줄**을 세션 `layer1.failure_summary`에 붙이기 (스킬 절차) | `zero-to-deploy/SKILL.md`, `session-schema.json` (필드 이미 있으면 활용) |

**의존성:** 5.1 → 5.2 → 5.3. 5.4는 병렬 가능.

---

## 권장 PR 분할

| PR | 포함 결함 | 비고 |
|----|------------|------|
| PR-A | 5.1–5.4 | CI/오케스트레이터만, 스키마 변경 최소 |
| PR-B | 4.1–4.3 | 게이트 + 스키마, UX(AskQuestion) 증가 |
| PR-C | 1.1–1.3 | 스키마·문서, 하위 호환 `fullstack` 기본값 |
| PR-D | 2.1–2.3 + 3.x | 세션·z2d·브리프, 범위 큼 → 마지막 또는 2PR로 분리 |

**순서 제안:** PR-A (L1 신뢰도) → PR-C (계약 정직도) → PR-B (Phase 5) → PR-D (플랫폼 게이트 + L0 분할).

---

## 완료 정의 (전부 적용 후)

- [ ] 순수 클라이언트 저장소에서 Phase 2 게이트를 **가짜 API 없이** 통과할 수 있다.
- [ ] `platform.yml` 변경이 zero-to-deploy에서 **명시적 확인**을 거친다.
- [ ] Phase 5 미수렴 시 **사람 또는 문서화된 gate** 없이는 구현 단계로 내려가지 않는다.
- [ ] L1은 **필수 체크 누락**을 조용히 통과하지 않는다.
- [ ] L1 실패 시 세션에 **재현 가능한 한 줄 요약 + 로그 스니펫**이 남는다.
- [ ] (선택) Phase 4 이후 L0가 **재디스패치**될 수 있다.
