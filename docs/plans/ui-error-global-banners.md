# UI / Error / Global Banners — 조용한 실패 금지 invariant 실현 (9 ERR 표면 umbrella)

**Branch**: `feat/ui/error/global-banners`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 브랜치에 코드 커밋 금지 (문서 이외).
**E2E Stage**: 7 (Runtime 안정성 UX — 모든 화면 공통 에러 표면)
**Severity**: High (MVP 안정성 UX 블로커 — `BecalmError` 분류는 존재하나 UI 표면 0건)
**Type**: Gap (umbrella)

---

## 1. Finding

`BecalmError` sealed 계층은 `Network / Unauthorized / RateLimited / ServerError / Validation / Io / Permission / NotFound / Cancelled / Unknown` 으로 분류돼 있으나 (`android/app/src/main/java/com/becalm/android/core/result/BecalmError.kt:18-69`) **UI 표면(Banner / Snackbar / BottomSheet) 은 0 건**. 즉 워커와 Repo 레이어에서는 에러를 정확히 판정·로깅하지만 사용자는 "왜 동기화가 멈췄는지" 를 **시각적으로 알 수 없다**.
spec invariant (`"모든 실패 상태는 사용자가 복구 경로를 볼 수 있어야 한다 — 조용한 실패 금지"`, `.spec/error-states.spec.yml:103`) 를 **정면으로 위반**.

이 문서는 **umbrella doc** 이다. 9 개 ERR ID (ERR-001..009) 의 UI 표면을 **한 브랜치**에서 다루되, 각 ERR 은 독립 commit 으로 쌓이고 sub-slug 로 구분한다 (브랜치 네이밍 원칙 준수).

Sub-slug 목록 (commit / sub-doc 단위 — 본 umbrella 가 entry point):
- `offline-badge` (ERR-001)
- `cursor-invalid-banner` (ERR-002)
- `oauth-disconnect-banner` (ERR-003)
- `quarantine-badge-sheet` (ERR-004)
- `voice-truncated-variant` (ERR-005, ERR-004 와 통합)
- `ratelimit-snackbar` (ERR-006)
- `server-sustained-banner` (ERR-007)
- `auth-expired-forcelogin` (ERR-008)
- `permission-revoke-banner` (ERR-009)

---

## 2. Spec Contract (무엇이어야 하는가)

### 2.1 `.spec/error-states.spec.yml:12-20` — ERR-001 OfflineBadge
> "네트워크 미연결 상태에서 Today/Persons/Commitments 진입 시 상단 OfflineBadge('오프라인 — 마지막 동기화 HH:mm')가 표시된다. Room 캐시는 정상 렌더링되며 사용자는 로컬 데이터 조작(체크 완료/취소)은 가능하지만 Railway 호출은 대기열에 쌓여 재연결 시 flush"
>
> "상단에 OfflineBadge 표시됨(텍스트: '오프라인 — 마지막 동기화 M/d HH:mm', 색: 경고 오렌지) … 재연결 감지 시 OfflineBadge 사라지고 SYNC-006 즉시 업로드 트리거"

### 2.2 `.spec/error-states.spec.yml:22-30` — ERR-002 cursor 무효화 경고
> "소스 cursor 무효화(ING-013)가 발생한 소스는 SourceStatusStrip 칩이 error 상태(빨간 점 + 느낌표)로 전환되고 칩 아래 얇은 경고 바('Gmail 재동기화가 필요합니다 — 설정에서 확인')가 TodayTimelineScreen 상단에 표시된다. 탭 시 /settings/sources/{source_id}로 딥링크"

### 2.3 `.spec/error-states.spec.yml:32-40` — ERR-003 OAuth disconnect 배너
> "OAuth 토큰 만료·revoke로 소스가 disconnect된 경우 SourceStatusStrip 칩이 error 상태로 바뀌고 상단 배너 '[Gmail] 연결이 끊어졌습니다 — 재연결'이 표시된다. 탭 시 OAuth 재연결 플로우(소스별 OAuth 스크린으로 딥링크)"
>
> "DataStore의 해당 소스 connected=false로 UPDATE … 백그라운드 WorkManager 동기화는 재연결 전까지 skip"

### 2.4 `.spec/error-states.spec.yml:42-50` — ERR-004 quarantine 주황 배지 + BottomSheet
> "개별 raw event가 quarantine(sync_status='quarantined')된 경우 해당 row에 주황 배지 '업로드 실패 — 탭하여 세부정보'가 표시된다. 탭 시 quarantine 사유(ING-003의 reason enum) + [재시도] / [삭제] 버튼을 가진 바텀시트가 열린다"
>
> "QuarantineDetailSheet 열림 — reason(schema_invalid | source_type_unknown | timestamp_parse_error | output_truncated | internal_error) + message + [재시도] 버튼(sync_status='pending'으로 재전환) + [삭제] 버튼 … [재시도]는 retryable=false이던 항목도 강제 재시도 허용"

