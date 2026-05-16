# Self Identity 기반 Person Matching SDD

날짜: 2026-05-16 KST

## 목표

Person matching의 중심은 counterparty 후보가 아니라 현재 사용자(self)다. BeCalm은
업무 관계를 "내가 누구에게 무엇을 줘야 하는가 / 누구에게서 무엇을 받아야 하는가"로
정리해야 하므로, self identity가 확정되지 않은 상태에서 사람, 약속, memory를 자동으로
확정하면 안 된다.

## 핵심 불변식

1. Self identity set은 auth email, provider account email, user-confirmed email/phone,
   user-confirmed alias, source-scoped speaker label을 포함한다.
2. Email, phone, provider email, source-scoped self speaker는 strong anchor다. 매칭되면
   counterparty person을 만들지 않고 `self_resolved`로 처리한다.
3. Alias/name anchor는 weak anchor다. 매칭되면 person을 만들지 않되 `suggested_self`로
   남겨 사용자가 확인한다.
4. Source connection ownership은 `self`, `shared`, `delegated`, `unknown`만 사용한다.
   `self`는 provider account identity를 self anchor로 연결한다. Non-self ownership은
   해당 connection을 self로 쓰지 않는다.
5. 여러 source connection이 모두 `self`일 수 있다. 업무용 Gmail, daily Gmail, calendar,
   IMAP처럼 여러 provider account email이 동시에 self identity set에 들어갈 수 있다.
6. Self anchor와 충돌하는 source participant 또는 commitment counterparty는
   `people`, `person_identities`, `commitment_participants`를 생성하지 않는다.
7. Self identity set 변경 후에는 affected source/person/memory projection이 재평가되어야
   한다.
8. `memory.md`는 counterparty memory다. Self로 판정된 identity/person은 counterparty
   memory로 생성되거나 강화되면 안 된다.
9. 충돌 또는 약한 매칭은 사용자가 확인할 수 있어야 한다. 자동 merge, 자동 split, 자동
   self 확정은 strong anchor에서만 허용한다.

## Slice 순서

### Slice 1. Source ownership contract

- Backend와 Android가 같은 ownership enum을 사용한다.
- 여러 source connection을 `self`로 지정하면 provider email anchor가 누적된다.
- `delegated/shared/unknown`은 self anchor를 새로 만들지 않는다.

### Slice 2. Onboarding self identity gate

- 로그인 직후 source 연결 전에 사용자가 기본 self profile을 확인한다.
- Display name, email, phone, alias 입력/수정이 가능하다.
- 저장 결과는 backend self identity anchors와 Android local mirror에 반영된다.
- Android 완료 기준:
  - Onboarding setup에서 display name, email, phone, alias를 입력할 수 있다.
  - Profile 저장은 `user_profile` mirror를 갱신하고 email/alias는 self identity anchor로 생성한다.
  - Settings에서도 email, phone, alias anchor를 추가할 수 있다.
  - 저장 후 source 연결 gate가 열린다.

### Slice 3. Source connection ownership onboarding

- Gmail/Calendar/IMAP/Outlook 연결 완료 후 "이 계정은 내 계정인가"를 확인한다.
- 업무용/개인용처럼 여러 self source를 추가할 수 있다.
- Shared/delegated source는 self matching anchor로 쓰지 않는다.
- Android 완료 기준:
  - Onboarding setup에서 연결된 source connection의 ownership을 확인한다.
  - 연결 계정이 `unknown`이면 setup 완료를 막고 사용자에게 선택을 요구한다.
  - Onboarding에서는 `self`, `shared`, `delegated` 중 하나를 명시적으로 선택해야 한다.
  - Settings에서는 복구/보류 목적의 `unknown` 값을 계속 지원한다.

### Slice 4. Extractor input self context

- Backend extraction/classification input에 active self identity set을 전달한다.
- `relation_to_user`, `person_ref`, `direction`은 self context로 post-verify한다.
- Wrong self/counterparty, wrong give/take severe fixture를 유지한다.
- 완료 기준:
  - Classifier에서 non-work/marketing/no-op으로 걸러진 email은 self anchor DB read를 하지 않는다.
  - Extractor prompt에는 active self identity context만 축약해 포함한다.
  - Model이 self identity를 `person_ref` 또는 counterparty participant로 잘못 반환해도 backend post-verify가 정정한다.
  - Sent mail에서 counterparty가 call/contact/time/link를 제공해야 하는 요청은 model이 `give`로 반환해도 `take`로 보정한다.

### Slice 5. Reindex cascade

- Self identity anchor 또는 source ownership 변경 시 source participants,
  commitment participants, person interactions, unmatched review, memory projection을
  재계산한다.

### Slice 6. Memory hardening

- `memory.md` 입력 collector가 self-resolved/suggested-self rows를 제외한다.
- 기존 self-person memory를 탐지하고 재생성 또는 삭제할 수 있는 repair path를 둔다.

### Slice 7. Conflict review, merge/split/undo

- Weak self match, competing person, wrong person correction을 review queue로 노출한다.
- 사용자 확인 결과를 backend feedback/audit trail로 저장한다.
- Merge, split, self-confirm, not-self, undo를 지원한다.

## TDD 기준

각 slice는 다음 순서로 진행한다.

1. SDD 불변식 또는 slice 문서를 먼저 갱신한다.
2. 실패하는 unit/contract/integration test를 먼저 추가한다.
3. 최소 구현으로 test를 green으로 만든다.
4. 변경으로 생긴 dead code와 enum drift를 정리한다.
5. Android는 필요한 경우 commit/push한다. Backend는 현재 정책대로 Railway 재배포만 한다.
