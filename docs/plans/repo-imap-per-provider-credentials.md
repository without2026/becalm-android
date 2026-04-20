# Repo / Imap / per-provider-credentials — 단일-튜플 ImapCredentialStore 가 Naver + Daum 병렬 실행을 구조적으로 막는다

**Branch**: `fix/repo/imap`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 2 (Email ingestion — IMAP providers: Naver + Daum)
**Severity**: High (ING-011 PRIMARY 100%-arrival 경로 invariant 위반 — "6개 소스 어댑터 병렬 실행" 불가)
**Type**: Drift (코드가 spec 의 parallel-execution invariant 와 정합하지 않는 architectural shape 를 채택)

---

## 1. Finding

`ImapCredentialStore.kt:60-66` 은 **단일 IMAP credential tuple** 만 보유한다 (`imap_username`, `imap_app_password`, `imap_host`, `imap_port`). `ImapDaumWorker.kt:43-45` KDoc 은 이 한계를 **명시적으로 인정**한다:

> "the shared [ImapCredentialStore] file holds exactly one IMAP credential tuple at a time (`host` column distinguishes provider) — callers must re-save the correct credential before enqueuing this worker."

이 "caller must re-save" 요구는 `data-ingestion.spec.yml:155` invariant 와 정면 충돌한다:
> "6개 소스 어댑터는 병렬 실행되며 한 어댑터의 실패가 다른 어댑터의 실행을 중단시키지 않는다"

그리고 `data-ingestion.spec.yml:110` ING-011 expected:
> "6개 소스 어댑터 병렬 실행됨(각 어댑터 독립 성공/실패 격리)"

즉 ING-011 의 foreground catch-up 이 Naver + Daum 를 **동시 코루틴**으로 실행하면 두 워커가 같은 파일을 concurrent read/write → race condition → 한쪽이 상대방 자격증명으로 IMAP 로그인 시도 → 401 → 전체 워커 failure. 이는 cross-worker isolation 이 아니라 **cross-worker poisoning**.

