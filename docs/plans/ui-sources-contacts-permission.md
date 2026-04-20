# UI / Sources / contacts-permission — SourcesList의 contacts 의사 행이 실제 READ_CONTACTS 상태를 반영

**Branch**: `fix/ui/sources/contacts-permission`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 2 — Person Enrichment UX (Sources 설정 진입점)
**Severity**: Medium
**Type**: Drift

---

## 1. Finding

`SourcesListViewModel`이 spec ENR-008 + SMG-001 의 "contacts 행은 CONTACTS 권한 부여 여부에 따라 connected / disconnected 상태 표시" 요구를 **하드코딩 `CONNECTED`로 위반**하고 있다. 코드 주석(`SourcesListViewModel.kt:81-86`)이 스스로 drift를 시인:

> "Status is hardcoded to \"CONNECTED\" because the VM layer cannot query Android runtime permissions directly. A proper permission check should be injected via a `PermissionRepository` when available, so this row reflects the real grant state rather than assuming it."

결과: 사용자가 온보딩에서 Contacts를 [나중에]로 skip하거나, 시스템 설정에서 권한을 revoke해도 **Sources 화면은 여전히 "연결됨"으로 표시**. 사용자는 자기 상태를 잘못 인식하고, persons_enrichment가 비어 있는 이유를 설명할 수 없다.

`grep -rn "PermissionRepository" android/app/src/main/` → 매치 0건 — 권한 조회용 경량 Hilt 주입 타입이 아예 없다. 본 PR이 해당 타입을 신설.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/person-enrichment.spec.yml:74-82` — ENR-008 Sources 행 상태 반영

> "id: ENR-008
>  type: ui_interaction
>  description: \"설정 화면 Sources 목록에 '연락처' 의사 소스 행이 표시된다. CONTACTS 권한 부여 여부에 따라 connected / disconnected 상태 표시됨. 탭 시 ContactsPermissionScreen(미부여) 또는 연락처 enrichment 상태 상세(부여)로 이동\"
>  screen: \"SourcesListScreen\"
>  interaction: \"화면 진입\"
>  precondition: \"설정 → Sources 목록 표시됨. READ_CONTACTS 부여됨\"
>  expected: \"'연락처' 행: '연결됨', last_synced_at 표시됨, enriched_count N건 표시됨. 탭 시 연락처 enrichment 상세 화면 표시됨(last_synced_at, enriched_count, [권한 해제] 버튼)\""

### 2.2 `.spec/person-enrichment.spec.yml:86` — invariant: CONTACTS 거부 시 기능 영향 없음

> "CONTACTS 권한 거부 시 앱 기능에 영향 없음 — persons_enrichment 없이 person_ref 원본값으로 fallback"

본 PR은 status 표시만 수정 — fallback/disabling 로직은 기존 Worker 가드(`EnrichmentWorker.kt:90-99`)가 이미 담당.

### 2.3 SMG-001 (SourcesListScreen docstring: `SourcesListScreen.kt:52`)

코드 주석에 `spec: ENR-008, SMG-001` 로 연계됨. SMG-001은 `sources-management.spec.yml`에 정의된 것으로 보이며, contacts 의사 소스 행의 존재 자체를 요구 (현재 이미 행은 존재 — 상태만 drift).

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `android/app/src/main/java/com/becalm/android/ui/sources/SourcesListViewModel.kt:81-94` — 하드코딩 `CONNECTED`

```kotlin
// SMG-001 + ENR-008: contacts is a pseudo-source imported via the
// READ_CONTACTS permission granted during onboarding, not an OAuth flow.
// Status is hardcoded to "CONNECTED" because the VM layer cannot query
// Android runtime permissions directly. A proper permission check should
// be injected via a `PermissionRepository` when available, so this row
// reflects the real grant state rather than assuming it.
val contactsRow = SourceStatusRow(
    sourceType = "contacts",
    status = "CONNECTED",
    lastSyncAt = null,
    lastError = null,
    itemsCount = 0,
)
```

### 3.2 `grep` 검증

```bash
# PermissionRepository 부재
grep -rn "PermissionRepository" android/app/src/main/
# → SourcesListViewModel의 주석에서만 언급, 실제 타입 없음

