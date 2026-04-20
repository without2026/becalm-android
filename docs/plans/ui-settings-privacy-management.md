# UI / Settings / Privacy Management — PIPA 권리 실행 진입점 (메뉴 + 라우트 + 랜딩)

**Branch**: `feat/ui/settings/pipa-rights`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 6 (Settings → PIPA 권리 실행 UX)
**Severity**: Critical (MVP 법규 블로커 — PIPA 제35·36·37·39조 실행 경로 부재)
**Type**: Gap (SettingsScreen 에 `개인정보 관리` 메뉴 자체 없음, PrivacyManagementScreen composable 자체 부재)

---

## 1. Finding

`SettingsScreen` 는 Account / Preferences / Data 3 섹션만 가지고 있으며 **PIPA 권리 실행 메뉴(`개인정보 관리`)가 전혀 없다**.
`BecalmRoute` sealed hierarchy 에 `settings/pipa-rights` 또는 그 하위 5 개 sub-route 가 한 건도 선언돼 있지 않다.
spec invariant ("PIPA 권리 실행 진입점은 SettingsScreen > '개인정보 관리' 단일 경로로 통일된다 — 숨겨진 메뉴 금지", `.spec/pipa-rights.spec.yml:86`) 를 **정면으로 위반**.

이 문서는 **umbrella doc** 이다. 실제 5 개 권리(Data Export / Consent Withdraw / Processing Pause / Account Deletion / Activity Log) 구현은 같은 브랜치 위의 별도 sub-PR 로 분리되며 이 doc 은 그 **진입점(nav + 랜딩 + 메뉴 행 5 개)** 만 책임진다.

sub-docs:
- `ui-settings-pipa-data-export.md` (PIPA-002)
- `ui-settings-pipa-consent-withdraw.md` (PIPA-003)
- `ui-settings-pipa-processing-pause.md` (PIPA-004)
- `ui-settings-pipa-account-deletion.md` (PIPA-005)
- `ui-settings-pipa-activity-log.md` (PIPA-007)

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/pipa-rights.spec.yml:16-24` — PIPA-001 landing
> "SettingsScreen > '개인정보 관리' 하위 메뉴가 노출되며 4개 하위 항목: [내 데이터 다운로드] / [동의 철회] / [데이터 처리 일시중단] / [계정 삭제]가 표시된다. 각 항목은 PIPA 해당 조항 번호를 부제로 표기하여 사용자가 권리임을 인식할 수 있게 한다"
>
> "PrivacyManagementScreen 표시됨. 항목: [내 데이터 다운로드 — 열람권(제35조)] [동의 철회 — 동의철회권(제39조)] [데이터 처리 일시중단 — 처리정지권(제37조)] [계정 삭제 — 삭제권(제36조)]. 각 항목 하단에 보조 설명 … [정정]은 commitment-edit.spec.yml 경로로 연결 안내 문구로 처리"

### 2.2 `.spec/pipa-rights.spec.yml:66-73` — PIPA-006 로그아웃 vs 삭제 구분
> "로그아웃은 삭제가 아니다 … SettingsScreen 로그아웃 버튼 옆에 툴팁 '로그아웃은 데이터를 지우지 않습니다. 완전 삭제는 [개인정보 관리 > 계정 삭제]를 이용하세요'"

### 2.3 `.spec/pipa-rights.spec.yml:76-83` — PIPA-007 감사 로그 진입점
> "SettingsScreen > '개인정보 관리' > [활동 내역] 항목으로 노출"

즉 랜딩 화면은 **최소 5 개 행**(Data Export / Consent Withdraw / Processing Pause / Account Deletion / Activity Log).

### 2.4 `.spec/pipa-rights.spec.yml:86` — invariant
> "PIPA 권리 실행 진입점은 SettingsScreen > '개인정보 관리' 단일 경로로 통일된다 — 숨겨진 메뉴 금지"

### 2.5 `.spec/contracts/ui-map.yml` — 경로 정합
현재 Routes.kt 주석은 "21 entries" 라고 기록. 본 PR 이 추가하는 6 개 라우트(landing + 5 sub) 는 ui-map.yml 에 **반드시 동시 등록** 되어야 spec drift 가 발생하지 않음.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `android/app/src/main/java/com/becalm/android/ui/settings/SettingsScreen.kt:187-219` — 3 섹션 고정
```kotlin
SettingsAccountSection(...)
Spacer(...)
SettingsPipaSection(... notificationsEnabled + pipaConsentEnabled + onToggleNotifications + onTogglePipa)
Spacer(...)
SettingsSourcesSection(onSourcesClick = ..., onWipeClick = { showWipeDialog = true })
```
`개인정보 관리` navigation row 없음. "Wipe Data" 버튼은 존재하지만 이것은 PIPA-005 계정 삭제와 다름 — AUTH-005 로컬 wipe 로 AuthRepository.signOut() 을 호출 (참고: `SettingsViewModel.kt:300-309`).

### 3.2 `android/app/src/main/java/com/becalm/android/ui/navigation/Routes.kt:148-166` — Settings 라우트 3 개뿐
```kotlin
public data object Settings : BecalmRoute("settings")
public data object SettingsSources : BecalmRoute("settings/sources")
public data class SourceDetail(...) : BecalmRoute("settings/sources/$sourceId")
```
`settings/pipa-rights*` 계열 라우트 전무.

### 3.3 Grep 검증
```bash
grep -rn "PrivacyManagementScreen\|pipa-rights\|pipa_action_log" \
  android/app/src/main/java/
