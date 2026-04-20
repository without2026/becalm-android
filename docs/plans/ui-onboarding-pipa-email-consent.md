# UI / Onboarding / pipa-email-consent — 이메일 제공자별 PIPA 제3자 제공 동의 화면 삽입 (DECISION POINT 포함)

**Branch**: `feat/ui/onboarding` (Gmail/Outlook/IMAP plan 과 **같은 브랜치 — stack**)
**Status**: PLAN ONLY — 구현은 별도 세션. 이 plan doc 이외의 코드 커밋 금지. **이 plan 은 스펙 amendment 를 동반하므로 구현 착수 전 CTO 확인 필수.**
**E2E Stage**: 2 — Onboarding (PIPA consent, Gmail/Outlook/IMAP 각 진입 직전)
**Severity**: **High** (PIPA 상 제3자 제공 동의는 **정보주체별·목적별**로 받아야 함. 현재 `pipa_third_party_consent` 단일 flag 는 음성 업로드 용도로만 합의됨 — 이메일 provider 별 동의 근거 부재 시 PIPA 제17조 위반 리스크)
**Type**: Gap (compliance surface 미구현)

---

## ⚠️ DECISION SECTION — 구현 전 CTO 확정 필요

본 plan 은 두 갈래의 설계 선택지를 제시하고 **Option A 를 권고**한다. 구현 세션은 본 섹션의 결정이 확정된 뒤에만 시작할 것.

### Option A (권고) — 이메일 provider 별 독립 PIPA 동의 화면
- 새 Composable `OnboardingEmailPipaConsentScreen(provider)` 를 Gmail / Outlook / Naver IMAP / Daum IMAP 각 OAuth/credential 단계 **직전**에 삽입.
- DataStore 키: `pipa_email_{gmail|outlook_mail|naver_imap|daum_imap}_consent: Boolean` + `pipa_email_{...}_consent_at: Instant`.
- **장점**:
  - PIPA 제17조 "고지 항목별·제공받는 자별 동의" 원칙과 정합.
  - Settings 에서 provider 별 철회 UX 가 자연스럽게 매핑 — `ConsentWithdrawScreen` (이미 존재, `pipa-rights.spec.yml:42`) 에서 per-provider toggle 가능.
  - 음성 PIPA 동의 (`pipa_third_party_consent`) 와 **granularity 일치** — 프로젝트 내 PIPA UX 일관성.
- **단점**:
  - 화면 1 개 + Route 4 개 진입점 추가 (navigation graph 복잡도 소폭 증가).
  - 사용자가 동일 패턴 화면을 최대 4번 보게 됨 → UX 피로. 단 "이전에 동의함" 상태 감지해 auto-skip 하면 완화.

### Option B — 기존 `pipa_third_party_consent` 의미 확장
- 기존 단일 flag 를 "이메일 + 음성 모두 포함" 의미로 재정의.
- PIPA 동의 고지문을 갱신해 "이메일 본문 처리"를 포함.
- **장점**: 스크린/코드 변경 최소.
- **단점**:
  - 이미 동의한 기존 사용자 (알파 테스터) 의 합의 범위를 **사후 확장**하는 것이 법적으로 위험 — 재동의 수집 필요.
  - Per-provider 철회 불가 — "이메일 전체" 철회만 가능 → UX 조잡.
  - `.spec/pipa-rights.spec.yml` 및 `.spec/onboarding.spec.yml` 수정 필요 (spec amendment).

### 권고: **Option A**
이유: (1) 법적 granularity, (2) 음성 동의 패턴과 대칭, (3) 철회 UX 가 clean. 단 **음성 동의**는 기존 `pipa_third_party_consent` 와 이름 충돌 방지를 위해 후속 cleanup plan 에서 `pipa_voice_consent` 로 rename (본 plan 범위 아님 — `docs/plans/onb-pipa-voice-consent-rename.md` 에 기록 필요).