# READ_CONTACTS 체크 경로는 Worker와 Screen에만 존재
grep -rn "READ_CONTACTS" android/app/src/main/java/
# → EnrichmentWorker.kt, ContactsPermissionScreen.kt — VM 레벨 조회 수단 없음
```

### 3.3 `android/app/src/main/java/com/becalm/android/ui/sources/SourcesListScreen.kt:112-146` — 렌더러는 status-agnostic

`SourceRowItem`은 `statusStringToSyncStatus(row.status)`로 색상/라벨을 매핑 (`SourcesListScreen.kt:112`). 즉 ViewModel이 올바른 status 문자열만 공급하면 UI는 **자동으로** 옳게 렌더링. 본 PR은 VM 한 곳만 수정하면 끝.

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| contacts 행 status | 권한 부여 여부에 따라 `CONNECTED` / `DISCONNECTED` | 항상 `CONNECTED` 하드코딩 | VM에 PermissionRepository 주입 + 동적 status |
| last_synced_at 표시 | enrichment 마지막 동기화 시각 | `lastSyncAt = null` 하드코딩 | `SourceStatusRepository` 조회로 `sourceType="enrichment"` 행의 `lastSyncedAt` 재사용 |
| enriched_count 표시 | N건 | `itemsCount = 0` 하드코딩 | PersonEnrichmentDao count — 본 PR **Out of Scope** (tiny PR 유지) |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술. **Tiny PR** — 1~2 파일.

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/ui/sources/SourcesListViewModel.kt`**
   - `PermissionRepository` 생성자 주입 추가.
   - contactsRow 하드코딩 제거 → `PermissionRepository.isGranted(android.Manifest.permission.READ_CONTACTS)` 호출 결과를 `status` 문자열로 매핑:
     - granted → `"CONNECTED"`
     - denied → `"DISCONNECTED"` (기존 `statusStringToSyncStatus`가 unknown으로 처리하더라도 신규 enum value 추가는 별도 PR)
   - `observeAll()`의 `.map { statuses → ... }` 블록 안에서 `permissionRepository.isGranted(...)`를 매번 호출 — runtime permission 결과는 가볍고 cache 없이 매 emission 호출 안전.
   - `lastSyncAt`는 본 PR에서 `null` 유지 (enrichedCount + lastSyncAt 표시는 별도 "SourceStatusRepository에 enrichment row join" PR).
   - 주석 정리 — "hardcoded because VM cannot query permission" 문구를 실제 구현으로 대체.

### 5.2 Files to add

1. **`android/app/src/main/java/com/becalm/android/data/repository/PermissionRepository.kt`** (신규, 1 파일, ~40줄)
   - `interface PermissionRepository { fun isGranted(permission: String): Boolean }`
   - `class PermissionRepositoryImpl @Inject constructor(@ApplicationContext private val context: Context) : PermissionRepository { override fun isGranted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }`
   - Hilt `@Module @InstallIn(SingletonComponent::class)` `@Binds` 바인딩 — 파일 끝에 co-locate (기존 `WorkSchedulerImpl.kt:312-341` 패턴 참조).
   - PII: permission 이름 문자열만 받고 context 외 상태 없음 — Logger 없이 pure function.

### 5.3 Files to delete (dead code)

- 없음.

### 5.4 Non-code changes

- 없음. manifest/DB/config 변경 없음.

---

## 6. Acceptance Criteria

- [ ] **Unit test**: `SourcesListViewModelTest — contacts row status is CONNECTED when PermissionRepository.isGranted(READ_CONTACTS) returns true` — MockK로 PermissionRepository 스텁.
- [ ] **Unit test**: `SourcesListViewModelTest — contacts row status is DISCONNECTED when PermissionRepository.isGranted(READ_CONTACTS) returns false`.
- [ ] **Grep invariant**: `grep -n "status = \"CONNECTED\"" android/app/src/main/java/com/becalm/android/ui/sources/SourcesListViewModel.kt | wc -l` = 0 (하드코딩 제거 확인).
- [ ] **Grep invariant**: `grep -rn "PermissionRepository" android/app/src/main/java/ | wc -l` ≥ 3 (인터페이스 1 + impl 1 + VM 주입 1).
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공.
- [ ] **Manual**: 신규 설치 → onboarding에서 Contacts [나중에] → Settings → Sources → contacts 행이 `DISCONNECTED` / `Unknown` 상태로 표시. 시스템 설정에서 허용 → 앱 재진입 시 `CONNECTED`.

---

## 7. Out of Scope

- **last_synced_at 표시**: `SourceStatusRepository`에서 `sourceType="enrichment"` 행(`EnrichmentWorker.kt:112,154`이 이미 기록)을 contacts 행과 join하는 로직. 별도 PR `feat/ui/sources/contacts-enrichment-stats`.
- **enriched_count 표시**: `PersonEnrichmentDao.count()` 또는 `observeAll().map { it.size }`를 contacts row에 주입. 별도 PR.
- **Contacts 행 탭 시 navigation 분기** (ContactsPermissionScreen vs enrichment 상세): `SourcesListScreen`은 현재 모든 row가 `SourceDetail` navigation — 본 PR 범위 아님. ENR-008의 "탭 시 ContactsPermissionScreen(미부여) 또는 연락처 enrichment 상세(부여)로 이동" 요구는 별도 PR `feat/ui/sources/contacts-detail-nav`.
- **statusStringToSyncStatus에 `DISCONNECTED` enum 추가**: 현재 `Ok/Stale/Error/Unknown` 4종. `DISCONNECTED`를 새 enum으로 추가하면 `SourceStatusIndicator` 렌더링 확장도 필요 — 본 PR은 기존 `Unknown` 매핑에 의존, 시각적으로는 회색 처리 (acceptable). 별도 UX PR.
- **ENR-008 전체 요구 충족**: 위 out-of-scope 항목들이 전부 해결돼야 spec 100% 충족. 본 PR은 **status 정확성**만을 tiny PR로 우선 확보.
- **`worker-person-enrichment-periodic-observer.md` / `ui-person-cards-detail-render.md`**: 독립 PR. 서로 블로킹 없음.