### 2.5 `.spec/error-states.spec.yml:52-60` — ERR-005 voice output_truncated 안내
> "Vertex AI output_truncated 오류(VOI-002 HTTP 502)로 voice raw event가 quarantine된 경우 일반 ERR-004 flow에 더해 안내 문구 '이 녹음은 파일이 너무 길어 처리하지 못했습니다. 향후 업데이트에서 자동 분할 처리 예정입니다'가 표시된다 … [재시도] 버튼은 숨김(같은 결과 반복 — 유도하지 않음), [삭제] 버튼만 표시. Sentry 이벤트 'voice_output_truncated_shown' (raw_event_id, duration_seconds) 전송"

### 2.6 `.spec/error-states.spec.yml:62-70` — ERR-006 Railway 429 Snackbar
> "Railway 429 rate limit 수신 시 상단 transient Snackbar '요청이 너무 많습니다. 잠시 후 자동 재시도됩니다.'를 표시하고 Retry-After 헤더 값만큼 대기 후 재시도한다 … 429가 5회 연속 발생하면 Sentry 'rate_limit_sustained' 이벤트 전송 + 배너 전환 '서버 혼잡 — 자동 동기화가 일시 중단되었습니다. 잠시 후 재시도'"

### 2.7 `.spec/error-states.spec.yml:72-80` — ERR-007 5xx 5분 지속 배너
> "Railway 5xx 또는 network timeout 장애가 5분 이상 지속되면 TodayTimelineScreen 상단에 '서버에 연결할 수 없습니다 — 로컬 데이터만 표시 중'이 표시된다 … (ERR-001과 다른 점: 네트워크는 살아 있음) … 최근 5분간 Railway 요청 ≥3회 모두 5xx 또는 timeout"

### 2.8 `.spec/error-states.spec.yml:82-90` — ERR-008 401 최종 실패 → LoginScreen
> "Supabase Auth refresh 최종 실패 시 LoginScreen으로 강제 이동 + Toast '세션이 만료되었습니다. 다시 로그인해주세요.' 표시. Room 데이터는 AUTH-005에 따라 보존 … 현재 화면과 무관하게 LoginScreen으로 이동(backStack clear)"

### 2.9 `.spec/error-states.spec.yml:92-100` — ERR-009 권한 revoke 배너
> "사용자가 필수 권한(READ_MEDIA_AUDIO / READ_CONTACTS 등)을 시스템 설정에서 revoke한 경우 관련 기능 진입 시 권한 회복 유도 배너를 표시한다. 배너 CTA 탭 시 Settings.ACTION_APPLICATION_DETAILS_SETTINGS로 직접 연결"

### 2.10 `.spec/error-states.spec.yml:102-106` — invariants
> "모든 실패 상태는 사용자가 **복구 경로를 볼 수 있어야** 한다 — 조용한 실패 금지 (quarantine은 배지로, disconnect은 배너로, offline은 badge로 노출)"
>
> "로컬 Room 데이터는 모든 오류 상태에서 정상 렌더링되며 사용자는 읽기·체크 완료 등 read/action 경로를 계속 사용할 수 있다"
>
> "권한 revoke은 앱 강제 종료/로그아웃이 아닌 feature-level graceful degrade로 처리한다"

---

## 3. Code Reality (지금 무엇인가)

### 3.1 `android/app/src/main/java/com/becalm/android/core/result/BecalmError.kt:18-69` — sealed 분류 완비
```kotlin
public sealed class BecalmError {
    public data class Network(val code: Int, val message: String) : BecalmError()
    public data object Unauthorized : BecalmError()
    public data class RateLimited(val retryAfterSeconds: Long?) : BecalmError()
    public data class ServerError(val code: Int, val body: String?) : BecalmError()
    public data class Validation(val field: String?, val message: String) : BecalmError()
    public data class Io(val message: String) : BecalmError()
    public data class Permission(val permission: String) : BecalmError()
    public data class NotFound(val resource: String) : BecalmError()
    public data object Cancelled : BecalmError()
    public data class Unknown(val throwable: Throwable) : BecalmError()
}
```
에러 "분류" 는 완전하지만 어느 UI 경로도 이 sealed 를 **사용자에게 보여주지 않는다**.

### 3.2 `android/app/src/main/java/com/becalm/android/data/remote/network/NetworkMonitor.kt:21-110` — observer 존재 but UI 미연동
```kotlin
public interface NetworkMonitor {
    public fun isOnline(): Boolean
    public val onlineFlow: Flow<Boolean>   // ← 이 flow 를 구독하는 UI 0 건
}
```
Grep 결과 `ui/` 하위 어느 composable/ViewModel 도 `NetworkMonitor` 를 주입받지 않음 — 즉 offline 상태가 UI 레이어에 **전달조차 되지 않는다**.

### 3.3 `android/app/src/main/java/com/becalm/android/ui/components/SourceStatusStrip.kt:82-101` — 칩 탭 금지 + 배너 미연결
```kotlin
// 주석: "칩 탭 인터랙션 없음" … "Error recovery is routed through the settings screen"
public fun SourceStatusStrip(
    sources: List<SourceStatusChip>,
    modifier: Modifier = Modifier,
) { ... }
```
Error 칩 상태는 렌더 가능하지만 ERR-002 / ERR-003 에서 요구하는 **"칩 아래 얇은 경고 바"** / **"상단 배너"** 는 존재하지 않음. 또한 SourceStatusStrip 의 Error chip 을 탭해도 spec 의 `"/settings/sources/{source_id}"` 딥링크가 동작하지 않음 (칩 자체가 clickable 아님).