# → 0 hits (코드 범위)
```

### 3.4 PIPA 토글 위치 혼선
`SettingsPipaSection.kt:29-54` 에 PIPA 제3자 제공 Switch 가 Preferences 섹션 안에 묶여 있음. 본 umbrella 는 이 Switch 를 **그대로 둔다** (ONB-PIPA/VOI-004 단일 동의 토글 — 세부 consent 철회는 sub-doc PIPA-003 에서 다룸). 단 PIPA-001 invariant 를 맞추기 위해 **별도 `개인정보 관리` 섹션 행** 을 새로 추가해야 함.

### 3.5 AUTH-005 로그아웃 경로 — PIPA-006 툴팁 부재
`SettingsAccountSection` (파일 참고만, line 인용 생략) 이 로그아웃 버튼만 렌더. spec 에 명시된 보조 텍스트 "로그아웃은 Room DB 와 권한 설정을 지우지 않습니다 …" 문구 부재.

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| 메뉴 진입점 | SettingsScreen 에 `개인정보 관리` 행 | 없음 | 행 1 추가 |
| 랜딩 화면 | PrivacyManagementScreen (5 행 nav) | 없음 | Composable 1 신규 |
| Nav 라우트 | `settings/pipa-rights` + 5 sub | 0 건 | 6 라우트 신규 |
| PIPA-006 툴팁 | 로그아웃 버튼 하단 보조 텍스트 | 없음 | 1 문구 추가 |
| 메뉴 부제 (PIPA 조항) | "열람권(제35조)" 등 | 없음 | 5 부제 strings.xml |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술.

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/ui/settings/SettingsScreen.kt`**
   - `SettingsSourcesSection` 아래에 `SettingsPipaManagementSection` 호출 1 개 추가.
   - `onPipaManagementClick = { navController.navigate(BecalmRoute.PipaRights.path) }` 주입.

2. **`android/app/src/main/java/com/becalm/android/ui/settings/SettingsAccountSection.kt`** (본 plan 은 존재만 확인 — 세부 읽기 생략)
   - 로그아웃 버튼 아래 회색 보조 Text — PIPA-006 문구: "로그아웃은 Room DB와 권한 설정을 지우지 않습니다. 완전 삭제는 [개인정보 관리 > 계정 삭제]를 이용하세요."

3. **`android/app/src/main/java/com/becalm/android/ui/navigation/Routes.kt`**
   - 6 개 data object 추가 (landing + 5 sub).
   - 네이밍 원칙: kebab-case path, PascalCase class. 예: `PipaRights` → `"settings/pipa-rights"`, `PipaDataExport` → `"settings/pipa-rights/data-export"` … 이와 같이 5 sub 를 **같은 prefix 로 묶어** deep-link 가능.