---

## 8. Dependencies

- **Blocked by**: 없음.
- **Blocks**: `feat/ui/sources/contacts-enrichment-stats` (last_synced_at/enriched_count 표시) — 본 PR이 PermissionRepository를 선행 도입하여 후속 PR이 같은 주입을 재사용.
- **병렬 가능**:
  - `worker-person-enrichment-periodic-observer.md` — 파일 겹침 없음.
  - `ui-person-cards-detail-render.md` — 파일 겹침 없음.
  - 모든 commitment/voice/email 관련 열린 PR — 파일 겹침 없음.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

- 영향 2 파일 (`SourcesListViewModel.kt` + 신규 `PermissionRepository.kt`).
- DB/schema/manifest 변경 없음 → revert 단일 커밋 안전.
- PermissionRepository는 신규 Hilt 바인딩 — 소비자가 본 PR의 VM 1곳뿐이므로 revert 시 바인딩 제거로 인한 다른 compile 실패 없음.
- User 영향: revert 후 contacts 행이 다시 하드코딩 `CONNECTED` — 기존 misleading 상태로 회귀.

---

## Appendix — Session handoff notes

- **Why tiny PR?** ENR-008 full 충족은 4가지 변경(status / last_synced_at / enriched_count / navigation 분기)이 필요. 한 PR에 뭉치면 리뷰 범위가 커지고, PermissionRepository 같은 foundation을 blocker로 만든다. 먼저 PermissionRepository + status만을 tiny PR로 뽑아 foundation을 확립하고, 나머지는 후속 PR에서 이를 재사용.
- **`PermissionRepository` vs `PermissionChecker` 네이밍**: AndroidX에 이미 `PermissionChecker` 클래스가 있어 혼동 방지용으로 `PermissionRepository` 선호. 또한 기존 Repository 패턴(`AuthRepository`, `SourceStatusRepository`)과 일관.
- **테스트 전략**: `PermissionRepositoryImpl` 자체는 ContextCompat delegation만 — Robolectric 또는 AndroidTest 없이 단위 테스트 무의미. 본 PR에서는 `PermissionRepository` 인터페이스만 VM 테스트에서 MockK로 스텁. Impl 자체의 verification은 manual acceptance로 충분.
- **Reactive 권한 변경**: 사용자가 Settings에서 권한을 toggle하면 OS가 앱 프로세스를 kill. 복귀 시 VM 재생성 → `isGranted()` 재평가. 따라서 Flow 기반 관찰 불필요, suspend/immediate 조회로 OK. 단, 같은 세션 내에서 `ContactsPermissionScreen`의 launcher 콜백 후 복귀 시 상태 반영이 지연될 수 있음 — `SourcesListScreen`은 `stateIn(WhileSubscribed(5_000))`이므로 화면 재진입 시 자동 재평가. Acceptable.
- **`statusStringToSyncStatus` 매핑 확인**: `DISCONNECTED` 문자열은 현 매핑에서 `Unknown`으로 떨어지며 회색 표시. 시각적으로는 다른 disconnected source(`NEVER_CONNECTED`)와 동일한 톤. 사용자 혼동 최소 — 별도 UX iteration은 후속 PR.
- **`@ApplicationContext` 주입**: Hilt의 `dagger.hilt.android.qualifiers.ApplicationContext` 사용. 기존 `ContentObserverBootstrap.kt:14` 패턴 참조.
- **Lint 경고**: `ContextCompat.checkSelfPermission`은 API 23+ stable. minSdk 확인(필수 23+) — 기존 `EnrichmentWorker.kt:91` 에서 이미 동일 API 사용 중이므로 lint 문제 없음.
- **향후 재사용성**: `PermissionRepository`는 `RECORD_AUDIO`, `READ_SMS`, `READ_CALL_LOG`, `POST_NOTIFICATIONS` 등 다른 권한 상태 조회에도 재사용 가능. 본 PR은 `READ_CONTACTS` 1개 소비만 커밋하고, 타 consumer는 필요 시 후속 PR에서 주입.
- **PR title 권장**: `fix(ui/sources): reflect READ_CONTACTS grant state on contacts row (ENR-008)`.
- **Commit 메시지**: spec 따라 `fix(ui/sources): contacts-permission — PermissionRepository로 실제 grant 반영`.