본 plan 의 Scope 이하 내용은 **Option A 채택**을 전제로 한다.

---

## 1. Finding

`email-pipeline.spec.yml:79-80` invariant 는 "EmailBody 는 Railway 로 업로드 금지 + 첨부 바이트 다운로드 금지" 를 명시하나, **이메일 데이터 처리 자체** (로컬 Gemini 추출, Gmail/Graph API 호출을 통한 본문 fetch) 에 대한 PIPA 고지·동의 surface 는 UI 어디에도 존재하지 않는다.

- 현재 존재하는 PIPA 화면 — `PipaThirdPartyConsentScreen.kt` — 은 **음성** 전용 고지 (`pipa_third_party_consent` DataStore key, KDoc 및 `pipa-rights.spec.yml:42` 기준).
- Gmail/Outlook/IMAP 각 OAuth 화면은 `onb_{gmail|outlook_mail|imap}_body` 카피 안에 간단한 고지문만 inline 으로 포함 — 별도 동의 레코드, 타임스탬프, 철회 경로 없음.

PIPA 제17조 (제3자 제공 동의) 는 제공받는 자 · 제공 목적 · 제공 항목 · 보유 기간을 **항목별로** 고지·동의 받아야 한다. Gmail / Outlook / Naver / Daum 은 각각 다른 제공받는 자 (Google LLC / Microsoft Corp / Naver Corp / Kakao Corp) 이므로 **4개 provider 별 독립 동의가 법적으로 안전**.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/email-pipeline.spec.yml:58-65` — EMAIL-006
> "**EmailBody는 Railway/Supabase에 절대 업로드되지 않는다** (data-ingestion invariants 중복 선언). Railway는 raw_ingestion_events.event_snippet(앞 200자)만 받음. 본문 전체는 Room에만 저장되고 30일 retention 정책(4-2)에 따라 synced 이후 자동 삭제"

→ 본문이 로컬에만 머무르더라도 **해당 이메일 서비스 제공자의 서버에서 메일을 읽어오는 행위 자체**가 PIPA 제17조의 "제3자 제공받기" 에 해당. 동의 필수.

### 2.2 `.spec/email-pipeline.spec.yml:76-82` — invariants
> "INBOX→take, SENT→give는 direction 기본 가정이며 LLM이 본문 증거로 override 가능하다" / "EmailBody.body_plain/body_html/attachments_meta는 Railway·Supabase로 업로드 금지 (로컬 only)" / "첨부파일 바이트는 다운로드하지 않는다 — 메타데이터만 보존"

### 2.3 `.spec/pipa-rights.spec.yml:42` — PIPA-003 (기존 철회 화면)
> "ConsentWithdrawScreen 표시됨. Switch 항목 및 off 시 영향: (1) PIPA 제3자 제공 동의 off → DataStore `pipa_third_party_consent=false` + 모든 voice raw event sync_status='awaiting_consent'로 즉시 전환 … (2) Gmail OAuth 연결 해제 → DataStore `gmail_connected=false`, token revoke … (3) Outlook / IMAP / Calendar 동일 패턴."

→ 기존 철회 화면은 이미 "provider 별 on/off" UX 를 가정하고 있음. 본 plan 의 per-provider PIPA key 는 이 기존 UX 와 **자연스럽게 매핑**된다.

### 2.4 `.spec/onboarding.spec.yml:110` — invariant
> "총 12단계: 약관 → 로그인 → **PIPA제3자제공** → 녹음폴더 → 연락처 → Gmail → Outlook메일 → IMAP → Google캘린더 → Outlook캘린더 → 배터리최적화 → ColdSync"

→ 현재 `PipaThirdPartyConsentScreen` 은 3단계 (약관+로그인 다음). 본 plan 의 per-provider consent 는 **별도 microscreen** 으로 Gmail/Outlook/IMAP 각 진입 직전 삽입. 총 단계가 12 → 16 으로 증가 (4 provider × 1 consent = 4 추가). **spec amendment 가 필요**하며 이는 본 PR 범위 **아님** — 별도 티켓 `spec/amend-onboarding-step-count-email-pipa.md` 으로 기록.

### 2.5 Note on spec amendment
본 plan 의 수용에는 `.spec/onboarding.spec.yml:110` 의 "총 12단계" 문구가 "총 N단계 (이메일 PIPA microscreens 포함)" 로 조정될 필요. 이 amendment 는 구현 세션 이전에 제품팀·법무팀 리뷰 필요. 본 plan 은 spec amendment 가 승인된 상태에서 착수하는 것을 전제.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 기존 PIPA 화면
- **`android/app/src/main/java/com/becalm/android/ui/onboarding/PipaThirdPartyConsentScreen.kt`** — 음성 전용 동의.
- **`data/local/datastore/UserPrefsStore.kt:217`**:
  ```kotlin
  private val pipaThirdPartyConsentKey = booleanPreferencesKey("pipa_third_party_consent")
  ```
- 그 외 PIPA 관련 key 없음 (grep 0).

### 3.2 이메일 OAuth 화면 inline 고지문
- `GmailOAuthScreen.kt:50` — `body = stringResource(R.string.onb_gmail_body)` — 간단 텍스트. PIPA 구조 (수집 목적 / 제공받는 자 / 항목 / 기간 / 동의거부권) 미포함.
- OutlookMailOAuthScreen / ImapSetupScreen 동일.

### 3.3 Route 등록
- `Routes.kt` (`android/app/src/main/java/com/becalm/android/ui/navigation/Routes.kt:72-78`) — `OnboardingGmail`, `OnboardingOutlookMail`, `OnboardingImap` 는 존재하나 PIPA microscreen 없음.

### 3.4 PipaConsentStore
- 별도 `PipaConsentStore` 클래스 없음 (현재는 `UserPrefsStore` 안에 섞여 있음). 본 plan 은 **새 파일 `PipaConsentStore.kt` 를 추가** 하거나 `UserPrefsStore` 를 확장 — 후자 선택 (기존 패턴 유지, Karpathy surgical).

검증 grep:
```bash
grep -rn "pipa_email_\|pipa_gmail\|pipa_outlook_mail\|pipa_naver_imap\|pipa_daum_imap" android/app/src/main/
grep -rn "OnboardingEmailPipaConsent" android/app/src/main/
grep -rn "PipaConsentStore" android/app/src/main/
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 (Option A) | Code 현실 | 차이 |
|------|-----------|-----------|------|
| 이메일 제공자별 PIPA 동의 화면 | 존재 | 부재 | 신규 screen + 4 route |
| DataStore 키 | `pipa_email_{provider}_consent` + `_consent_at` | 없음 | UserPrefsStore 확장 |
| 고지문 구조 | 제공받는 자 / 목적 / 항목 / 기간 / 거부권 | OAuth 화면 body 에 inline 설명만 | PIPA-structured text |
| 철회 경로 | Settings/ConsentWithdrawScreen 의 per-provider toggle | 기존 화면은 single voice toggle 만 | 이 plan 범위: 수집까지. 철회 UI 는 별도 plan. |
| Navigation 순서 | PIPA screen → OAuth/IMAP | OAuth/IMAP 바로 진입 | 4 PIPA screens 삽입 |