### 3.4 `android/app/src/main/java/com/becalm/android/data/remote/interceptor/AuthInterceptor.kt:50-91` — 401 path 존재 but UI forceLogin 훅 없음
```kotlin
// Step 3c: refresh 실패 시 buffered 401 response 그대로 return — UI 상위에 "session expired" 신호를 쏘는 채널 없음
val newToken = runBlocking { authTokenProvider.refresh(token) }
if (newToken == null) {
    return bufferedResponse
}
```
즉 401 최종 실패가 Repository 까지 `BecalmError.Unauthorized` 로 올라가지만 **LoginScreen 으로 forceLogin 하는 전역 effect 가 없다**. 현재 동작: 화면 별 ViewModel 에서 에러만 노출되고 세션은 사실상 "깨진 채로" 유지됨.

### 3.5 `android/app/src/main/java/com/becalm/android/ui/navigation/Routes.kt:39-166` — AuthGuard 부재
```kotlin
public sealed class BecalmRoute(public val path: String) {
    public data object Login : BecalmRoute("login")
    public data object Today : BecalmRoute("today")
    // ... 21 entries
}
```
grep 결과 `AuthGuard`, `sessionExpired`, `forceLogin` 어느 이름도 코드에 존재하지 않음 (위 grep 0 hits). `BecalmNavHost` 는 모든 destination 을 무조건 그리며, 401 감지 시 `navigate(Login)` 을 호출하는 side-effect 계층이 없다.

### 3.6 `android/app/src/main/java/com/becalm/android/data/local/db/entity/RawIngestionEventEntity.kt:45` + `160-167` — sync_status 컬럼은 있으나 UI 미노출
```kotlin
@ColumnInfo(name = "sync_status")    // 값: pending | synced | failed | quarantined
// 주석: "failed" — upload exhausted max retries; event quarantined.
```
스키마에는 `quarantined` 상태가 이미 정의돼 있고 `sync_status` 인덱스도 존재. 그러나 `UnassignedEventsScreen / PersonDetailScreen / TodayTimelineScreen` 어디에도 "주황 배지" 렌더가 없고 `QuarantineDetailSheet` composable 자체가 파일로 존재하지 않음 (spec-declared 이름으로 grep 0 hits — §3.7).

### 3.7 Grep 검증
```bash
# UI 표면 부재 확인
grep -rn "OfflineBadge\|QuarantineDetailSheet\|ErrorBanner\|PermissionStatusBanner\|SessionExpiredToast" \
  android/app/src/main/java/
# → 0 hits (본 plan 기준)

# NetworkMonitor 를 ui 레이어가 쓰는지
grep -rn "NetworkMonitor\|onlineFlow\|isOnline" android/app/src/main/java/com/becalm/android/ui/
# → 0 hits

# AuthGuard / forceLogin 훅 존재 여부
grep -rn "AuthGuard\|sessionExpired\|forceLogin" android/app/src/main/java/
# → 0 hits
```

### 3.8 `android/app/src/main/java/com/becalm/android/BecalmApp.kt:27-49` — Scaffold 레벨 배너 슬롯 부재
```kotlin
Scaffold(
    bottomBar = { if (currentRoute in TAB_ROUTES) BecalmBottomNavigation(...) }
) { padding ->
    BecalmNavHost(...)
}
```
`Scaffold` 는 `topBar` / `snackbarHost` 슬롯이 비어 있고, 전역 배너 host (`GlobalErrorBannerHost`) 가 존재하지 않음. 즉 "모든 화면 공통으로 상단 배너" 를 띄우려면 Scaffold 수준에 host 를 도입해야 함.

---

## 4. Gap (spec vs code)

| ERR ID | Spec 요구 | Code 현실 | Δ |
|--------|-----------|-----------|---|
| ERR-001 | OfflineBadge (오렌지, last_sync HH:mm) | NetworkMonitor 존재, UI 미연동 | Composable + ViewModel 주입 |
| ERR-002 | 경고 바 + `/settings/sources/{id}` 딥링크 | 칩만 있음, 탭 금지 | 배너 composable + nav hook |
| ERR-003 | 상단 배너 + OAuth 딥링크 | `connected=false` 경로 미구현·배너 0 | SourcesRepository flag + 배너 |
| ERR-004 | 주황 배지 + QuarantineDetailSheet | sync_status 스키마만 존재 | 3 screen 배지 + BottomSheet |
| ERR-005 | output_truncated 안내 카드 + 재시도 숨김 + Sentry | 0 hits | ERR-004 에 variant 분기 |
| ERR-006 | 429 Snackbar + Retry-After + 5회 지속 배너 | worker 만 인지, UI 0 | SnackbarHost + counter 상태 |
| ERR-007 | 5xx 5분 지속 배너 | ServerError 분류만 존재 | persistent failure counter |
| ERR-008 | LoginScreen 강제 이동 + Toast | interceptor 는 401 return 만 | AuthGuard effect + navHost |
| ERR-009 | 권한 revoke 배너 + app settings intent | PermissionStatusBanner 없음 | LifecycleObserver + 배너 |