4. **`android/app/src/main/java/com/becalm/android/ui/navigation/BecalmNavHost.kt`**
   - 6 개 `composable(...)` 블록 신규. 본 umbrella PR 은 landing 만 실체화하고, 5 sub 는 `TODO("see ui-settings-pipa-*.md")` placeholder composable (빈 BecalmScaffold + "Coming soon") — sub-PR merge 시 실체 composable 로 대체.

5. **`android/app/src/main/res/values/strings.xml`** / `values-ko/strings.xml`
   - `settings_pipa_management_section`, `settings_pipa_management_row_label`
   - `pipa_landing_title` = "개인정보 관리"
   - `pipa_landing_row_data_export_title` = "내 데이터 다운로드"
   - `pipa_landing_row_data_export_subtitle` = "열람권(제35조) — 이 앱에 저장된 본인 데이터를 ZIP으로 내보냅니다"
   - 4 개 동일 패턴 + activity log 행 1 개.
   - `settings_sign_out_secondary_text` = "로그아웃은 Room DB와 권한 설정을 지우지 않습니다. 완전 삭제는 [개인정보 관리 > 계정 삭제]를 이용하세요."
   - `pipa_landing_correction_hint` = "정정은 [약속] 탭에서 각 항목 편집으로 수행할 수 있습니다"

### 5.2 Files to add

1. **`android/app/src/main/java/com/becalm/android/ui/settings/pipa/PrivacyManagementScreen.kt`**
   - 역할: PIPA-001 랜딩. 5 `SettingsNavigationRow` (기존 helper 재사용) + 각 행 하단 subtitle Text.
   - BecalmScaffold + back arrow. ViewModel 불필요 (단순 nav). `hiltViewModel()` 없음.
   - 하단에 `pipa_landing_correction_hint` 안내 텍스트 (PIPA-001 의 "[정정]은 commitment-edit 경로로 안내").

2. **`android/app/src/main/java/com/becalm/android/ui/settings/pipa/PipaPlaceholderScreen.kt`** (임시, sub-PR merge 시 제거)
   - 5 개 sub route 가 NavHost 에 등록되어야 nav 링크 오류가 안 남. placeholder composable 을 같은 param 으로 공유.

3. **`android/app/src/main/java/com/becalm/android/ui/settings/SettingsPipaManagementSection.kt`**
   - `SettingsSourcesSection` 와 동일 패턴 (glassPanel + SettingsNavigationRow) — `onClick = onPipaManagementClick`.

### 5.3 Files to delete (dead code)

없음.

### 5.4 Non-code changes

- `.spec/contracts/ui-map.yml` — 6 라우트 entry 추가 (spec 과 코드 동기화).
- `docs/plans/` — 5 sub-doc 는 본 브랜치에서 같은 PR 또는 병렬 doc PR 로 머지. 구현 sub-PR 들은 서로 파일 겹침이 적어 병렬 가능.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant (메뉴 행 노출)**: `grep -rn "개인정보 관리\|pipa_landing_title\|PipaManagementSection" android/app/src/main/` ≥ 3 (섹션 composable + strings.xml 2 개 리소스)
- [ ] **Grep invariant (라우트 선언)**: `grep -n "PipaRights\|PipaDataExport\|PipaConsentWithdraw\|PipaProcessingPause\|PipaAccountDeletion\|PipaActivityLog" android/app/src/main/java/com/becalm/android/ui/navigation/Routes.kt | wc -l` ≥ 6
- [ ] **Grep invariant (숨겨진 메뉴 금지)**: PIPA 권리 경로가 SettingsScreen 이외의 screen 에서도 진입되지 않는지 — `grep -rn "navigate(.*PipaRights\|navigate(.*pipa-rights" android/app/src/main/java/com/becalm/android/ui/ | grep -v "ui/settings/"` 가 0 이어야 함 (PIPA-001 invariant).
- [ ] **UI test (Compose)**: `SettingsScreenTest — '개인정보 관리' 행 클릭 시 PipaRights 라우트로 이동` 통과.
- [ ] **UI test**: `PrivacyManagementScreenTest — 5 행 모두 렌더링 + 각 행의 부제가 제35·36·37·39조 문구를 포함` 통과.
- [ ] **Navigation test**: 5 sub-route 가 NavHost 에 등록되어 있으며 placeholder 라도 네비게이션 시 crash 하지 않음 — `BecalmNavHostTest`.
- [ ] **PIPA-006 문구**: `grep -n "로그아웃은 Room DB\|settings_sign_out_secondary_text" android/app/src/main/res/` ≥ 1.

