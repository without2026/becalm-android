# UI / Commitment / manual-sheet — CommitmentCreateSheet (수동 약속 추가) 전체 부재

**Branch**: `feat/ui/commitment`
**Status**: PLAN ONLY — 구현은 다른 세션에서 진행. 이 plan doc 이외의 코드 커밋 금지 (umbrella PR #22 에 누적).
**E2E Stage**: 5 — CommitmentManagement 화면 / 수동 추가 시트
**Severity**: High (`manual-commitment.spec.yml` MAN-001..006 전체가 UI·Repository·Worker 3개 레이어에서 미구현. 사용자는 LLM 추출 외 경로 — 오프라인 대화·메모·LLM 이 놓친 약속 보완 — 으로 commitment 를 기록할 수 없다. 또한 `commitment-edit.spec.yml` EDIT-007 supersede 의 입력 화면도 본 sheet 를 재사용하므로 `ui-commitment-edit-sheet.md` 의 supersede mode 도 본 plan 선행 없이는 구현 불가.)
**Type**: Gap (ExtendedFAB + Composable Sheet + VM + Repository INSERT + Worker skip-guard 경로 전체 파일 부재)

---

## 1. Finding

`CommitmentManagementScreen.kt:70-122` 의 `BecalmScaffold` 호출에는 `floatingActionButton` slot 이 없고, 파일 전체에 `ExtendedFloatingActionButton` 또는 `FloatingActionButton` 호출이 단 1건도 없다 — 화면 우하단에 `[+ 약속 추가]` 진입점 자체가 부재 (MAN-001 위반). 또한 `android/app/src/main/java/com/becalm/android/ui/commitments/` 하위에 `CommitmentCreateSheet.kt`, `CommitmentCreateViewModel.kt` 파일 2종이 존재하지 않아 quote multiline TextField · direction RadioButton · validation (MAN-002, MAN-005) 모두 진입 불가. `CommitmentRepository.kt:22-197` 의 public 인터페이스에는 사용자 직접 입력으로 commitment 를 INSERT 하는 `saveManualCommitment(...)` 메서드가 부재 → MAN-003 의 invariant 5종 (source_type='manual' / confidence=1.0 / source_ref=null / source_event_title=null / source_event_occurred_at=now() / raw_ingestion_events INSERT 금지) 을 강제할 코드 경로 자체가 없다. `CommitmentCard` 에는 `sourceType` 파라미터가 없어 MAN-004 의 '수동 추가' 📝 배지도 렌더 불가. LLM 재추출 파이프라인 (MAN-006) 의 `source_type='manual'` skip-guard 도 부재.

본 plan 은 **MAN-001..006 6개 behavior 전체** 를 하나의 umbrella logic unit 으로 묶는다. 구현 세션이 FAB 추가 → Sheet/VM 신규 → Repository INSERT 경로 → CommitmentCard 배지 → Worker skip guard 를 linear commit stack 으로 쌓을 수 있도록 의존성과 acceptance 를 정의.

---

## 2. Spec Contract (무엇이어야 하는가)

- **`.spec/manual-commitment.spec.yml:18` MAN-001** — FAB 진입 + sheet 2-mode:
  > "CommitmentManagementScreen 우하단에 ExtendedFloatingActionButton [+ 약속 추가]가 표시되며 탭 시 CommitmentCreateSheet(빈 폼)가 열린다. sheet 상단에 '직접 추가한 약속은 LLM 추출 약속과 동일하게 관리됩니다' 안내 라벨. 동일 sheet는 EDIT-007 supersede 경로에서는 quote + source_event_* pre-filled 상태로 재사용된다 — 즉, '신규 manual' mode와 'supersede' mode 두 가지"

- **`.spec/manual-commitment.spec.yml:22` MAN-001 expected**:
  > "CommitmentCreateSheet 열림. 상단 제목: '약속 직접 추가'. 필드: title(필수), direction(필수), quote(필수 — '약속 맥락' 라벨로 표시), person_ref(선택), due_at(선택), due_hint(선택), due_is_approximate(선택, default false). 하단 [취소]/[저장] 버튼."

- **`.spec/manual-commitment.spec.yml:28` MAN-002** — quote multiline TextField (≤500자):
  > "quote 필드는 사용자가 약속의 맥락·증거를 본인이 기술하는 영역이다 (예: '김대리와 3시 미팅 중 월요일 보고서 전달 약속'). LLM 추출 quote와 동일한 법적 증거 필드로 저장되지만 사용자 자기 기록이므로 'verbatim' 제약은 없다. 최대 500자. 빈 값 저장 불가 — 최소 1자 필요 (증거 없는 기록 방지)"

- **`.spec/manual-commitment.spec.yml:32` MAN-002 expected**:
  > "quote TextField multi-line 3줄, max 500자, placeholder: '이 약속의 맥락을 기록하세요 (예: 3/15 팀 미팅 중 월요일 보고서 전달 구두 약속)'. 공백만 입력: 헬퍼 텍스트 '약속의 맥락을 최소 1자 이상 입력해주세요'. 500자 초과: '최대 500자까지 입력할 수 있습니다'. supersede 모드에서는 quote가 원본 commitment에서 복사되어 읽기 전용 카드로 표시 + 편집 불가"

- **`.spec/manual-commitment.spec.yml:38` MAN-003** — saveManualCommitment invariants:
  > "[저장] 탭 시 Room commitments INSERT + Railway POST /v1/commitments를 실행한다. source_type='manual', source_ref=null, source_event_title=null, source_event_occurred_at=now(), confidence=1.0(사용자 직접 입력으로 확신), sync_status='pending', last_edited_by=user_id, last_edited_at=now(). raw_ingestion_events row는 생성하지 않음 (manual은 원본 이벤트가 없음)"

- **`.spec/manual-commitment.spec.yml:48` MAN-004** — '수동 추가' 배지 + Detail 출처:
  > "수동 약속은 UI에서 '수동 추가' 배지로 구분된다 — CommitmentCard 상단 우측에 📝 아이콘과 '수동 추가' 라벨. CommitmentDetailSheet의 '출처' 섹션은 '사용자 직접 추가 ({created_at}, KST)'로 표시되며 source_event_title·source_event_occurred_at은 숨김. PersonDetailScreen 목록에서도 동일 배지 표시"

- **`.spec/manual-commitment.spec.yml:58` MAN-005** — Validation (title 1-200 / direction 필수 / quote 1-500 / person_ref lowercase 정규화 / due_at optional):
  > "title 공백 제거 후 1~200자. direction ∈ ('give','take') 1개 선택 필수. quote 공백 제거 후 최소 1자 최대 500자. person_ref는 공백 제거 후 lowercase 정규화(비워도 허용). due_at 입력 시 유효 ISO8601 KST(과거도 허용 — 지난 약속 추후 기록). 규칙 위반 시 해당 필드 하단 빨간 헬퍼 텍스트 + [저장] 비활성"

- **`.spec/manual-commitment.spec.yml:62` MAN-005 default direction**:
  > "direction 미선택: RadioButton 특성상 하나는 선택되어야 하므로 초기 default는 'give' (본인 제안 약속이 일반적)."

- **`.spec/manual-commitment.spec.yml:68` MAN-006** — Extraction worker skip + retention 면제:
  > "수동 약속은 LLM 재추출 대상이 아니다 — 어떤 WorkManager worker(CommitmentExtractionWorker 등)도 source_type='manual' commitment를 재처리하지 않는다. 또한 raw_ingestion_events 리텐션 30일 정책(data-ingestion.spec.yml) 대상도 아니다 (연관 raw event 자체가 없음). 사용자가 명시적으로 삭제(EDIT-006)하지 않는 한 영속 보존"

- **`.spec/manual-commitment.spec.yml:77` invariant**:
  > "수동 약속은 source_type='manual', confidence=1.0, source_ref=null, source_event_title=null로 저장된다"

- **`.spec/manual-commitment.spec.yml:78` invariant**:
  > "source_event_occurred_at은 created_at과 같은 값(now() at save time)으로 기록 — LLM 추출 약속의 '원본 이벤트 발생 시각' 의미와 다름"

- **`.spec/manual-commitment.spec.yml:79` invariant**:
  > "quote는 사용자가 직접 입력하며 최소 1자 최대 500자 — 빈 quote 저장 불가 (증거 없는 기록 방지)"

- **`.spec/manual-commitment.spec.yml:80` invariant**:
  > "수동 약속은 raw_ingestion_events row를 생성하지 않는다 — commitments 테이블에만 존재"

- **`.spec/manual-commitment.spec.yml:81` invariant**:
  > "LLM 추출 파이프라인(CommitmentExtractionWorker·VoiceExtractionWorker 등)은 source_type='manual' commitment를 재처리하지 않는다"

---

## 3. Code Reality (지금 무엇인가)

### 3.1 ExtendedFAB 부재 — MAN-001 위반

- **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt:70-73`**:
  ```kotlin
  BecalmScaffold(
      title = stringResource(R.string.commitments_title),
      snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { padding ->
  ```
  → `floatingActionButton` slot 인자 없음. 전체 파일에 FAB 호출 0건.

```bash
grep -rn "ExtendedFloatingActionButton\|FloatingActionButton" \
  android/app/src/main/java/com/becalm/android/ui/commitments/
# → empty (0 matches)
```

### 3.2 Sheet / VM 파일 부재 — MAN-001, MAN-002, MAN-005 위반

`android/app/src/main/java/com/becalm/android/ui/commitments/` 디렉토리 내용:
```
CommitmentManagementScreen.kt
CommitmentManagementViewModel.kt
```
→ `CommitmentCreateSheet.kt`, `CommitmentCreateViewModel.kt` 부재. quote multiline TextField · direction RadioButton · validation 진입점 없음.

### 3.3 Repository INSERT 경로 부재 — MAN-003 위반

- **`android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepository.kt:22-197`** public 인터페이스 — `observeAllForUser` · `observePendingForToday` · `observeAllForPerson` · `refreshSince` · `transitionState` · `updateActionState` · `findPendingSync` · `markSynced` · `markFailed` · `uploadBatch` · `deleteAllForUser` 만 존재.

```bash
grep -n "saveManualCommitment\|createManual\|insertManual" \
  android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepository.kt
# → empty (0 matches)
```
→ MAN-003 invariant 5종 (source_type='manual' / confidence=1.0 / source_ref=null / source_event_title=null / source_event_occurred_at=now()) 을 강제할 코드 경로 없음. `raw_ingestion_events INSERT 금지` invariant 도 코드 차원에서 enforce 할 대상 자체가 없음.

### 3.4 SourceType.MANUAL 상수 부재 (PR #21 dependency)

- **`android/app/src/main/java/com/becalm/android/data/remote/dto/SourceTypes.kt:14-46`** — 7종 상수 (`VOICE` / `GMAIL` / `OUTLOOK_MAIL` / `NAVER_IMAP` / `DAUM_IMAP` / `GOOGLE_CALENDAR` / `OUTLOOK_CALENDAR`) + `ALL` set. `MANUAL` 미선언.
- **`android/app/src/main/java/com/becalm/android/data/local/db/entity/CommitmentEntity.kt:53-54`** (KDoc) — `sourceType` Valid values 7종 주석에 `manual` 없음.

```bash
grep -rn "SourceType.MANUAL\|\"manual\"" \
  android/app/src/main/java/com/becalm/android/data/
# → empty (0 matches)
```
→ `feat/repo/commitment/source-type-manual` (PR #21) 선행 필수.

### 3.5 CommitmentEntity — last_edited_by / last_edited_at 컬럼 부재 (PR #20 dependency)

- **`android/app/src/main/java/com/becalm/android/data/local/db/entity/CommitmentEntity.kt:80-139`** — 18개 컬럼 정의. `last_edited_by` · `last_edited_at` · `deleted_at` · `quote_disputed` · `quote_disputed_at` · `supersedes_commitment_id` 6종 미존재.

```bash
grep -n "last_edited_by\|last_edited_at" \
  android/app/src/main/java/com/becalm/android/data/local/db/entity/CommitmentEntity.kt
# → empty (0 matches)
```
→ MAN-003 의 `last_edited_by=user_id` + `last_edited_at=now()` set 이 현재 스키마에서 컬럼 미스매치. `feat/db/commitment/edit-delete-dispute-supersede` (PR #20) 선행 필수.

### 3.6 CommitmentCard '수동 추가' 배지 렌더 경로 부재 — MAN-004 위반

- **`android/app/src/main/java/com/becalm/android/ui/components/CommitmentCard.kt:54-90`** — `title` · `direction` · `derivedStatus` · `dueDate` · `counterpartyDisplayName` 5개 파라미터만. `sourceType` 파라미터 자체 없음 → `source_type=='manual'` 시각적 구분 렌더 경로 없음.
- **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt:61-68`** — `CommitmentRow` 데이터 클래스에도 `sourceType` 필드 없음.
- **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt:335-345`** (`applyFilter` 매핑) — `sourceType = entity.sourceType` 매핑 없음.

### 3.7 Extraction worker skip-guard 부재 — MAN-006 invariant 미enforce

```bash
grep -rn "source_type.*manual\|sourceType.*MANUAL\|skip.*manual" \
  android/app/src/main/java/com/becalm/android/worker/ \
  android/app/src/main/java/com/becalm/android/domain/
# → empty (0 matches)
```
→ MAN-006 invariant 를 단위 테스트로라도 확정할 assertion 없음. 현재 LLM 추출 worker 는 `raw_ingestion_events` → LLM → `commitments` INSERT 단방향이라 manual row 를 read 할 경로가 없어 우발적 재추출 가능성은 낮으나, invariant 를 코드 차원 테스트로 고정할 필요.

### 3.8 Routes.kt — 라우트 신규 추가 불필요

- **`android/app/src/main/java/com/becalm/android/ui/navigation/Routes.kt:143-144`** — `Commitments` 루트만. `CommitmentCreate(superssedesId)` 라우트 신규 추가 없음 — 본 PR 은 `ModalBottomSheet` 로 in-screen overlay 구현 → 라우트 추가 불필요 확인.

### 3.9 검증 grep 종합

```bash
grep -rn "CommitmentCreateSheet\|CommitmentCreateViewModel\|saveManualCommitment" \
  android/app/src/main/java/
# → 0 matches
```

---

## 4. Gap (spec vs code)

| 측면 | Spec 요구 | Code 현실 | Δ |
|------|-----------|-----------|---|
| FAB 진입점 (MAN-001) | `ExtendedFloatingActionButton [+ 약속 추가]` | `CommitmentManagementScreen.kt:70-73` 에 slot 없음 | Scaffold FAB slot 추가 |
| Sheet Composable (MAN-001/002) | `CommitmentCreateSheet` (NewManual + Supersede 2 mode) | 파일 없음 | 신규 파일 |
| Sheet ViewModel (MAN-005) | `CommitmentCreateViewModel` (validation + INSERT) | 파일 없음 | 신규 파일 |
| quote multiline (MAN-002) | 3줄, 1~500자, placeholder | 없음 | Sheet 내부 |
| direction RadioButton (MAN-005) | 'give'/'take', default 'give' | 없음 | Sheet 내부 |
| Repository INSERT (MAN-003) | `saveManualCommitment(input): BecalmResult<CommitmentEntity>` | `CommitmentRepository.kt:22-197` 에 부재 | 인터페이스 method 추가 |
| MAN-003 invariant 5종 | source_type='manual', confidence=1.0, source_ref=null, source_event_title=null, source_event_occurred_at=now() | 없음 | INSERT path 강제 |
| raw_ingestion_events INSERT 금지 | commitments 만 INSERT | enforce 할 코드 경로 없음 | INSERT path + 단위 테스트 |
| 수동 추가 배지 (MAN-004) | CommitmentCard 상단 우측 📝 + '수동 추가' | `CommitmentCard.kt:54-90` `sourceType` 파라미터 없음 | Card param 추가 + VM Row 확장 |
| 출처 섹션 분기 (MAN-004) | DetailSheet 에서 '사용자 직접 추가 ({created_at}, KST)' | DetailSheet 자체 부재 | `ui-commitment-detail-sheet.md` 협력 |
| MAN-006 worker skip | LLM 재추출 worker 가 manual 건너뜀 | guard 없음 | 단위 테스트로 invariant 확정 |
| SourceType.MANUAL 상수 | `SourceTypes.kt:14-46` 에 MANUAL | 미선언 | PR #21 선행 |
| last_edited_by / last_edited_at 컬럼 | `CommitmentEntity` 컬럼 2종 | 미존재 | PR #20 선행 |

---

## 5. Proposed Fix

**코드는 쓰지 말고 접근법만** 기술.

### 5.1 Files to change

1. **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt`**
   - `BecalmScaffold` 호출에 `floatingActionButton = { ExtendedFloatingActionButton(text = stringResource(R.string.commitments_fab_add_label), icon = Icons.Filled.Add, onClick = { showCreateSheet = true }) }` slot 추가.
   - 로컬 `rememberSaveable { mutableStateOf(false) }` state 로 sheet 표시 제어.
   - `if (showCreateSheet) CommitmentCreateSheet(mode = CreateMode.NewManual, onDismiss = { showCreateSheet = false }, onSaved = { showCreateSheet = false })` 조건부 렌더.
   - Sheet 닫힘 후 `CommitmentManagementViewModel` 의 `observeAllForUser` Flow 가 반응형으로 재emit → LazyColumn 자동 갱신.
   - `BecalmScaffold` 가 `floatingActionButton` slot 을 노출하지 않으면 `BecalmScaffold` 에 옵셔널 slot 추가 (default null) — 다른 호출부 영향 0.

2. **`android/app/src/main/java/com/becalm/android/ui/components/CommitmentCard.kt`**
   - 파라미터 `sourceType: String? = null` 추가 (backward-compat default null).
   - `sourceType == SourceType.MANUAL` 일 때 D-N 배지 옆 우측 상단 영역에 `Icons.Filled.EditNote` + `stringResource(R.string.commitment_card_manual_badge_label)` ("수동 추가") 렌더.
   - Contentdescription 도 함께 업데이트 — "수동 추가된 약속" 접두.

3. **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementViewModel.kt`**
   - `CommitmentRow` (`CommitmentManagementViewModel.kt:61-68`) 에 `sourceType: String` 추가.
   - `applyFilter` (`CommitmentManagementViewModel.kt:325-345`) 에서 `sourceType = entity.sourceType` 매핑.
   - 본 VM 에는 onCreateManual 액션 추가하지 않음 — Sheet 가 자체 VM (`CommitmentCreateViewModel`) 소유 (Surgical Changes 원칙).

4. **`android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepository.kt`**
   - 인터페이스에 신규 method:
     ```
     public suspend fun saveManualCommitment(input: ManualCommitmentInput): BecalmResult<CommitmentEntity>

     public data class ManualCommitmentInput(
       val userId: String,
       val title: String,                 // 1-200자 (MAN-005)
       val direction: String,             // "give" | "take" (MAN-005)
       val quote: String,                 // 1-500자 (MAN-002)
       val personRef: String?,            // 정규화 후 (이메일 lower / E.164)
       val dueAt: Instant?,               // null 허용 (MAN-005)
       val dueHint: String?,
       val dueIsApproximate: Boolean = false,
     )
     ```
   - KDoc 에 MAN-003 invariant 5종 자동 적용 명시 + `raw_ingestion_events INSERT 금지` invariant 명시.

5. **`android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepositoryImpl.kt`**
   - `saveManualCommitment` 구현 — UUID 생성 → `val now = Clock.System.now()` → `CommitmentEntity(..., sourceType = SourceType.MANUAL, confidence = 1.0, sourceRef = null, sourceEventTitle = null, sourceEventOccurredAt = now, createdAt = now, updatedAt = now, lastEditedBy = input.userId, lastEditedAt = now, syncStatus = "pending")` 를 하드코딩으로 set (MAN-003 invariant 강제) → `commitmentDao.insert(entity)`.
   - `rawIngestionEventDao` 는 호출하지 않음 (MAN invariant 80).
   - Railway POST 는 기존 `UploadWorker.flushCommitments()` 경로 재사용 — `sync_status='pending'` 으로 INSERT 하면 워커가 자동 drain.

6. **`android/app/src/main/java/com/becalm/android/data/remote/dto/SourceTypes.kt`** (PR #21 범위)
   - `MANUAL = "manual"` 상수 + `ALL` set 에 추가 — 본 plan out-of-scope, PR #21 선행.

### 5.2 Files to add

1. **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentCreateSheet.kt`**
   - Material3 `ModalBottomSheet`.
   - 2-mode sealed class:
     ```
     public sealed class CreateMode {
       public data object NewManual : CreateMode()
       public data class Supersede(
         val originalId: String,
         val originalQuote: String,
         val originalSourceEventTitle: String?,
         val originalSourceEventOccurredAt: Instant?,
       ) : CreateMode()
     }
     ```
   - 섹션 (위→아래):
     1. 상단 제목: "약속 직접 추가" (NewManual) / "이 약속을 다른 약속으로 대체" (Supersede)
     2. 안내 라벨 (NewManual 만): "직접 추가한 약속은 LLM 추출 약속과 동일하게 관리됩니다"
     3. quote `OutlinedTextField` — `singleLine = false`, `minLines = 3`, `maxLines = 5`, 500자 카운터, placeholder "이 약속의 맥락을 기록하세요...". Supersede 모드: `enabled = false` + 회색 Surface 카드.
     4. title `OutlinedTextField` — singleLine, 200자 카운터.
     5. direction RadioButton 2개 ("내가 한 약속" / "상대가 한 약속") — default `"give"` (MAN-005).
     6. person_ref `OutlinedTextField` (선택) — 본 PR 은 plain TextField + blur 시 정규화. 자동완성은 후속 plan.
     7. due_at Material3 `DatePicker` + `TimePicker` — null 허용 ([지우기] 버튼).
     8. due_hint `OutlinedTextField` (선택).
     9. due_is_approximate `Switch` (default off).
     10. 하단 Row `[취소]` + `[저장]`. `[저장]` enabled = `state.canSave`.
   - Validation 에러는 각 필드 하단 `supportingText` (빨간색).
   - a11y: 48dp 최소 터치, 각 필드 `contentDescription`.

2. **`android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentCreateViewModel.kt`**
   - `@HiltViewModel`. 생성자: `CommitmentRepository`, `UserPrefsStore`, `Logger`.
   - `uiState: StateFlow<CommitmentCreateUiState>` — title/direction/quote/personRef/dueAt/dueHint/dueIsApproximate + fieldErrors map + `saving` + `savedId` 이벤트.
   - 액션: `onTitleChange`, `onDirectionChange`, `onQuoteChange`, `onPersonRefChange`, `onDueAtChange`, `onDueHintChange`, `onDueIsApproximateChange`, `onSaveClick`, `onCancel`.
   - `validate()` 순수 함수 (MAN-005 규칙 전체) — title 1-200, direction ∈ ('give','take'), quote trim 1-500, person_ref optional (공백만이면 null 로 변환), due_at optional.
   - `onSaveClick` flow: validate → person_ref 정규화 util 호출 → `repo.saveManualCommitment(input)` → Success 시 `savedId` 방출 + Sheet dismiss / Failure 시 snackbar state.

3. **테스트 파일** (신규 4종):
   - `CommitmentCreateViewModelValidationTest.kt` — MAN-005 규칙 전체
   - `CommitmentCreateViewModelSaveTest.kt` — Success / Failure path
   - `CommitmentRepositoryImplManualTest.kt` — MAN-003 invariant 5종 + `raw_ingestion_events` 미호출 verify
   - `CommitmentCreateSheetTest.kt` — Compose UI test (FAB → Sheet 열림, 필드 노드 존재, direction default 'give')

### 5.3 Files to delete (dead code)
없음.

### 5.4 Non-code changes

- **strings.xml** 신규 키 (한국어 원문):
  - `commitments_fab_add_label = "+ 약속 추가"`
  - `commitment_create_title_new = "약속 직접 추가"`
  - `commitment_create_title_supersede = "이 약속을 다른 약속으로 대체"`
  - `commitment_create_notice = "직접 추가한 약속은 LLM 추출 약속과 동일하게 관리됩니다"`
  - `commitment_create_quote_label = "약속 맥락"`
  - `commitment_create_quote_placeholder = "이 약속의 맥락을 기록하세요 (예: 3/15 팀 미팅 중 월요일 보고서 전달 구두 약속)"`
  - `commitment_create_quote_empty_error = "약속의 맥락을 최소 1자 이상 입력해주세요"`
  - `commitment_create_quote_too_long_error = "최대 500자까지 입력할 수 있습니다"`
  - `commitment_create_title_empty_error = "제목을 입력해주세요"`
  - `commitment_create_title_too_long_error = "최대 200자까지 입력할 수 있습니다"`
  - `commitment_card_manual_badge_label = "수동 추가"`
- **Drawable / Icons**: `Icons.Filled.EditNote` 재사용 (Material Icons 표준) — 별도 vector drawable 추가 불필요.
- **Railway 서버**: POST `/v1/commitments` 가 `source_type='manual'` 수락 — 서버 팀 별도 세션 (PR #21 out-of-scope).
- **DB migration**: PR #20 이 이미 처리. 본 plan 신규 migration 없음.

---

## 6. Acceptance Criteria

다른 세션이 구현 완료 여부를 **기계적으로 검증**할 수 있는 항목.

- [ ] **File exists**: `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentCreateSheet.kt`
- [ ] **File exists**: `android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentCreateViewModel.kt`
- [ ] **Grep invariant**: `grep -rn "ExtendedFloatingActionButton.*약속\|ExtendedFloatingActionButton\s*(" android/app/src/main/java/com/becalm/android/ui/commitments/CommitmentManagementScreen.kt | wc -l` ≥ 1
- [ ] **Grep invariant**: `grep -n "saveManualCommitment" android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepository.kt android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepositoryImpl.kt | wc -l` ≥ 2 (인터페이스 + 구현)
- [ ] **Grep invariant**: `grep -n "source_type.*manual\|SourceType.MANUAL" android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepositoryImpl.kt | wc -l` ≥ 1
- [ ] **Grep invariant**: `grep -rn "sourceType\s*:\s*String" android/app/src/main/java/com/becalm/android/ui/components/CommitmentCard.kt | wc -l` ≥ 1
- [ ] **Grep invariant**: `grep -n "commitment_card_manual_badge_label\|수동 추가" android/app/src/main/res/values/strings.xml android/app/src/main/java/com/becalm/android/ui/components/CommitmentCard.kt | wc -l` ≥ 2
- [ ] **Grep invariant (금지)**: `grep -n "rawIngestionEventDao\|raw_ingestion_events" android/app/src/main/java/com/becalm/android/data/repository/CommitmentRepositoryImpl.kt | grep -i "saveManual\|createManual" | wc -l` = 0 (MAN invariant 80 — raw_ingestion_events INSERT 금지)
- [ ] **Unit test**: `CommitmentCreateViewModelValidationTest — empty title → canSave=false + error="제목을 입력해주세요"`
- [ ] **Unit test**: `CommitmentCreateViewModelValidationTest — title 201자 → error="최대 200자까지 입력할 수 있습니다"`
- [ ] **Unit test**: `CommitmentCreateViewModelValidationTest — quote 공백만 → error="약속의 맥락을 최소 1자 이상 입력해주세요"`
- [ ] **Unit test**: `CommitmentCreateViewModelValidationTest — quote 501자 → error="최대 500자까지 입력할 수 있습니다"`
- [ ] **Unit test**: `CommitmentCreateViewModelValidationTest — 초기 direction default == "give"` (MAN-005)
- [ ] **Unit test**: `CommitmentCreateViewModelValidationTest — person_ref "  ABC@Example.com " → 정규화 "abc@example.com"`
- [ ] **Unit test**: `CommitmentRepositoryImplManualTest — saveManualCommitment → entity.sourceType == "manual" && confidence == 1.0 && sourceRef == null && sourceEventTitle == null` (MAN invariant 77)
- [ ] **Unit test**: `CommitmentRepositoryImplManualTest — saveManualCommitment → entity.sourceEventOccurredAt == entity.createdAt` (MAN invariant 78)
- [ ] **Unit test**: `CommitmentRepositoryImplManualTest — saveManualCommitment 호출 중 rawIngestionEventDao.insert*() 0회 invoke verify` (MAN invariant 80)
- [ ] **Unit test**: `CommitmentRepositoryImplManualTest — lastEditedBy == input.userId && lastEditedAt == entity.createdAt` (MAN-003)
- [ ] **UI test (Compose)**: `CommitmentCreateSheetTest — FAB 노드 탭 → ModalBottomSheet 표시 + title/quote/direction 필드 노드 모두 존재`
- [ ] **UI test**: `CommitmentCreateSheetTest — direction RadioButton 초기 selected == "give"`
- [ ] **UI test**: `CommitmentCreateSheetTest — Supersede 모드 → quote 필드 enabled == false + 원본 quote 텍스트 표시`
- [ ] **Integration test**: 저장 후 `CommitmentManagementScreen` LazyColumn 최상단에 `sourceType == "manual"` 카드 + "수동 추가" 배지 존재
- [ ] **Compile gate**: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 성공

---

## 7. Out of Scope

이 PR 에서 **건드리지 말 것**.

- `SourceType.MANUAL` enum 상수 신규 추가 — `repo-commitment-source-type-manual.md` (PR #21) 담당. 본 plan 은 PR #21 merge 후 import 만.
- 6 컬럼 (`last_edited_by` · `last_edited_at` · `quote_disputed` · `quote_disputed_at` · `deleted_at` · `supersedes_commitment_id`) Room 스키마 + Migration — `db-commitment-edit-delete-dispute-supersede.md` (PR #20) 담당. `CommitmentEntity.kt` / DAO / Migrations 편집 금지.
- EDIT-* (commitment-edit.spec.yml) 모든 behavior — `ui-commitment-edit-sheet.md` 별도 plan. 본 plan 은 Supersede 모드의 **입력 화면만** 제공; old row `deleted_at` set + new row `supersedes_commitment_id` set 의 **저장 합성 로직** 은 EDIT sheet 가 `repo.supersede(oldId, newInput)` 같은 합성 method 로 처리 (별도 plan).
- CommitmentCard 의 `📝` 아이콘 자산 (vector drawable) 디자인 결정 — `Icons.Filled.EditNote` 재사용으로 단순화 확정.
- Railway 서버 POST validation 확장 (`source_type='manual'` 수락) — 서버 팀 별도 세션.
- person_ref autocomplete (Room persons_enrichment 매칭 드롭다운) — 본 PR plain TextField + 정규화만. EDIT-002 와 동일 후속 plan.
- `CommitmentExtractionWorker` / `VoiceExtractionWorker` 에 명시적 `WHERE source_type != 'manual'` SQL guard 추가 — 현재 workers 는 `raw_ingestion_events` → LLM → `commitments` 단방향이라 manual row 를 read 할 경로 자체가 없음. MAN-006 invariant 는 `CommitmentRepositoryImplManualTest` 의 단위 테스트 (`rawIngestionEventDao` 0회 invoke) 로만 enforce.
- `RetentionSweepWorker` 수정 — `worker-retention-sweep-30-day.md` 가 `raw_ingestion_events` 만 스윕하도록 이미 명시. manual commitment 에 영향 0. 본 plan 변경 없음.

---

## 8. Dependencies

- **Blocked by**:
  - **PR #21** `feat/repo/commitment/source-type-manual` — `SourceType.MANUAL = "manual"` 상수 추가. PR #21 merge 전에는 `CommitmentRepositoryImpl.saveManualCommitment` 에서 `SourceType.MANUAL` 참조가 컴파일 실패. 임시 회피로 string literal `"manual"` 사용 가능하나 비권장.
  - **PR #20** `feat/db/commitment/edit-delete-dispute-supersede` — `last_edited_by` / `last_edited_at` 컬럼 미존재 상태에서 INSERT 시 Room 컬럼 미스매치. MAN-003 의 두 필드 set 을 완전 충족하려면 PR #20 선행 필수.
  - **`ui-commitment-detail-sheet.md`** — DetailSheet 의 '출처' 섹션 분기 (MAN-004 — '사용자 직접 추가 ({created_at}, KST)' + source_event_title/occurred_at 숨김) 가 본 plan 의 manual row 를 입력으로 받음. DetailSheet plan 이 먼저 merge 되면 본 plan 에 분기 commit 포함; 아니면 본 plan merge 후 DetailSheet plan 이 분기 추가.
- **Blocks**:
  - `ui-commitment-edit-sheet.md` EDIT-007 supersede mode — 동일 `CommitmentCreateSheet` 를 `CreateMode.Supersede` 로 재사용. 본 plan 의 Sheet 가 선행 존재해야 EDIT-007 `[이건 다른 약속입니다]` 버튼이 연결 가능.
- **병렬 가능**:
  - `feat/ui/commitment` umbrella branch (**PR #22**) 내 다른 logic unit — `ui-commitment-detail-sheet.md`, `ui-commitment-reminder-due-gate.md`, `ui-commitment-completed-section-undo.md`, `ui-commitment-pull-refresh.md`, `ui-commitment-cancel-action.md`, `ui-commitment-dn-badge-kst.md`. 신규 파일 2종 (`CommitmentCreateSheet.kt` · `CommitmentCreateViewModel.kt`) 은 다른 plan 과 파일 겹침 0. 공유 파일인 `CommitmentManagementScreen.kt` · `CommitmentCard.kt` · `CommitmentManagementViewModel.kt` 는 umbrella branch 의 linear commit stack 으로 자연 정렬 → 충돌 없음.

Merge 순서: (PR #20 + PR #21) → 본 plan commit + `ui-commitment-detail-sheet.md` commit → `ui-commitment-edit-sheet.md` commit — 모두 umbrella PR #22 내부.

---

## 9. Rollback plan

```bash
git revert <commit-sha>
```

신규 파일 2종 (`CommitmentCreateSheet.kt`, `CommitmentCreateViewModel.kt`) + `CommitmentRepository` 인터페이스 `saveManualCommitment` method + `CommitmentRepositoryImpl` 구현 + `CommitmentCard.kt` `sourceType` 파라미터 + `CommitmentManagementScreen.kt` FAB slot + `CommitmentManagementViewModel` `CommitmentRow.sourceType` 추가 — Revert 시 모두 제거되고 기존 상태로 복귀. DB 스키마 변경 없음 (PR #20 의 컬럼은 남음).

**데이터 영향**: 이미 사용자가 manual 약속을 1건이라도 INSERT 했다면 Room 에 `source_type='manual'` row 가 그대로 남음. Revert 후 UI 가 이를 표시할 경로 (`SourceType.MANUAL` branch) 가 사라져 **invisible 상태** 가 되며 데이터 손실은 없음. 재배포 시 sheet 가 복구되면 기존 row 가 다시 보임. **사용자 혼란 가능 → revert 전 in-app 노티 권장**.

복구 시나리오:
1. Revert 단독 → manual row 일시적 invisible. 3-day rollforward 권장.
2. Schema downgrade 없음 (PR #20 무관) → DB 위험 0.

최소 롤백: `CommitmentManagementScreen` 의 FAB slot 라인 1줄만 주석 처리하면 진입점 제거 + 기존 manual row 는 여전히 목록에 표시 (배지까지 렌더).

---

## Appendix — Session handoff notes

- **PR #20 + PR #21 머지 후 시작 권장**. 두 PR 미머지 상태에서는 (a) `SourceType.MANUAL` 미존재로 `CommitmentRepositoryImpl.saveManualCommitment` 컴파일 실패 (b) `last_edited_by` / `last_edited_at` 컬럼 미존재로 INSERT 시 Room 컬럼 미스매치. 회피는 string literal + 두 필드 생략이나 추후 재방문 비용 높음.
- **BecalmScaffold FAB slot 확인 먼저**:
  ```bash
  grep -n "floatingActionButton" android/app/src/main/java/com/becalm/android/ui/components/BecalmScaffold.kt
  ```
  노출 없으면 옵셔널 `floatingActionButton: (@Composable () -> Unit)? = null` 추가 권장 (default null → 다른 호출부 영향 0). `Box` overlay 대안도 가능하나 일관성 떨어짐.
- **2-mode sealed class**: `CommitmentCreateSheet` 는 `CreateMode.NewManual` / `CreateMode.Supersede(originalId, originalQuote, originalSourceEventTitle, originalSourceEventOccurredAt)` 두 모드를 같은 Composable 본체에서 분기. EDIT-007 구현 세션이 이 sealed class 의 `Supersede` 를 호출하면 됨. Supersede 모드에서는 quote 필드 `enabled=false`, 안내 라벨 숨김, 상단 제목 변경.
- **confidence 타입**: `CommitmentEntity.confidence` (`CommitmentEntity.kt:125-126`) 는 `Double`. MAN-003 `confidence=1.0` 은 `Double(1.0)` 로 set — Float/Double 혼동 주의.
- **`source_event_occurred_at = now()` 의미 주석 명시**: LLM 추출 약속에서는 "원본 이벤트 발생 시각" 이지만 manual 에서는 "저장 시각". MAN invariant 78. KDoc 에 이 semantic 차이를 반드시 명시 — 추후 유지보수 혼동 방지.
- **person_ref 정규화 util 재사용**: EDIT-004 (`ui-commitment-edit-sheet.md`) 와 동일.
  ```bash
  grep -rn "fun normalizePersonRef\|object PersonRef\|class PersonRefNormalizer" android/app/src/main/java/
  ```
  없으면 본 PR 또는 EDIT sheet PR 중 먼저 가는 쪽이 util 신규 추가 후 다른 쪽이 import. 이메일: `lowercase()`. 전화: libphonenumber 의존성 확인 후 E.164 시도, 실패 시 원본 유지.
- **MAN-006 enforce 방법**: 명시적 `WHERE source_type != 'manual'` SQL guard 불필요. 이유: LLM 추출 worker 는 `raw_ingestion_events` → `commitments` 단방향이라 manual row 를 read 할 경로 자체가 없음. `CommitmentRepositoryImplManualTest` 에서 `saveManualCommitment` 호출 중 `rawIngestionEventDao` mock 이 0회 invoke 됨을 `verify(rawIngestionEventDao, never()).insert(any())` 로 assert — invariant 80 + 81 둘 다 확정.
- **ExtendedFAB 라벨**: `"+ 약속 추가"` 원문 그대로 strings.xml 에. 아이콘 `Icons.Filled.Add`. FAB expand/collapse 는 불필요 — spec 은 항상 확장 상태.
- **Sheet dismiss 후 list 자동 갱신 verify**: `CommitmentManagementViewModel.observeCommitments` 가 `commitmentRepository.observeAllForUser(userId)` 를 Flow 로 수집 (`CommitmentManagementViewModel.kt:160-193`). Room `@Insert` 는 Flow 를 재emit 하므로 자동 갱신 동작. UI test 로 "저장 → Sheet dismiss → 목록 최상단에 새 카드" 시나리오 검증.
- **"수동 추가" 배지 색상**: 디자인 token 별도 정의 없으면 `MaterialTheme.colorScheme.tertiaryContainer` / `onTertiaryContainer` 조합 권장. D-N 배지와 시각적 충돌 회피 — D-N 배지는 primaryContainer 계열이므로 tertiary 가 차별화. 디자이너 최종 확인.
- **Supersede 저장 로직 소속**: 본 plan 은 **입력 화면** 제공까지. old row `deleted_at = now()` + new row `supersedes_commitment_id = old.id` atomic 처리는 `CommitmentRepository.supersede(oldId, newInput): BecalmResult<Unit>` 같은 합성 method 가 필요하며 이는 `ui-commitment-edit-sheet.md` 또는 별도 `repo-commitment-supersede.md` plan 소속. 본 plan 의 `saveManualCommitment` 는 **새 row INSERT 만** 책임.
- **Compose BOM 확인**: `ModalBottomSheet` 는 Material3 1.2+ 에 stable. `gradle/libs.versions.toml` 의 `compose-bom` 버전 우선 확인 후 부족하면 bump (별도 commit).