Invariant 위반: "조용한 실패 금지" 9/9 항목 모두 위반. 본 umbrella 가 전체를 메운다.

### 4.1 왜 지금까지 안 만들어졌는지 (root cause 추정)

- 워커 레이어가 에러를 **정확히 분류** 했고 logcat / Sentry 는 잘 기록하고 있으므로 "개발자 입장에서는 보이는" 상태 → UX 표면 부재가 눈에 띄지 않음.
- 5 개 화면 (`Today / Persons / Commitments / Unassigned / Settings`) 각각이 독립적으로 Scaffold 를 래핑 → 공용 배너 host 를 두는 "자연스러운 장소" 가 없어 방치됨.
- `NetworkMonitor` 는 `data/remote/network/` 아래에 있고 ViewModel 레이어는 `ui/` 아래 — 의존 방향상 UI 가 data 모듈을 주입받는 게 맞지만, 기존 ViewModel 들은 Repository 만 주입하고 NetworkMonitor 는 건너뜀 (DI 그래프 gap).
- AuthInterceptor 의 refresh 실패는 "OkHttp Response 가 401 로 return" 이라는 **암묵적 채널** 로만 전달 — UI 레이어까지 명시적 이벤트가 전파되지 않음. 이것이 ERR-008 의 근본 원인.

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술. 본 umbrella 는 **공용 배너 인프라**(`GlobalErrorBannerHost`, `ErrorUiState`) 를 먼저 심고 9 개 ERR 이 그 host 위에 렌더링되도록 설계. 각 ERR 은 독립 commit (sub-slug) 로 쌓인다.

### 5.1 Subscope table — 9 ERR × 변경/신규 파일 × sub-slug

| ERR ID | Sub-slug (commit prefix) | Files to change | Files to add |
|--------|--------------------------|-----------------|--------------|
| — (infra) | `error-banner-host` | `BecalmApp.kt` (Scaffold 에 banner host slot) | `ui/error/GlobalErrorBannerHost.kt`, `ui/error/ErrorUiState.kt`, `ui/error/ErrorEventBus.kt` |
| ERR-001 | `offline-badge` | `TodayViewModel.kt`, `PersonsViewModel.kt`, `CommitmentManagementViewModel.kt` (NetworkMonitor 주입 + lastSyncAt flow) | `ui/error/OfflineBadge.kt`, `ui/error/OfflineStateHolder.kt` |
| ERR-002 | `cursor-invalid-banner` | `TodayTimelineScreen.kt` (SourceStatusStrip 하단 슬롯), `SourcesRepository.kt` (cursor_invalid flag 노출) | `ui/error/CursorInvalidBanner.kt` |
| ERR-003 | `oauth-disconnect-banner` | `SourcesRepository.kt` (connected=false 이벤트 emit), `BecalmNavHost.kt` (OAuth 재연결 딥링크) | `ui/error/SourceDisconnectBanner.kt` |
| ERR-004 | `quarantine-badge-sheet` | `UnassignedEventsScreen.kt`, `PersonDetailScreen.kt`, `TodayTimelineScreen.kt` (카드 배지), `RawIngestionRepository.kt` (retry/delete API) | `ui/error/QuarantineBadge.kt`, `ui/error/QuarantineDetailSheet.kt`, `ui/error/QuarantineDetailViewModel.kt` |
| ERR-005 | `voice-truncated-variant` | `QuarantineDetailSheet.kt` (reason='output_truncated' 분기 — ERR-004 와 **같은 파일**) | (없음 — variant 만) |
| ERR-006 | `ratelimit-snackbar` | `BecalmApp.kt` (SnackbarHost 연결), `RetryInterceptor.kt` / `RawEventUploader.kt` (ErrorEventBus 로 429 push) | `ui/error/RateLimitSnackbar.kt`, `ui/error/RateLimitCounter.kt` (5회 counter) |
| ERR-007 | `server-sustained-banner` | `NetworkModule.kt` (persistent failure counter interceptor 주입) | `ui/error/ServerSustainedBanner.kt`, `data/remote/interceptor/ServerFailureTracker.kt` (5분 rolling window) |
| ERR-008 | `auth-expired-forcelogin` | `BecalmNavHost.kt` (AuthGuard composable wrap), `AuthRepository.kt` (sessionExpired Flow 추가), `AuthInterceptor.kt:82-84` (refresh 실패 시 AuthEventBus.emit) | `ui/navigation/AuthGuard.kt`, `ui/error/SessionExpiredToast.kt` |
| ERR-009 | `permission-revoke-banner` | `TodayTimelineScreen.kt`, `PersonsScreen.kt` (LifecycleObserver + banner slot) | `ui/error/PermissionStatusBanner.kt`, `ui/error/PermissionRevokeDetector.kt` |

### 5.2 공용 인프라 설계 원칙