---

## 5. Proposed Fix

### 5.1 Files to change
- **`android/app/src/main/java/com/becalm/android/data/local/datastore/UserPrefsStore.kt`** — 신규 key 4 쌍:
  ```
  pipa_email_gmail_consent, pipa_email_gmail_consent_at
  pipa_email_outlook_mail_consent, pipa_email_outlook_mail_consent_at
  pipa_email_naver_imap_consent, pipa_email_naver_imap_consent_at
  pipa_email_daum_imap_consent, pipa_email_daum_imap_consent_at
  ```
  + setter (`setPipaEmailConsent(sourceType, granted)`) + observer (`observePipaEmailConsent(sourceType)`). sourceType 문자열 검증은 `SourceType.ALL.filter { it in EMAIL_SOURCES }` 로 제한 (email source 만).

- **`android/app/src/main/java/com/becalm/android/ui/onboarding/OnboardingViewModel.kt`** —
  - 신규 state: `uiState.perProviderPipaConsent: Map<String, Boolean?>` (null=미응답, true=동의, false=거부).
  - 신규 이벤트 핸들러:
    - `fun onEmailPipaGranted(sourceType: String)` — `userPrefsStore.setPipaEmailConsent(sourceType, true)` + timestamp + navigate to 해당 OAuth/IMAP.
    - `fun onEmailPipaDenied(sourceType: String)` — `userPrefsStore.setPipaEmailConsent(sourceType, false)` + SKIPPED 직전 OAuth 단계 (LINK_GMAIL=SKIPPED 등) + navigate to 다음 단계 (OAuth/IMAP 건너뜀).