본 플랜은 ADAPT-DAUM-002 (audit 기준) 를 해결한다. 해결 범위는 **store 의 per-provider namespace 도입 + 기존 caller wiring + 기존 tuple 의 idempotent 마이그레이션** 까지. 신규 provider 별 자격증명 입력 UI 는 별도 플랜 #14 `ui-onboarding-imap-provider-selector.md` 에서 다룬다.

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/data-ingestion.spec.yml:155` — invariant (parallel execution)
> "6개 소스 어댑터는 병렬 실행되며 한 어댑터의 실패가 다른 어댑터의 실행을 중단시키지 않는다"

### 2.2 `.spec/data-ingestion.spec.yml:110` — ING-011 expected
> "6개 소스 어댑터 병렬 실행됨(각 어댑터 독립 성공/실패 격리). 각 어댑터가 last_cursor 이후 신규 이벤트를 Room에 INSERT함."

### 2.3 `.spec/data-ingestion.spec.yml:80-83` — ING-008 (Naver)
> "[주기적 보조 경로] 네이버 IMAP sync가 신규 이메일을 로컬 파싱하여 Room에 저장한다. … IMAP 앱 비밀번호는 Android Keystore에 보관되며 Railway로 전송되지 않는다"
> precondition: "Keystore에 네이버 IMAP credential 저장됨"

### 2.4 `.spec/data-ingestion.spec.yml:78-85` 전반 ING-008 + ADAPT-DAUM-001 (Daum mirrors) 암시
- Naver 의 "네이버 IMAP credential" 과 Daum 의 "다음 IMAP credential" 은 **별개 슬롯** 이어야 spec 문장이 성립. 현재 구현은 단일 슬롯.

### 2.5 `.spec/data-ingestion.spec.yml:153` — invariant (storage medium)
> "Gmail/Outlook OAuth 토큰 및 IMAP 앱 비밀번호는 Android Keystore에만 저장되며 Railway 서버로 전송되지 않는다"

→ 본 플랜은 Keystore 기반 `EncryptedSharedPreferences` 를 **유지**하며 namespace 만 확장. 저장 매체 변경 없음.

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `android/app/src/main/java/com/becalm/android/data/local/secure/ImapCredentialStore.kt:60-66`
```kotlin
internal const val KEY_USERNAME = "imap_username"
internal const val KEY_APP_PASSWORD = "imap_app_password"
internal const val KEY_HOST = "imap_host"
internal const val KEY_PORT = "imap_port"
```
→ 4 개의 non-namespaced key. 모든 호출자가 같은 슬롯을 덮어쓴다.

### 3.2 `ImapCredentialStore.kt:94-102` — saveCredentials
```kotlin
public suspend fun saveCredentials(c: ImapCredentials): Unit = withContext(ioDispatcher) {
    prefs.edit()
        .putString(KEY_USERNAME, c.username)
        .putString(KEY_APP_PASSWORD, c.appPassword)
        .putString(KEY_HOST, c.host)
        .putInt(KEY_PORT, c.port)
        .apply()
```
→ provider 구분 없음.

### 3.3 `ImapCredentialStore.kt:81-87` — getCredentials
- 단일 튜플 반환. 호출자 (Daum worker) 는 host 값으로 매칭 확인 후 사용 — 단 "매칭 안 맞으면 fail" 말고 다른 방어막 없음.

### 3.4 `ImapDaumWorker.kt:43-45` — KDoc admission
```kotlin
* The credential store is provisioned by the onboarding flow
* (SP-53); the shared [ImapCredentialStore] file holds exactly one IMAP credential
* tuple at a time (`host` column distinguishes provider) — callers must re-save the
* correct credential before enqueuing this worker.
```
→ KDoc 이 이 architectural 결함을 명시.

### 3.5 호출자 위치 검증 grep
```bash
grep -rn "imapCredentialStore\.saveCredentials\|imapCredentialStore\.getCredentials\|ImapCredentialStore()" android/app/src/main/java/
# 기대 결과: ImapNaverWorker.kt, ImapDaumWorker.kt, 그리고 ui/onboarding/ImapSetupScreen.kt (혹은 ViewModel) 에서 참조
```
구현 세션은 이 grep 결과에서 나온 **모든** 참조 사이트에 `sourceType` 파라미터를 전달하도록 편집한다.

### 3.6 `data/remote/dto/SourceTypes.kt:25-28` — 이미 존재
```kotlin
public const val NAVER_IMAP: String = "naver_imap"
public const val DAUM_IMAP: String = "daum_imap"
```
→ 본 플랜은 이 상수를 **그대로 재사용** — 새 enum/상수 도입 금지.

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| Per-provider credential 격리 | Naver + Daum 각자 슬롯 | 단일 튜플 (host 로 구분) | 동일 파일 내 key namespacing |
| 병렬 실행 안전성 | 서로 간섭 없음 | 동일 key 쓰기 레이스 | key 분리로 자연스럽게 격리 |
| Migration | 기존 사용자 유지 | 없음 | `ImapCredentialStoreMigrator` 추가 |
| 호출자 API | `save(sourceType, creds)` | `save(creds)` | API 에 `sourceType` 필수 파라미터 추가 |
| Entry UI | provider 선택 후 저장 | 단일 provider 가정 | **본 PR 아님** — #14 |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술.

### 5.1 접근 — 단일 파일 내 key namespacing

**선택**: 같은 `EncryptedSharedPreferences` 파일 내에서 key 를 `<source_type>_*` 로 prefix.

이유:
- 파일/마스터 키 분리 (별도 파일 + 별도 master key alias) 대비 단순.
- blast-radius: 현재 단일 파일이 손상되면 두 provider 자격증명 모두 손실되나, 이는 기존 구현도 동일한 리스크 (파일 1 개). 본 변경으로 리스크 증가 없음.
- 반대로 "Naver 만 삭제" 같은 operation 은 key prefix scan 으로 자연스럽게 수행 가능.

신규 key 스키마 (prefix = `SourceType.NAVER_IMAP` 또는 `SourceType.DAUM_IMAP` string value):

- `naver_imap_host`
- `naver_imap_port`
- `naver_imap_username`
- `naver_imap_password`
- `daum_imap_host`
- `daum_imap_port`
- `daum_imap_username`
- `daum_imap_password`

기존 4 개 key (`imap_username`, `imap_app_password`, `imap_host`, `imap_port`) 는 **deprecated** 이며 마이그레이션 이후 제거.

### 5.2 API 변경

- `saveCredentials(c: ImapCredentials)` → **`save(sourceType: String, c: ImapCredentials)`**
- `getCredentials()` → **`load(sourceType: String): ImapCredentials?`**
- `clear()` (all) → 두 가지로 분리:
  - **`clear(sourceType: String)`** — 해당 provider 키만 삭제
  - **`clearAll()`** — sign-out 시 모든 provider 자격증명 삭제 (AUTH-005 호환)

`sourceType` 파라미터는 `SourceType.NAVER_IMAP` / `SourceType.DAUM_IMAP` 상수만 허용. 그 외 값은 `require` guard 로 즉시 실패 (fail loudly — spec 의 "Fail loudly" 원칙).

### 5.3 Files to change

- **`data/local/secure/ImapCredentialStore.kt`** (편집)
  - companion object 의 legacy key 상수 제거, 신규 key 상수 도입.
    - `private fun key(sourceType: String, suffix: String): String = "${sourceType}_$suffix"` 같은 helper 도입 권장. suffix 는 `host`, `port`, `username`, `password`.
  - 모든 공개 API 에 `sourceType: String` 파라미터 추가 + `require(sourceType in ALLOWED_IMAP_SOURCES)` guard.
  - `ALLOWED_IMAP_SOURCES = setOf(SourceType.NAVER_IMAP, SourceType.DAUM_IMAP)`.
  - `DEFAULT_HOST` / `DEFAULT_PORT` 는 per-provider 로 분리 (단 파일 내 상수로만, public API 제거):
    - Naver: `imap.naver.com` / 993
    - Daum: `imap.daum.net` / 993
    - default host 결정은 **본 파일이 하지 않고** 호출자 (worker 또는 ViewModel) 가 `sourceType` 에 따라 선택해서 `ImapCredentials.host` 로 전달. 이유: 향후 3 rd provider 추가 시 store 가 provider 지식을 갖지 않도록 단일책임 유지.
  - 기존 `DEFAULT_HOST` / `DEFAULT_PORT` public const 는 호출자 migration 이후 제거 (단, 본 PR 에서 동일하게 제거하지 말고 먼저 `@Deprecated` 표시 후 실제 제거는 follow-up 에서 수행 — 호환성 기간 불필요 시 즉시 제거도 수용 가능. CTO 판단).

- **`worker/ingestion/ImapNaverWorker.kt`** (편집)
  - `imapCredentialStore.getCredentials()` 호출 사이트를 `imapCredentialStore.load(SourceType.NAVER_IMAP)` 로 교체.
  - import 로 `SourceType` 추가.
  - 기타 로직 변경 없음. cursor 처리는 `SyncCursorStore` 담당이므로 본 PR 영향 없음.

- **`worker/ingestion/ImapDaumWorker.kt`** (편집)
  - 동일하게 `imapCredentialStore.load(SourceType.DAUM_IMAP)` 교체.
  - KDoc 43-45 의 "callers must re-save the correct credential before enqueuing this worker" 문단을 **삭제** 및 "credentials are isolated per source_type via namespaced keys — no re-save required across providers" 로 교체.

- **`ui/onboarding/ImapSetupScreen.kt`** (편집 — 최소)
  - 기존 코드가 single-provider 가정이더라도, 본 PR 은 **저장 경로만** `sourceType` 파라미터로 전달하도록 wiring.
  - provider 선택 UI 자체는 **본 PR 범위 아님** (Out-of-Scope, PR#14). 따라서 현재 onboarding 흐름이 Naver 만 저장하는 경우 → `save(SourceType.NAVER_IMAP, creds)` 로 고정 전달. Daum 저장 경로가 아직 UI 에 없다면 본 PR 은 wiring 만 준비하고 UI 는 #14 에서 추가.
  - 구현자 주의: **UI 를 새로 만들지 말 것**. 파일 diff 는 `ViewModel.save(...)` 호출부 1 줄 수준이어야 함.

- **`core/di/ImapModule.kt`** 또는 기존 Hilt 모듈 — 필요 시 편집 (`ImapCredentialStore` 는 이미 `@Singleton` + `@Inject` 이므로 별도 Hilt 작업 대부분 불필요).

### 5.4 Files to add

- **`data/local/secure/ImapCredentialStoreMigrator.kt`** (신규)
  - `@Singleton` 클래스. `ImapCredentialStore` 와 별도 클래스 (store 를 단순하게 유지).
  - 의존성: `@ApplicationContext Context`, `@IoDispatcher CoroutineDispatcher`, `Logger`, 기존 `EncryptedSharedPreferences` 파일 참조 (동일 `FILE_NAME`, `MASTER_KEY_ALIAS`), DataStore `UserPrefs` (migration 완료 플래그용).
  - 공개 API: `suspend fun migrateIfNeeded(): Unit`
  - 동작:
    1. DataStore `imap_credential_store_migrated_v1: Boolean` 플래그 체크 — true 면 즉시 return.
    2. prefs 에서 legacy key 4 개 (`imap_username`, `imap_app_password`, `imap_host`, `imap_port`) 읽기.
    3. 모두 존재 + host 값이 `imap.naver.com` 으로 시작 → `naver_imap_*` namespace 로 복사.
       host 값이 `imap.daum.net` 으로 시작 → `daum_imap_*` namespace 로 복사.
       그 외 host → 방어적으로 Naver 로 간주 + Sentry 에 `imap_migration_unknown_host` 이벤트. (BeCalm 이 현재 Naver + Daum 만 지원하므로 third option 없음.)
    4. legacy key 4 개 `edit().remove(...)` 로 제거.
    5. DataStore 에 `imap_credential_store_migrated_v1 = true` 저장.
  - **Idempotent**: 재실행 시 플래그로 skip. 플래그만 있고 legacy key 도 없으면 no-op.
  - 호출 사이트: `BecalmApplication.onCreate()` 의 초기화 블록 또는 기존 migration orchestration (`Migrations.kt` 또는 app-start hook). DataStore 접근이 IO 이므로 `applicationScope.launch(Dispatchers.IO)` 로 비동기 실행.
  - **호출 순서 주의**: Migrator 가 완료되기 전에 `ImapCredentialStore.load(...)` 가 호출되면 legacy 데이터가 보이지 않아 사용자가 재입력 요구됨. 따라서:
    - Migrator 는 app-start 에서 실행되며, 워커 enqueue 는 ING-011 foreground catch-up 시점.
    - foreground catch-up 은 `applicationScope.launch { migrator.migrateIfNeeded(); workScheduler.enqueue... }` 형태로 **순차화**. 구현자는 `migrateIfNeeded` 가 suspend 이므로 workflow 에서 `await` 필수.

### 5.5 Files to delete (dead code)

- 없음. 기존 dead code 제거는 본 PR 범위 외 (CLAUDE.md "Surgical Changes" 원칙).

### 5.6 Non-code changes

- **DataStore key 추가**: `imap_credential_store_migrated_v1: Boolean` (기본 false). DataStore proto/Preferences 변경은 wire-compatible — migration 필요 없음.
- **Sentry event**: `imap_migration_unknown_host` (payload: `{host_hash}` — PII 방지 위해 호스트명 해시만).
- **Manifest**: 변경 없음.
- **Permission**: 변경 없음.

---

## 6. Acceptance Criteria

- [ ] **Grep invariant**: `grep -n "naver_imap_host\|daum_imap_host" android/app/src/main/java/com/becalm/android/data/local/secure/ImapCredentialStore.kt | wc -l` 가 2 이상.
- [ ] **Grep invariant forbidden**: `grep -n "KEY_USERNAME\|KEY_APP_PASSWORD\|KEY_HOST\|KEY_PORT" android/app/src/main/java/com/becalm/android/data/local/secure/ImapCredentialStore.kt | wc -l` 가 0 이다 (legacy 상수 전부 제거됨).
- [ ] **Grep invariant**: `grep -n "SourceType\.NAVER_IMAP\|SourceType\.DAUM_IMAP" android/app/src/main/java/com/becalm/android/worker/ingestion/ImapNaverWorker.kt android/app/src/main/java/com/becalm/android/worker/ingestion/ImapDaumWorker.kt | wc -l` 가 2 이상.
- [ ] **Grep invariant**: `grep -n "class ImapCredentialStoreMigrator" android/app/src/main/java/ | wc -l` 가 1.
- [ ] **Grep invariant forbidden**: `grep -rn "imapCredentialStore\.getCredentials()\|imapCredentialStore\.saveCredentials(" android/app/src/main/java/ | wc -l` 가 0 이다 (모든 호출자가 새 API 로 교체됨).
- [ ] **API compile gate**: `ImapCredentialStore.save` / `load` / `clear` 3 개 메서드가 모두 `sourceType: String` 첫 파라미터를 받는다 (리플렉션 또는 Kotlin metadata 검증 — 테스트에서 assert).
- [ ] **Unit test**: `ImapCredentialStoreTest — save(NAVER, creds) does not overwrite DAUM slot` 통과.
- [ ] **Unit test**: `ImapCredentialStoreTest — save(DAUM, creds) does not overwrite NAVER slot` 통과.
- [ ] **Unit test**: `ImapCredentialStoreTest — clear(NAVER) leaves DAUM intact` 통과.
- [ ] **Unit test**: `ImapCredentialStoreTest — clearAll removes both providers` 통과.
- [ ] **Unit test**: `ImapCredentialStoreTest — save with invalid sourceType throws IllegalArgumentException` 통과.
- [ ] **Unit test**: `ImapCredentialStoreMigratorTest — legacy tuple with imap.naver.com host migrates to naver_imap_* keys` 통과.
- [ ] **Unit test**: `ImapCredentialStoreMigratorTest — legacy tuple with imap.daum.net host migrates to daum_imap_* keys` 통과.
- [ ] **Unit test**: `ImapCredentialStoreMigratorTest — running twice is idempotent (no duplicate writes, flag stays true)` 통과.
- [ ] **Unit test**: `ImapCredentialStoreMigratorTest — unknown host falls back to naver with Sentry event` 통과.
- [ ] **Integration test (Robolectric or instrumented)**: `ImapNaverWorkerTest — concurrent run with ImapDaumWorker does not poison credentials` 통과 — 두 워커를 동시에 실행해도 각자 올바른 credential 로 IMAP 접속.
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공.

---

## 7. Out of Scope

- **Per-provider credential 입력 UI** (사용자가 Naver 와 Daum 를 구분하여 입력) — **플랜 #14 `ui-onboarding-imap-provider-selector.md`** 에서 처리. 본 PR 은 **저장 계층만** 수정하고 기존 caller 를 wire-up.
- **ImapClient 병렬 접속 안정성** (connection pool, timeout 튜닝 등) — 별도 resilience 플랜.
- **Naver 보낸메일함 / Daum 보낸편지함 folder enumeration** (`ADAPT-NAVER-001`, `ADAPT-DAUM-001`) — 별도 플랜.
- **UIDVALIDITY rebuild 30-day window** (`ADAPT-NAVER-003`) — 별도 플랜.
- **OAuth provider 구현** (`ADAPT-CRED-001`) — `feat/repo/auth` 브랜치에서 처리.
- **IMAP 추가 provider 지원** (Outlook IMAP, Yahoo 등) — MVP 범위 외.
- **EmailBody / attachments / headers 확장** — 별도 플랜들 (`ADAPT-EMAIL-*`).
- **Migrator 가 Sentry 로 host 원본을 보내는 경로** — PIPA 위반 가능 → hash 만 보낸다 (plan 내 명시).
- **legacy key 를 Flag-off 상황에서 복구** — one-way migration, 복구 경로 없음 (rollback plan 참조).

---

## 8. Dependencies

- **Blocked by**: 없음. `feat/repo/auth` (OAuth 플랜) 와 **완전 독립** — 파일 겹침 없음 (`data/local/secure/ImapCredentialStore.kt` vs `data/local/secure/OAuthCredentialStore.kt`).
- **Blocks**:
  - `ui-onboarding-imap-provider-selector.md` (PR#14) — provider 별 입력 UI 가 본 PR 의 `save(sourceType, ...)` API 에 의존.
  - `ADAPT-NAVER-*`, `ADAPT-DAUM-*` 후속 플랜 — 병렬 실행 전제가 깨지지 않아야 downstream 작업이 의미있음.

### 8.1 병렬 실행 가능성 (CLAUDE.md worktree 전략)

- 다른 모듈 브랜치 (`feat/repo/auth`, `feat/ui/onboarding`, `feat/worker/email-body`) 와 파일 겹침 **0**.
- `git worktree add ../becalm-repo-imap fix/repo/imap` 로 독립 worktree 에서 구현 가능.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

Revert 시 동작:
- `ImapCredentialStore` 는 원래 단일-튜플 API 로 복귀.
- 워커들도 `getCredentials()` / `saveCredentials(...)` 를 다시 호출하도록 원복됨.
- **그러나**: migrator 가 이미 실행됐다면 legacy key 4 개는 **삭제된 상태**. 즉 revert 후 워커는 자격증명이 없다고 판단 → 사용자에게 재입력 요구.
- **복구 전략**:
  - 만일 실제 운영 중 revert 가 필요하면, revert 에 **별도 "reverse migrator"** 를 추가해야 함. 본 플랜에서는 reverse migrator 를 **준비하지 않음** (MVP 단계이므로 rollback 확률 낮고 사용자 재입력 요구가 허용됨 — `ONB-004` / ImapSetup 재실행으로 빠른 복구 가능).
  - 운영 환경 (post-production) 에서 revert 가 발생하면, data loss 는 없고 UX 손실만 있음. 사용자는 Settings → IMAP 재입력 경로로 1 회 재설정.
- **데이터 손실 여부**: 자격증명은 로컬만 → 서버 데이터 손실 없음. Room 에 저장된 이메일도 영향 없음. 단 credential 이 재입력될 때까지 Naver/Daum IMAP sync 가 skip.

---

## Appendix — Session handoff notes

- **왜 파일 분리 (ImapCredentialStoreNaver + ImapCredentialStoreDaum) 가 아니라 key namespace 인가**:
  - 파일 분리 시 master key alias 도 2 개 → Keystore entry 2 개 → 관리 오버헤드.
  - 단일 파일 내 namespace 는 기존 blast-radius 동일 + 코드 단순.
  - `EncryptedSharedPreferences` 는 key-level encryption (AES-SIV) 이므로 key 이름 prefix 로 cross-read 가 불가능 — namespace 격리 자체가 암호학적으로도 성립.
- **왜 `SourceType` 상수 재사용인가 (신규 enum 도입 금지)**:
  - 이미 `data/remote/dto/SourceTypes.kt` 에 wire-format string 이 정의됨 (`naver_imap`, `daum_imap`).
  - 별도 enum 을 추가하면 dual source-of-truth → CLAUDE.md "Explicit over implicit" 위반.
- **Migrator 실패 시 기본 전략**:
  - legacy key 가 읽히지 않는 경우 (Keystore 손상) → `ImapCredentialStore` 의 기존 "Keystore damage recovery" (file wipe + rebuild) 경로와 동일하게 동작. migrator 는 `migrateIfNeeded` 호출에서 try-catch 로 감싸되 **예외를 삼키지 말고** Sentry 에 `imap_migration_failed` 로 기록 후 flag 를 설정하지 않음 → 다음 app launch 에서 재시도.
  - "Fail loudly" 원칙: migrator 가 이유 없이 플래그만 true 로 세팅하는 경로 금지.
- **동시성 고려**:
  - `EncryptedSharedPreferences` 는 thread-safe. 두 워커가 서로 다른 prefix 의 key 를 읽어도 충돌 없음.
  - 단 `apply()` 는 async commit → save 직후 다른 코루틴이 read 하면 stale 가능. 본 use-case 는 save 와 read 가 다른 시점 (onboarding vs periodic worker) 이므로 영향 없음.
- **함정 — `DEFAULT_HOST` / `DEFAULT_PORT` public const**:
  - 기존 `ImapCredentials` data class 의 default parameter 가 `ImapCredentialStore.DEFAULT_HOST` 를 참조 중 → 제거 시 data class 호환성 깨짐. 구현자는 둘 중 하나:
    1. data class 의 default 를 제거하고 호출자가 sourceType 에 따라 host 명시. (더 안전, 권장.)
    2. `DEFAULT_HOST_NAVER` / `DEFAULT_HOST_DAUM` 로 분리하고 data class default 제거.
  - 어느 쪽이든 public API 깨짐 → 호출자 grep 필수.
- **테스트 팁**:
  - `EncryptedSharedPreferences` 는 Robolectric 에서 동작 (AndroidX Security 1.1.x 기준). instrumented test 불필요.
  - Migrator 의 DataStore 접근은 `TestCoroutineScheduler` + in-memory DataStore factory 로 테스트.
  - "두 워커 concurrent run" 통합 테스트는 Robolectric 에서 `launch` 2 개 후 `runCurrent` 로 시뮬레이션. 실제 IMAP 접속은 mock `ImapClient` 로 stub.
- **UI PR (#14) 와의 인터페이스 계약**:
  - 본 PR 이 정의하는 API signature 가 확정되면 #14 의 ViewModel 은 `imapCredentialStore.save(SourceType.NAVER_IMAP, ImapCredentials(...))` / `save(SourceType.DAUM_IMAP, ...)` 만 호출.
  - 본 PR 에서 시그니처 변경이 있으면 #14 에 영향 — 가능한 한 본 PR merge 후 #14 시작.
- **"audit 의 fix 제안 '네임스페이스 by host' vs 본 플랜 'by source_type'"**:
  - audit 원문: "namespace keys by host OR split into ImapCredentialStoreNaver/ImapCredentialStoreDaum".
  - host 로 namespace → 사용자가 host 를 오타 입력 (e.g. `imap.naver.com` vs `imaps.naver.com`) 하면 key 가 갈라지는 취약점.
  - source_type 은 enum-like 고정값이라 type-safe.
  - 본 플랜은 source_type 선택 — audit 제안보다 엄격한 scheme.