1. **단일 `GlobalErrorBannerHost`** — Scaffold 수준에서 `topBar` 바로 아래에 위치. priority queue: `OfflineBadge > SourceDisconnect > ServerSustained > CursorInvalid > PermissionRevoke`. 동시에 여러 배너가 활성화되면 **가장 높은 우선순위만 노출** (spec 의 "지저분한 다중 배너" 회피).
2. **`ErrorEventBus` (SharedFlow<ErrorUiEvent>)** — Repo/Worker/Interceptor 가 UI 에 이벤트를 밀어 넣는 단일 채널. Snackbar (ERR-006) 전용 1-shot 이벤트와 Banner 전용 sticky state 를 구분.
3. **`AuthEventBus` (Channel<Unit>)** — `AuthInterceptor` 의 refresh 실패 지점에서만 emit. `AuthGuard` composable 이 수신해 `navController.navigate(Login) { popUpTo(0) { inclusive = true } }` 수행.
4. **우선순위 justification**: offline 은 근본 원인 (서버 요청 자체 불가) → 다른 배너를 숨기는 게 맞음. server sustained > cursor invalid 인 이유는 cursor 경고는 "특정 소스" 이슈이지만 server sustained 는 "전체 pipeline" 이슈.
5. **dismiss 정책**: `OfflineBadge / ServerSustainedBanner` 는 "원인 해소 시 자동 dismiss" (상태 기반). `CursorInvalidBanner / SourceDisconnectBanner / PermissionStatusBanner` 는 "원인 해소 + 사용자 확인" (사용자가 설정 화면 왕복 후 복귀 시 재평가). `RateLimitSnackbar` 는 3 초 auto-dismiss + 5 회 이후 배너로 승격.
6. **`ErrorUiEvent` sealed 계층 제안**:
   - `Banner.Offline(lastSyncAt: Instant?)`
   - `Banner.CursorInvalid(sourceId: String, sourceLabel: String)`
   - `Banner.SourceDisconnect(sourceId: String, sourceLabel: String)`
   - `Banner.ServerSustained`
   - `Banner.PermissionRevoked(permission: String)`
   - `Snackbar.RateLimit(retryAfterSec: Long?)`
   - `Snackbar.RateLimitSustained`
   - `OneShot.SessionExpired` (AuthGuard 전용 — 실제로는 AuthEventBus 로 분리되지만 enum 으로 상호 참조 가능)
7. **Hilt wiring**: 공용 bus 들은 `@Singleton` scope. `@Provides` 로 `@Named("error") MutableSharedFlow` / `@Named("auth") Channel` 구성. ViewModel 은 read-only 로 주입 (`@get:Inject val errorEvents: SharedFlow<ErrorUiEvent>`).

### 5.3 Files to delete (dead code)

없음. 본 plan 은 순수 추가형.

### 5.4 Non-code changes

- `.spec/contracts/ui-map.yml` — 라우트 추가는 없음 (banner/sheet 은 route 가 아님). 단 ERR-003 OAuth 딥링크가 기존 `OnboardingGmail / OnboardingOutlookMail / OnboardingImap / OnboardingGoogleCalendar / OnboardingOutlookCalendar` 재사용하는지 확인 필요 — 재사용이 맞다면 spec drift 없음.
- `strings.xml` / `values-ko/strings.xml` — 9 ERR × 1~2 문구 = 약 15 개 신규 key (spec verbatim 텍스트 그대로 복사).
- Sentry SDK wiring 확인 — ERR-005 / ERR-006 의 custom event 전송 (이미 init 되어 있다고 가정, 아니면 별도 PR 필요 → session handoff note).

---

## 6. Acceptance Criteria

### 6.1 Grep invariants

- [ ] **모든 배너 composable 존재**: `grep -rn "OfflineBadge\|CursorInvalidBanner\|SourceDisconnectBanner\|QuarantineBadge\|QuarantineDetailSheet\|RateLimitSnackbar\|ServerSustainedBanner\|SessionExpiredToast\|PermissionStatusBanner" android/app/src/main/java/com/becalm/android/ui/ | wc -l` ≥ 9
- [ ] **공용 host + bus 존재**: `grep -rn "GlobalErrorBannerHost\|ErrorEventBus\|AuthEventBus" android/app/src/main/java/` ≥ 3
- [ ] **NetworkMonitor UI 연결**: `grep -rn "NetworkMonitor\|onlineFlow" android/app/src/main/java/com/becalm/android/ui/` ≥ 3 (Today/Persons/Commitments VM)
- [ ] **AuthGuard 설치**: `grep -n "AuthGuard" android/app/src/main/java/com/becalm/android/ui/navigation/BecalmNavHost.kt` ≥ 1
- [ ] **조용한 실패 금지**: `grep -rn "\.catch { }\|Result\.failure(.*).takeIf.*null\|try {.*} catch (.*) { }" android/app/src/main/java/com/becalm/android/ui/` 가 0 (ViewModel 이 에러를 swallow 하지 않음 — 모두 ErrorEventBus 로 push 하거나 state 로 노출)
- [ ] **Sentry event key 존재 (ERR-005, ERR-006)**: `grep -rn "voice_output_truncated_shown\|rate_limit_sustained" android/app/src/main/java/` ≥ 2