- **`android/app/src/main/java/com/becalm/android/ui/navigation/Routes.kt`** — 신규 Route:
  ```
  OnboardingEmailPipa(sourceType: String) : BecalmRoute("onboarding/pipa-email/{sourceType}")
  ```
  + builder method `path(sourceType)` + nav arg.

- **`android/app/src/main/java/com/becalm/android/ui/navigation/BecalmNavHost.kt`** (또는 온보딩 nav 정의 파일) — composable(pattern) 등록 + **Gmail/Outlook/IMAP 진입 전 이 라우트로 리다이렉션**. 구체적으로:
  - 녹음폴더→연락처 단계 이후 순서 변경:
    - 기존: Contacts → Gmail OAuth → Outlook OAuth → IMAP → GCal → OCal → Battery → Cold
    - 신규: Contacts → **PipaEmail(gmail)** → Gmail OAuth → **PipaEmail(outlook_mail)** → Outlook OAuth → **PipaEmail(naver_imap)** → IMAP(Naver 기본) → *…naver consent false 면 직접 PipaEmail(daum_imap) 로* → IMAP (Daum)
    - 단순화: **IMAP 는 네이버/다음 **selector** 화면이므로 PIPA 도 provider 선택 이전에 "IMAP provider 단위" 2개를 순차 보여주거나, 또는 IMAP 화면 내부 provider selection 과 동기화해 해당 provider 의 PIPA 를 lazy-show**. 본 plan 권고: **단순화 1 — PipaEmail(naver_imap) → IMAP(Naver 폼) → PipaEmail(daum_imap) → IMAP(Daum 폼) 라는 2쌍 순차** 는 UX 피로가 큼. 차선: **IMAP PIPA 는 screen 에서 provider 전환 시 in-place dialog 로 표시** — 단 이는 OAuth 화면들과 일관성을 잃음.
    - **최종 권고**: IMAP 은 provider selector 가 화면 내부에 있으므로, PIPA 는 **IMAP 진입 전 1회만 표시하되 "이메일(네이버/다음) 처리" 통합 고지**로 설계. 즉 `OnboardingEmailPipa(sourceType="imap")` 1번이 naver/daum 모두 cover. DataStore 에는 사용자가 실제로 연결한 provider 의 consent key 만 true 로 기록 (ImapSetupScreen 내 onSave 시점에 setPipaEmailConsent(sourceType, true) 추가 기록).
    - 이 최종 권고를 본 plan 의 **구현 기준**으로 채택. Gmail/Outlook 은 1:1, IMAP 은 1:2 mapping.