---

## 7. Out of Scope

- 5 sub 기능 실제 구현 — 각 sub-doc 이 별도 PR:
  - PIPA-002 Data Export → `ui-settings-pipa-data-export.md`
  - PIPA-003 Consent Withdraw → `ui-settings-pipa-consent-withdraw.md`
  - PIPA-004 Processing Pause → `ui-settings-pipa-processing-pause.md`
  - PIPA-005 Account Deletion → `ui-settings-pipa-account-deletion.md`
  - PIPA-007 Activity Log → `ui-settings-pipa-activity-log.md`
- PIPA 제3자 제공 동의 toggle 분리/이동 — 본 PR 은 기존 `SettingsPipaSection` 그대로 둔다. sub PIPA-003 에서 다룸.
- `settings/sources` (SMG-*) 수정 — `ui-settings-source-actions.md` 별도 PR.
- Railway `DELETE /v1/users/me` 엔드포인트 — backend-repo 별도.
- Supabase Auth Admin API 권한 부여 — devops 별도.

---

## 8. Dependencies

- **Blocked by**: 없음. 본 umbrella 는 최초 진입점이므로 다른 PR 에 선행 의존 없음.
- **Blocks**: `feat/ui/settings/pipa-rights` 브랜치의 5 sub-PR 전체. 5 sub 모두 본 umbrella 가 추가한 라우트 + placeholder composable 을 **전제로** 실 composable 로 대체한다.
- **병렬 가능**:
  - `feat/ui/sources` (SMG-*) — 파일 겹침 없음.
  - `feat/ui/error/global-banners` — `SettingsScreen.kt` 에서 겹치지 않음 (Scaffold 수준 배너).

merge 순서: 본 umbrella → 5 sub (임의 순서 병렬) → sub 끝나면 `PipaPlaceholderScreen.kt` 제거 정리 PR.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

랜딩 화면과 메뉴 행만 제거되므로 user-facing impact 는 "권리 실행 메뉴 사라짐" 정도. Room / DataStore / 토큰 영향 없음 — 순수 UI 레벨 revert 안전.
sub-PR 이 이미 merge 된 상태에서 본 umbrella 만 revert 는 금지 (sub composable 이 본 PR 의 Route 객체를 참조). sub → umbrella 역순 revert 또는 전체 복원 2 경로 중 선택.

---

## Appendix — Session handoff notes

- **본 doc 은 umbrella**. 구현자 입장에서 "작은 PR" 로 보이지만 sub-PR 5 개의 **entry point 만** 맡는 것이 의도 — sub 구현과 섞지 말 것.
- placeholder composable 전략: sub-PR 들이 독립적으로 NavHost 를 건드릴 때 머지 충돌이 생기지 않도록, 본 PR 에서 5 개 destination 을 **placeholder 로 먼저 심어둔다**. 각 sub-PR 은 그 placeholder 의 body 만 교체.
- PIPA-001 의 "4 개 하위 항목" 문구와 PIPA-007 의 "[활동 내역] 항목" 요구를 합치면 **총 5 개 행**. spec 내부에서 이미 한 번 drift 가 있는데(4 vs 5) — 본 plan 은 5 행 랜딩 기준.
- `SettingsWipeData` 버튼 (`SettingsSourcesSection`) 과 `PipaAccountDeletion` (PIPA-005) 의 관계: 전자는 AUTH-005 로컬 wipe (로그아웃 + 로컬 데이터 초기화), 후자는 서버 cascade 까지 포함하는 **권리 실행**. sub-PR 에서 명확히 분리. 본 umbrella 에서는 기존 Wipe 버튼 그대로 유지.
- `PipaRights` → 하위 라우트 네이밍 패턴은 `settings/pipa-rights/<kebab>` 로 통일하는 것이 ui-map.yml drift 방지에 유리. sub-PR 들도 같은 prefix 사용.
- 이 PR 은 **Critical** 이지만 **작은 diff**. 법규 리스크만 크고 코드 변경은 meta 수준. 후속 sub-PR 들이 본 PR 의 Route object 를 참조해야 하므로 **가장 먼저 merge**.