### 6.2 Unit / Integration tests

- [ ] **ERR-001**: `OfflineBadgeTest — NetworkMonitor.onlineFlow=false emit 시 오렌지 배지 + 'M/d HH:mm' 포맷 렌더` 통과
- [ ] **ERR-002**: `CursorInvalidBannerTest — 탭 시 navController.navigate('settings/sources/gmail')` 통과
- [ ] **ERR-003**: `SourceDisconnectBannerTest — connected=false UPDATE 이벤트 수신 시 배너 노출 + OAuth 재연결 route 호출` 통과
- [ ] **ERR-004**: `QuarantineDetailSheetTest — 5 reason enum 각각에 대한 바텀시트 렌더 + [재시도]/[삭제] 버튼 동작` 통과
- [ ] **ERR-005**: `QuarantineDetailSheetTest — reason='output_truncated' + source_type='voice' → [재시도] 숨김 + 안내 카드 표시 + Sentry emit` 통과
- [ ] **ERR-006**: `RateLimitSnackbarTest — 429 1회 → 3s Snackbar / 5회 연속 → 배너 전환 + Retry-After 초 반영` 통과
- [ ] **ERR-007**: `ServerSustainedBannerTest — 5xx 3회 in 5 min → 배너 노출 / 성공 응답 1회 → 자동 dismiss` 통과
- [ ] **ERR-008**: `AuthGuardTest — AuthEventBus emit 시 현재 route 무관하게 Login 으로 이동 + backStack clear + Toast 문구` 통과
- [ ] **ERR-009**: `PermissionStatusBannerTest — onResume 시 ContextCompat.checkSelfPermission=DENIED → 배너 + ACTION_APPLICATION_DETAILS_SETTINGS intent 발사` 통과

### 6.3 Manual smoke

- [ ] 비행기 모드 ON → Today 진입 시 오렌지 OfflineBadge. 비행기 모드 OFF → 배너 사라지고 SYNC-006 트리거
- [ ] 로그아웃 상태에서 refresh_token 만료 시뮬레이션 (SharedPreferences 토큰 강제 삭제) → 어느 화면에서든 Login 으로 이동 + Toast
- [ ] 시스템 설정에서 READ_CONTACTS revoke → 앱 복귀 시 Persons 배너 + 탭 시 앱 상세 설정
- [ ] `adb shell svc data disable && adb shell svc wifi disable` → Today 배너 오렌지. 재활성화 시 2 초 내 자동 dismiss
- [ ] Gmail disconnect 시뮬레이션 (DataStore `sources.gmail.connected = false` 강제 UPDATE) → Today 상단 "[Gmail] 연결이 끊어졌습니다 — 재연결" 배너. 탭 시 `OnboardingGmail` route 로 이동
- [ ] Quarantine 시뮬레이션: Room SQL `UPDATE raw_ingestion_events SET sync_status='quarantined', quarantine_reason='schema_invalid' WHERE id=...` → Today/Persons/Unassigned 화면에서 주황 배지 노출. 탭 시 Sheet 열림 + [재시도] 후 `sync_status='pending'` 으로 전환 확인
- [ ] Voice truncated 시뮬레이션: 위 SQL 에서 `quarantine_reason='output_truncated' AND source_type='voice'` → Sheet 상단 빨간 카드 + [재시도] 버튼 숨김 확인 + Sentry logcat 에서 `voice_output_truncated_shown` 이벤트 발사 확인
- [ ] 429 시뮬레이션: Charles Proxy 로 모든 `/v1/**` 응답을 `429 Retry-After: 10` 으로 고정 → 1 회 Snackbar 3 초 / 5 회 후 상단 배너 전환 + Sentry `rate_limit_sustained` 발사 확인
- [ ] 5xx 시뮬레이션: Charles 로 `500 Internal Server Error` 고정, 5 분간 3 회 이상 요청 → Today 상단 배너. 정상 응답 1 회 복귀 시 배너 자동 dismiss
- [ ] AuthGuard 압력 테스트: Today / Persons / Commitments / Settings / SourceDetail 각 5 화면에서 AuthEventBus emit → 모든 경우 Login 이동 + backStack clear 확인 (뒤로가기 시 Today 로 복귀하지 않아야 함)

---

## 7. Out of Scope

- **각 에러 카테고리별 서버 측 원인 조사**: 429/5xx/401 의 Railway-side root cause 분석은 backend 레포 담당. 본 PR 은 **클라이언트 UI 표면만** 책임.
- **`BecalmError` 계층 자체 수정**: 새 에러 타입 추가는 금지. 현 10 subtype 로 9 ERR 모두 커버 가능함을 확인했으므로 표면만 덧붙임.
- **WorkManager 정책 변경**: 429/5xx 재시도 policy 는 기존 `UploadBackoff` / `RetryInterceptor` 유지. 본 PR 은 UI 통지 + Sentry emit 만 추가.
- **ING-013 cursor 무효화 worker 로직**: cursor 재계산은 `worker-sync-cursor-invalidation.md` 소관. 본 PR 은 cursor_invalid **flag 를 UI 에 노출**하는 부분만.
- **PIPA / 개인정보 관리 메뉴**: `ui-settings-privacy-management.md` 소관.
- **Sentry SDK 초기화 / DSN 설정**: 이미 init 되어 있다고 가정. 아니면 별도 infra PR.
- **A11y 보이스오버 텍스트 완전성**: contentDescription 만 최소 수준 추가. 전체 A11y 감사는 별도 PR.

