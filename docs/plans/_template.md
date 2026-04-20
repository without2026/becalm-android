# [Layer/Module/Logic] — <한 줄 요약>

**Branch**: `{feat|refactor|fix}/<layer>/<module>/<logic>`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: N — <스테이지 이름>
**Severity**: Critical | High | Medium | Low
**Type**: Drift | Gap | Dead-code

---

## 1. Finding

한 문단으로 무엇이 문제인지 요약. 스펙의 어느 ID가 위배되고, 코드의 어느 부분이 문제인지 명시.

---

## 2. Spec Contract (무엇이어야 하는가)

스펙에서 **원문 그대로 인용**. `.spec/**/*.spec.yml` 경로와 줄번호 명시.

- **`<.spec/module.spec.yml>:<line>`** — `<ID>`:
  > "인용문 직접 붙여넣기"

- **`<.spec/contracts/data-model.yml>:<line>`**:
  > "스키마 관련 인용"

여러 spec이 관련되면 모두 나열.

---

## 3. Code Reality (지금 무엇인가)

현재 코드에서 위배되는 부분을 **파일:줄번호 + 인용**으로 기록.

- **`<path/to/file.kt>:<line>`**:
  ```kotlin
  // 실제 코드 인용
  ```

- **`<path/to/another.kt>:<line>`**: <한 줄 설명>

Grep/Glob 검증 명령도 함께:
```bash
# 검증 grep
grep -rn "<pattern>" android/app/src/main/java/
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | 차이 |
|------|-----------|-----------|------|
| … | … | … | … |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술.

### 5.1 Files to change
- `<file>` — <무엇을 어떻게 바꾸는지>
- `<file>` — …

### 5.2 Files to add
- `<file>` — <역할>

### 5.3 Files to delete (dead code)
- `<file>` — <이유>

### 5.4 Non-code changes
- DB migration: …
- Config / manifest: …
- Permission 변경: …

---

## 6. Acceptance Criteria

다른 세션이 구현 완료 여부를 **기계적으로 검증**할 수 있는 항목.

- [ ] **Unit test**: `<test file>` — `<test name>` 가 통과한다
- [ ] **Integration test**: …
- [ ] **Grep invariant**: `grep -rn "<forbidden-pattern>" android/app/src/main/java/ | wc -l` 가 0 이다
- [ ] **Manual**: …

---

## 7. Out of Scope

이 PR에서 **건드리지 말 것**. 의도치 않은 scope creep 방지.

- …
- …

---

## 8. Dependencies

- **Blocked by**: PR#N (브랜치 이름)
- **Blocks**: PR#M (브랜치 이름)

merge 순서 / 병렬 가능 여부 명시.

---

## 9. Rollback plan

merge 후 문제 발생 시 원복 전략. Revert 한 줄이면 OK, schema migration 관련이면 데이터 복구 전략 명시.

---

## Appendix — Session handoff notes

구현 세션에게 전달할 추가 컨텍스트. 왜 이렇게 결정했는지, 검토한 대안, 발견한 함정 등.