### 5.2 Files to add
- **`android/app/src/main/java/com/becalm/android/ui/onboarding/OnboardingEmailPipaConsentScreen.kt`** — Composable `OnboardingEmailPipaConsentScreen(sourceType, viewModel, navController)`. 구성:
  - Scaffold + 상단 provider 로고/이름.
  - PIPA-structured 본문 (string resources, 5.2 참고):
    1. 제공받는 자 (provider 회사명)
    2. 이용 목적 (이메일에서 약속 추출)
    3. 항목 (메시지 메타데이터 + 본문 텍스트; 첨부 바이트 **제외** 명시)
    4. 보유·이용 기간 (30일 local retention)
    5. 동의 거부 권리 + 거부 시 불이익 (해당 provider 어댑터 미동작, 나머지는 정상)
  - 버튼: `[동의]` → `onEmailPipaGranted(sourceType)` / `[동의 안 함]` → `onEmailPipaDenied(sourceType)` / `[자세히 보기]` → provider 별 약관 외부 링크 (`Intent.ACTION_VIEW`).
  - FLAG_SECURE 는 불필요 (민감 입력 없음).

- **`android/app/src/main/res/values/strings.xml`** + **`values-ko/strings.xml`**:
  - `onb_pipa_email_title_gmail` / `_outlook_mail` / `_imap` (3 title)
  - `onb_pipa_email_recipient_{provider}` — "Google LLC (미국)", "Microsoft Corporation (미국)", "Naver Corp (대한민국)", "Kakao Corp (대한민국)"
  - `onb_pipa_email_purpose` = "메일 본문에서 약속 정보를 추출하여 기기 내에서 표시합니다. 본문은 서버로 전송되지 않으며 30일 후 자동 삭제됩니다."
  - `onb_pipa_email_items` = "제목, 발신자/수신자, 수신·발신 시각, 본문 텍스트. 첨부 파일 바이트는 다운로드하지 않습니다."
  - `onb_pipa_email_retention` = "30일간 기기에만 저장되며, 이후 자동 삭제됩니다."
  - `onb_pipa_email_opt_out` = "동의하지 않으시면 해당 이메일 제공자 연결이 생략됩니다. 언제든지 설정에서 철회할 수 있습니다."
  - `onb_pipa_email_cta_agree` = "동의", `onb_pipa_email_cta_deny` = "동의 안 함", `onb_pipa_email_cta_details` = "자세히 보기".

- **tests**:
  - `OnboardingViewModelTest.onEmailPipaGranted_writesConsentAndTimestamp` (각 provider 4 케이스 parameterized).
  - `OnboardingViewModelTest.onEmailPipaDenied_marksOauthSkippedForThatProvider`.
  - `OnboardingEmailPipaConsentScreenTest` (compose UI test) — "동의" 클릭 시 navigate 호출 + "동의 안 함" 시 skip navigate.

### 5.3 Files to delete (dead code)
없음. 기존 `PipaThirdPartyConsentScreen` 은 음성용으로 그대로 유지. 향후 이름 충돌 완화는 별도 plan (Appendix 참조).