---

## 8. Dependencies

- **Blocked by**:
  - `worker-sync-cursor-invalidation.md` (ING-013) — ERR-002 의 cursor_invalid flag 가 Repo 에서 emit 되려면 worker 측 `cursor_invalid` 컬럼 UPDATE 가 먼저 구현되어야 함. 본 umbrella 는 **Repo interface 까지만** 정의하고 실제 emit 은 cursor-invalidation PR merge 이후에 wire-up.
  - AUTH refresh 로직 (AUTH-007) — 이미 구현 완료 (`AuthInterceptor.kt:50-91`). 본 PR 은 refresh **실패** 지점에 AuthEventBus emit 한 줄 추가.
  - Source Management 라우트 (`settings/sources/{id}`) — 이미 구현 완료 (`Routes.kt:160-166`). ERR-002 딥링크 target 으로 재사용.

- **Blocks**:
  - 없음. 본 umbrella 이후 나오는 모든 UI PR 은 `GlobalErrorBannerHost` 를 자유롭게 재사용 가능 (공용 인프라 제공).

- **병렬 가능**:
  - `ui-settings-privacy-management.md` — 파일 겹침 없음 (Settings 섹션 vs Scaffold 수준 배너).
  - 각 ERR sub-slug (ERR-001..009) 간에도 파일 겹침 최소 — ERR-004/005 만 `QuarantineDetailSheet.kt` 공유. 나머지 8 개는 **독립 commit** 으로 같은 브랜치에 push 가능.

merge 순서: `error-banner-host` (인프라) → ERR-008 (AuthGuard — 나머지가 의존) → ERR-001/006/007/009 (독립) → ERR-002/003 (cursor & OAuth) → ERR-004+005 (통합).

---

## 9. Rollback plan

```bash
# 전체 롤백 (merge commit 기준)
git revert -m 1 <merge-sha>
```

각 ERR sub-slug 가 **독립 commit** 이므로 일부만 revert 도 가능:
```bash
git log --grep="feat(ui/error): offline-badge"   # sub-slug 로 검색
git revert <sha>
```

user-facing impact: 배너가 사라질 뿐 기능 자체가 깨지지 않음 (기존 silent failure 상태로 복귀). Room / DataStore / 토큰 영향 없음 — 순수 UI 레벨 revert 안전. 단 ERR-008 (AuthGuard) 만 revert 하면 401 persistent 상태가 재발하므로 해당 commit 은 **반드시 인프라 commit 과 함께 revert**.

---

## Appendix — Session handoff notes