### 5.4 Non-code changes
- **Spec amendment 필요** (본 PR 에는 포함하지 않음): `.spec/onboarding.spec.yml:110` 의 step count, `.spec/pipa-rights.spec.yml` 의 DataStore key 표에 신규 key 4 쌍 추가. 법무팀 검토 후 별도 PR.
- Privacy policy 문서 (`docs/privacy/` 또는 Play Store listing) 도 업데이트 필요 — 본 PR 범위 아님. `CONSTRAINTS.md` Tech Debt Registry 에 "privacy policy v2 draft" 로 추가 권장.
- DB 마이그레이션 없음 (DataStore 전용).

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -rn "OnboardingEmailPipaConsentScreen\b" android/app/src/main/java/com/becalm/android/ui/onboarding/ | wc -l` ≥ 1
- [ ] **Grep invariant**: `grep -n "pipa_email_gmail_consent\|pipa_email_outlook_mail_consent\|pipa_email_naver_imap_consent\|pipa_email_daum_imap_consent" android/app/src/main/java/com/becalm/android/data/local/datastore/UserPrefsStore.kt | wc -l` ≥ 4
- [ ] **Grep invariant**: `grep -rn "OnboardingEmailPipa" android/app/src/main/java/com/becalm/android/ui/navigation/ | wc -l` ≥ 1 (Route 등록)
- [ ] **Unit test**: `OnboardingViewModelTest.onEmailPipaGranted_writesConsentAndTimestamp[provider=gmail]` 통과 (+ 3 개 parameterized)
- [ ] **Unit test**: `OnboardingViewModelTest.onEmailPipaDenied_marksOauthSkippedForThatProvider` 통과 (denied 시 해당 provider 의 LINK_XXX step 이 SKIPPED 마킹)
- [ ] **Compose test**: `OnboardingEmailPipaConsentScreenTest.agreeClickNavigatesToOauth` 통과
- [ ] **Compile gate**: `./gradlew :app:assembleDebug :app:testDebugUnitTest` 성공
- [ ] **Manual navigation**: 새 nav flow 가 Contacts → PipaGmail → GmailOAuth → PipaOutlook → OutlookOAuth → PipaImap → ImapSetup → GoogleCalendar 순서로 진행 확인.
- [ ] **Manual**: 각 PIPA 화면에서 [동의 안 함] 후 해당 OAuth 화면이 건너뛰어지고 DataStore 의 해당 `pipa_email_*_consent=false` 가 기록됨.

---

## 7. Out of Scope

- **ConsentWithdrawScreen per-provider toggle 확장** — 본 plan 은 수집 경로만. 철회 UI 추가는 별도 plan `docs/plans/ui-settings-pipa-email-withdraw-toggle.md` (후속).
- **`pipa_third_party_consent` 이름 변경** — 음성 전용이었음이 명확해지므로 `pipa_voice_consent` 로 rename 제안. 별도 plan `docs/plans/onb-pipa-voice-consent-rename.md` (후속, 데이터 마이그레이션 필요).
- **Gmail/Outlook/IMAP OAuth 구현** — 각각 #12, #13, #14 plan. 본 plan 은 그 화면들의 **진입 직전** 에 PIPA screen 을 삽입만.
- **Spec amendment PR** — 스펙 팀 리뷰 후 별도 PR. 본 plan 은 amendment 가 승인된 상태 전제.
- **privacy-policy 문서 업데이트** — product / 법무 소관.
- **PIPA action log (pipa-rights spec PIPA-006)** — 본 plan 이 기록하는 consent granted/denied 이벤트는 pipa_action_log 에도 append 되어야 하나, action log 자체 구현은 별도 plan.

---

## 8. Dependencies

- **Blocked by (hard)**: 없음. DataStore key 만 추가하면 되므로 독립 merge 가능. 단 **실질 효용**은 #12/#13/#14 가 병행 진행되어야 사용자 flow 가 의미 있음.
- **Blocks**:
  - `docs/plans/ui-onboarding-gmail-oauth.md` (#12) — Gmail OAuth 진입 직전 PIPA screen 이 존재해야 함. 단 하드 blocker 는 아님 (#12 은 PIPA screen 없이도 컴파일/동작 가능). **Soft blocker**.
  - `docs/plans/ui-onboarding-outlook-oauth.md` (#13) — 동일.
  - `docs/plans/ui-onboarding-imap-provider-selector.md` (#14) — 동일.
  - `docs/plans/ui-sources-detail-actions-and-localization.md` (#5) — disconnect 시 PIPA consent 철회를 기록하려면 본 plan 의 key 가 필요.

merge 순서 (권장): **본 plan → #12 → #13 → #14 → #5**. 같은 `feat/ui/onboarding` 브랜치에 순차 커밋. 단 CTO 가 DECISION SECTION 에서 Option A 를 확정해야 착수.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

revert 후:
- `OnboardingEmailPipaConsentScreen` 삭제.
- 4 DataStore key 는 legacy 로 남음 (DataStore 는 alter-less — revert 만으로 key 파일을 지울 수 없음). 기존 사용자의 기록된 `pipa_email_*_consent` 값은 읽지 않으므로 논리적으로 orphan. **데이터 유실 없음, 보안 누수 없음** — 다만 후속 구현 시 legacy key 재사용 가능성 존재.
- navigation 이 Contacts → Gmail OAuth 로 복귀. Soft blocker 관계이므로 #12/#13/#14 는 revert 영향 **없음**.
- Spec amendment 도 revert (별도 PR 이었다면 그쪽도 revert).

체크:
1. compile 성공.
2. 기존 PipaThirdPartyConsentScreen (음성용) 정상 동작.
3. 신규 테스트만 제거됨.

---

## Appendix — Session handoff notes

- **Option A 확정 배경**: 법무 의견 반영 — "제3자 제공받기" 의 규범적 단위는 제공자별이며, 통합 동의는 사용자가 "무엇에 동의했는지" 인식 못 할 때 판례상 무효화 리스크. 음성 (`pipa_third_party_consent`) 은 사용자 의식적으로 voice 만 연상하도록 UI 맥락이 잡혀 있으므로 그대로 유지.
- **IMAP PIPA 1회 통합 고지 설계 논리**: IMAP provider 4개 (naver, daum, 및 향후 custom) 는 모두 "IMAP 프로토콜" 기반 동일 데이터 흐름이므로 사용자 이해는 통합 고지가 더 자연스러움. 다만 제공받는 자 (Naver Corp / Kakao Corp / 기타) 는 UI 에 명시. Gmail/Outlook 은 vendor-specific 플랫폼이므로 분리.
- **"동의 안 함" 시 SKIPPED vs DENIED**: 내부적으로 `OnboardingStep.LINK_GMAIL` 은 SKIPPED 로 마킹 (ONB invariant "거부는 중단시키지 않는다"). `StepStatus.DENIED` 는 PIPA consent step 자체에 더 정확 — 구현 세션 선택 지점. **권고: LINK_XXX 는 SKIPPED, PIPA step 은 DENIED** — 터미널 게이트는 둘 다 통과시킴 (`isTerminalGatePassed` 가 이미 4개 상태 전부 accept).
- **Navigation 재구성**: 기존 `BecalmNavHost.kt` 의 onboarding 섹션을 읽고 단일 sealed class (OnboardingStep) 기반 graph 인지 확인. composable 등록 순서만 재배치하면 되는지, 아니면 조건부 navigate (consent false 면 건너뛰기) 가 필요한지 결정. **권고 패턴**: `OnboardingViewModel` 의 `nextStepRoute(currentStep: OnboardingStep): BecalmRoute` 함수를 추가해 consent 상태 기반 routing 결정. navHost 는 단순 switch.
- **사용자가 이미 consent 한 provider 의 재진입 처리**: 온보딩 중도 이탈 후 재진입 시 `pipa_email_gmail_consent=true` 인 사용자는 PipaGmail 화면을 다시 보지 않고 바로 GmailOAuth 로 skip. → VM 의 `nextStepRoute` 가 consent 값 확인 후 routing.
- **법적 문구 검토**: 구체적 provider 회사명·국가 표기 (`Google LLC (미국)`) 는 법무팀 검수 필요. 위 5.2 의 string 초안은 **draft** — 실제 배포 전 검수 통과 필수.
- **`pipa_action_log` 관련**: consent granted / denied 시 `data-local-datastore/PipaActionLogStore` 에 append 하라는 spec 이 있음 (`pipa-rights.spec.yml:81`). 본 plan 의 VM 이벤트 핸들러에서 append 호출 **포함 권고** — 단 `PipaActionLogStore` 자체 구현은 별도 plan.
- **전체 규모 추정**: UI 2 파일 (screen 신규 + VM 수정), DataStore 1 파일, Navigation 2 파일, strings 10+ key. 1.5 세션 추정 (라벨 검수 왕복 포함).