- **Umbrella 이유**: 9 ERR 을 개별 PR 로 쪼개면 PR 개수가 폭증하고 공용 인프라 (`GlobalErrorBannerHost`, `ErrorEventBus`) 가 중복 정의됨. 한 브랜치 안에서 commit 단위로 sub-slug 를 쌓는 것이 "모듈당 1 worktree" 원칙과도 정합.
- **우선순위 queue 설계 근거**: spec invariant "조용한 실패 금지" 는 **모든 에러를 다 보여주라** 가 아니라 **하나라도 보이게 하라**. 동시 노출 시 `OfflineBadge` 를 최우선으로 두는 것이 사용자 mental model 에 맞음 (네트워크 없으면 나머지는 의미 없음).
- **ERR-005 가 독립 composable 이 아닌 이유**: spec §2.5 는 "ERR-004 flow **에 더해**" 라고 명시 — 별도 sheet 을 만들면 spec drift. 같은 `QuarantineDetailSheet` 내부에서 `reason` enum 분기 + `source_type IN ('voice','call_recording')` 조건으로 variant 렌더.
- **ERR-006 의 5회 counter 상태 저장 위치**: Worker 를 벗어나 메모리 기반 `RateLimitCounter` (process-scoped singleton) 가 적절. 앱 재기동 시 초기화되어도 괜찮음 (spec 은 "5회 연속" 을 세션 단위로 해석).
- **ERR-007 persistent failure counter**: `OkHttp Interceptor` 레벨이 가장 적절. `RetryInterceptor` 에 wrapping 하는 새 `ServerFailureTracker` interceptor 추가 — 5분 rolling window 으로 실패 3회 이상 감지. 이 카운터는 **성공 1회에 완전 리셋**.
- **AuthGuard 구현 전략**: `NavHost` 바깥에서 `LaunchedEffect(Unit) { authEventBus.collect { navController.navigate(Login) { popUpTo(0) { inclusive = true } } } }` 패턴. spec §2.8 의 "backStack clear" 를 `popUpTo(0) { inclusive = true }` 로 정확히 매핑.
- **PermissionStatusBanner 의 LifecycleObserver**: 앱이 백그라운드 → 설정 화면 → 복귀 경로에서 권한 재검사 필요. `LifecycleEventObserver(Lifecycle.Event.ON_RESUME)` 가 가장 가볍고 false-positive 없음.
- **SourceStatusStrip 클릭성 문제**: 현재 칩은 `Modifier.clickable` 없음. ERR-002 구현 시 칩 전체가 아니라 **칩 아래 경고 바만** clickable 로 하는 것이 spec 과 일치 ("칩 탭 인터랙션 없음" 유지).
- **`connected=false` UPDATE 경로 확인 필요**: 현재 `SourcesRepository` 에서 disconnect 이벤트가 어디서 emit 되는지 grep 으로 재확인. 없다면 ERR-003 은 `AuthInterceptor` 에서 **특정 source_id 를 식별하는 메타데이터** 가 필요 — 이는 worker-level 이슈이므로 해당 PR 의 Repository 계약 점검 필요.
- **테스트 전략**: 9 ERR 모두 Compose UI test (`createAndroidComposeRule`) + ViewModel unit test 2 레이어. ErrorEventBus 는 TestScope + Turbine 으로 검증.
- **본 PR 크기 예상**: 신규 파일 ~15 개 × 평균 80 lines ≈ 1200 lines. umbrella 이지만 각 파일은 작고 단순. 리뷰 부담은 commit 단위로 나눠 처리.
- **우선순위 queue 가 stateful 해야 하는 이유**: `OfflineBadge` 가 떠 있는 상태에서 `CursorInvalidBanner` 가 뜨면 후자는 suppressed 상태로 대기해야 한다. offline 해소 시 자동으로 cursor 배너가 올라와야 spec §2.10 "복구 경로를 볼 수 있어야 한다" 가 깨지지 않음. 따라서 host 는 단순히 "latest emit" 렌더가 아니라 **priority queue 의 head** 를 렌더.
- **Sentry 이벤트 스키마**:
  - `voice_output_truncated_shown`: `{ raw_event_id: String, duration_seconds: Long, user_id_hash: String }`
  - `rate_limit_sustained`: `{ endpoint_path: String, consecutive_count: Int, last_retry_after_sec: Long? }`
  두 이벤트 모두 PII 금지 — `user_id` 는 raw 가 아닌 salted hash 로. `raw_event_id` 는 내부 ID (서버 assigned) 이므로 PII 아님.
- **Compose Test 주의사항**: `GlobalErrorBannerHost` 는 `SharedFlow` 기반이라 `runTest + advanceUntilIdle` 조합 필요. `TestDispatcher` 를 `Hilt` 테스트 모듈에서 override 하는 패턴이 기존 `SourceStatusStripTest` 에 이미 있음 — 재사용.
- **ERR-003 `connected=false` 감지 주체 재확인 필요**: `AuthInterceptor` 는 railway host 기준이라 소스별 OAuth 실패 (Gmail 401 `invalid_grant`) 는 **워커 내부** 에서만 감지됨. 즉 `SourcesRepository.disconnect(sourceId)` 를 worker 가 호출하는 경로가 존재해야 하며 본 PR 은 그 호출 사이트를 **하나라도 추가**해야 함 (spec §2.3 "Gmail 401 invalid_grant / MS Graph 401 unauthorized_client / IMAP auth failure" 3 경로). 해당 호출 사이트 추가는 `worker-oauth-disconnect-detect.md` 가 있다면 그쪽 소관이지만 없으면 본 PR 에서 같이 처리 — session handoff 에서 결정.
- **`RateLimitCounter` 동시성**: 여러 Worker 가 병렬로 429 를 받을 수 있으므로 `AtomicInteger` + `AtomicLong lastResetTs` 조합. 5 회 counter 의 "연속" 정의는 spec 에 없지만 "성공 응답 1 회 시 리셋" 으로 해석하는 것이 안전 (5xx counter 와 동일 규칙).
- **다국어**: 본 plan 의 모든 문구는 한국어 기준. `values-ko/strings.xml` 이 소스 오브 트루스이고 `values/strings.xml` (English) 은 translator-only placeholder 상태 유지 (MVP 는 KR-only). 단 Sentry 이벤트 key 는 반드시 영어.
- **post-MVP 연결점**: ERR-005 의 Sentry 이벤트는 향후 voice overlap-chunk 설계 입력으로 활용 — 전송된 `duration_seconds` 분포를 보고 chunk 크기 결정. 즉 본 PR 은 **데이터 수집 포인트** 를 심는 의미가 크다.
- **plan-doc 자체의 out-of-scope 원칙 확인**: 각 ERR 의 backend-side root cause (예: Gmail cursor 가 왜 invalid 되는가) 는 해당 모듈 spec (`data-ingestion.spec.yml`, `voice-pipeline.spec.yml`) 이 소유. 본 doc 은 spec header `"이 파일은 '사용자가 보는 것'과 '복구 경로'를 정의한다"` 와 정확히 align — UI 표면에만 집중.
