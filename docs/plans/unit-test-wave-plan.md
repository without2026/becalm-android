# BeCalm Android Unit Test Wave Plan

작성일: 2026-04-22  
기준 문서: `docs/android-tdd-sdd-bible.md`, `docs/becalm-mvp-boundary.md`, `.spec/*`

## 1. 작업 원칙

- 이 계획은 **구현 본문이 아니라 spec + 선언부(signature)** 기준으로 작성한다.
- 기존 `android/app/src/test` 코드는 참고 대상일 수는 있지만 **정답 소스가 아니다**.
- 우선순위는 `pure function -> ViewModel orchestration -> repository/worker hermetic unit` 순서로 잡는다.
- Compose 시각 회귀, Navigation, Room SQL, WorkManager 실제 스케줄링, ContentResolver/MediaStore/네트워크 왕복은 이 문서의 범위가 아니다. 그것들은 별도 integration 또는 `androidTest`로 간다.

## 1.1 테스트 폴더 규칙

- JVM 순수 단위 테스트: `android/app/src/test/java/com/becalm/android/unit/...`
- JVM hermetic integration 테스트: `android/app/src/test/java/com/becalm/android/integration/...`
- 디바이스/에뮬레이터 테스트: `android/app/src/androidTest/java/com/becalm/android/...`
- 새 테스트는 반드시 위 3개 축 중 하나에만 들어간다. 기존 `src/test/java/com/becalm/android/...` 루트 직하 flat 배치는 더 늘리지 않는다.

## 2. Unit-Testable Seam Inventory

| Priority | Area | Spec IDs | Target Signature / Seam | Planned Test File |
|---|---|---|---|---|
| P0 | Voice retry/quarantine | `VOI-002`, `VOI-003`, `VOI-006`, `ERR-005` | `VoiceUploadStateMachine.decideRetryAction(runAttemptCount, maxAttempts)`, `VoiceUploadStateMachine.decide502Action(errorCode)` | `unit/worker/VoiceUploadStateMachineSpecTest.kt` |
| P0 | Upload retry math | `SYNC-003`, `SYNC-005`, `ERR-006` | `UploadBackoff.nextDelaySeconds(attempt, retryAfterSec)` | `unit/worker/UploadBackoffSpecTest.kt` |
| P0 | Auth refresh boundary | `AUTH-004`, `AUTH-007`, `ERR-008` | `AuthTokenProvider.currentAccessToken()`, `AuthTokenProvider.refresh(previousAccessToken)`, `AuthInterceptor.intercept(chain)` | `unit/data/remote/interceptor/AuthInterceptorSpecTest.kt` |
| P0 | User-scoped DB naming | `AUTH-008`, `PIPA-006` | `BeCalmDatabase.databaseFilename(userIdHash)`, `BeCalmDatabase.deriveUserIdHash(userId)` | `unit/data/local/db/BeCalmDatabaseNamingSpecTest.kt` |
| P0 | Commitment lifecycle matrix | `CMT-005`..`CMT-013` | `CommitmentStateMachine.transition(...)`, `CommitmentState.fromWire(value)` | `unit/domain/commitment/CommitmentStateMachineSpecTest.kt` |
| P0 | Edit validation/normalization | `EDIT-003`, `EDIT-004`, `EDIT-005`, `EDIT-006`, `EDIT-007` | `CommitmentEditValidator.validate(input)`, `CommitmentEditValidator.normalise(draft)` | `unit/domain/commitment/CommitmentEditValidatorSpecTest.kt` |
| P0 | Manual commitment validation | `MAN-002`, `MAN-003`, `MAN-005`, `MAN-006` | `CommitmentManualValidator.validate(input)`, `CommitmentManualValidator.normalise(draft)` | `unit/domain/commitment/CommitmentManualValidatorSpecTest.kt` |
| P0 | Person ref normalization | `EDIT-004`, `MAN-005`, `SRC-003` | `PersonRefNormalizer.normalize(raw)`, `PersonRefNormalizer.isValidPhoneShapeOrFreeForm(normalized)` | `unit/domain/commitment/PersonRefNormalizerSpecTest.kt` |
| P0 | Email person resolution | `EMAIL-001`, `EMAIL-002` | `EmailPersonRef.forInbox(fromAddress)`, `EmailPersonRef.forSent(toAddresses)`, `EmailPersonRef.isGroupEmail(recipientCount)` | `unit/domain/email/EmailPersonRefSpecTest.kt` |
| P0 | Email snippet rules | `EMAIL-003`, `EMAIL-004`, `EMAIL-006`, `EMAIL-007` | `EmailSnippetBuilder.buildSnippet(...)` | `unit/domain/email/EmailSnippetBuilderSpecTest.kt` |
| P0 | Reply quoted-block stripping | `EMAIL-005` | `QuotedBlockSplitter.split(bodyPlain)` | `unit/domain/email/QuotedBlockSplitterSpecTest.kt` |
| P0 | Email LLM prompt contract | `EMAIL-001`, `EMAIL-005` | `EmailPromptBuilder.buildSystemContext(...)`, `EmailPromptBuilder.buildUserContext(...)` | `unit/domain/email/EmailPromptBuilderSpecTest.kt` |
| P0 | Gmail header parsing | `EMAIL-001`, `EMAIL-002` | `canonicalizeEmail(fromHeader)`, `firstRecipientEmail(header)`, `gmailLabelsToFolder(labelIds)` | `unit/worker/ingestion/GmailHeadersSpecTest.kt` |
| P1 | Today overall sync aggregation | `TDY-003`, `TDY-008`, `ERR-002`, `ERR-003`, `ERR-007` | `deriveOverallState(sources)`, `buildChips(sourceStatus)` | `unit/ui/today/TodayOverallSyncSpecTest.kt` |
| P1 | Today refresh orchestration | `TDY-004`, `TDY-005`, `TDY-006`, `TDY-009`, `ING-011`, `SYNC-006` | `TodayViewModel.onPullRefresh()` | `unit/ui/today/TodayViewModelSpecTest.kt` |
| P1 | Auth screen orchestration | `AUTH-001`, `AUTH-002`, `AUTH-003`, `AUTH-005`, `ONB-006` | `AuthViewModel.onEmailSignIn(...)`, `onGoogleSignIn(...)`, `onSignOut()`, `onObserveSession()` | `unit/ui/auth/AuthViewModelSpecTest.kt` |
| P1 | Onboarding step graph | `ONB-001`, `ONB-PIPA`, `ONB-002`, `ONB-CONTACTS`, `ONB-004`, `ONB-007`, `ONB-008` | `OnboardingViewModel.onAcceptTerms()`, `onPipaConsentGranted()`, `onPipaConsentDeclined()`, `onSkipStep(step)`, `onMarkStepStatus(step, status)`, `reportOnboardingStepFailed(...)`, `onCompleteOnboarding()` | `unit/ui/onboarding/OnboardingViewModelSpecTest.kt` |
| P1 | Commitment list action flow | `CMT-001`..`CMT-010`, `CMT-012`, `CMT-013`, `MAN-001` | `CommitmentManagementViewModel.onFilterChange(...)`, `onPullRefresh()`, `onRemind(id)`, `onFollowUp(id)`, `onComplete(id)`, `onCancel(id)`, `onUndo(snapshot)` | `unit/ui/commitments/CommitmentManagementViewModelSpecTest.kt` |
| P1 | Commitment edit flow | `EDIT-001`..`EDIT-008` | `CommitmentEditViewModel.onTitleChange(...)`, `onDueAtMillisChange(...)`, `onDueIsApproximateChange(...)`, `onDueHintChange(...)`, `onPersonRefChange(...)`, `onDirectionChange(...)`, `onSave()`, `onToggleDispute()`, `onConfirmDelete()` | `unit/ui/commitments/CommitmentEditViewModelSpecTest.kt` |
| P1 | Manual/supersede create flow | `MAN-001`..`MAN-006`, `EDIT-007` | `CommitmentCreateViewModel.onTitleChange(...)`, `onDirectionChange(...)`, `onQuoteChange(...)`, `onPersonRefChange(...)`, `onDueAtMillisChange(...)`, `onDueHintChange(...)`, `onApproxChange(...)`, `onSave()` | `unit/ui/commitments/CommitmentCreateViewModelSpecTest.kt` |
| P1 | Source status projection | `SMG-001`..`SMG-005`, `TDY-003`, `TDY-008` | `SourceStatusRepository.observeAll()`, `observeFor(sourceType)`, `recordSyncSuccess(...)`, `recordSyncError(...)`, `recordSyncStart(...)` | `integration/data/repository/SourceStatusRepositorySpecTest.kt` |
| P1 | Settings / privacy toggles | `AUTH-005`, `SMG-004`, `PIPA-003`, `PIPA-004`, `PIPA-006` | `SettingsViewModel.onTogglePipaConsent(enabled)`, `onSignOut()` | `unit/ui/settings/SettingsViewModelSpecTest.kt` |
| P1 | Source detail orchestration | `SMG-002`, `SMG-003`, `SMG-004`, `SMG-005` | `SourceDetailViewModel.setUserId(userId)` | `unit/ui/sources/SourceDetailViewModelSpecTest.kt` |
| P1 | Foreground catch-up scheduling | `ING-011`, `SYNC-006`, `SRC-006`, `TDY-009`, `PIPA-004` | `ForegroundCatchUpScheduler.start()`, `triggerCatchUp()` | `integration/worker/ForegroundCatchUpSchedulerSpecTest.kt` |
| P1 | Work key generation | `VOI-006`, `ING-011`, `SYNC-006` | `UniqueWorkKeys.voiceUpload(rawEventId)`, `UniqueWorkKeys.commitmentExtractionKey(rawEventId)` | `unit/worker/UniqueWorkKeysSpecTest.kt` |
| P2 | Persons list filtering/projection | `SRC-001`, `SRC-003`, `SRC-005`, `SRC-006`, `SRC-007`, `ENR-006` | `PersonsViewModel.onQueryChange(q)` | `unit/ui/persons/PersonsViewModelSpecTest.kt` |
| P2 | Person detail sectioning | `SRC-002`, `SRC-008` | `PersonDetailViewModel` state projection | `unit/ui/persons/PersonDetailViewModelSpecTest.kt` |
| P2 | Raw event detail projection | `SRC-004`, `ERR-004`, `ERR-005` | `RawEventDetailViewModel` state projection | `unit/ui/persons/RawEventDetailViewModelSpecTest.kt` |
| P2 | Attachment meta parsing | `EMAIL-004`, `SRC-004` | `AttachmentMetaParser.parse(json)` | `unit/ui/persons/AttachmentMetaParserSpecTest.kt` |
| P2 | Retention worker decisions | `EMAIL-006`, global retention, `MAN-006` | `RetentionSweepWorker.doWork()` under fake repositories/stores | `integration/worker/RetentionSweepWorkerSpecTest.kt` |
| P2 | Consent pause / release repository rules | `VOI-004`, `PIPA-003`, `PIPA-004` | `RawIngestionRepository.releaseAwaitingConsentVoiceAndReturnIds(userId)`, `parkAndCancelPendingVoice(userId)` | `integration/data/repository/RawIngestionRepositorySpecTest.kt` |

## 3. 실행 순서

### Wave 1: 순수 함수 잠금

- `VoiceUploadStateMachine`
- `UploadBackoff`
- `CommitmentStateMachine`
- `CommitmentEditValidator`
- `CommitmentManualValidator`
- `PersonRefNormalizer`
- `EmailPersonRef`
- `EmailSnippetBuilder`
- `QuotedBlockSplitter`
- `EmailPromptBuilder`
- `GmailHeaders`
- `UniqueWorkKeys`
- `BeCalmDatabase` naming helpers

### Wave 2: auth / sync / today orchestration

- `AuthInterceptor`
- `AuthViewModel`
- `TodayOverallSync`
- `TodayViewModel`
- `SourceStatusRepository`
- `ForegroundCatchUpScheduler`

### Wave 3: commitments / source management / onboarding

- `CommitmentManagementViewModel`
- `CommitmentEditViewModel`
- `CommitmentCreateViewModel`
- `SettingsViewModel`
- `SourceDetailViewModel`
- `OnboardingViewModel`

### Wave 4: persons / retention / consent edge cases

- `PersonsViewModel`
- `PersonDetailViewModel`
- `RawEventDetailViewModel`
- `AttachmentMetaParser`
- `RetentionSweepWorker`
- `RawIngestionRepository`

## 4. 이번 턴 이후 바로 시작할 첫 Red

첫 failing unit test는 아래 순서로 연다.

1. `worker/VoiceUploadStateMachineSpecTest.kt`
2. `worker/UploadBackoffSpecTest.kt`
3. `domain/commitment/CommitmentStateMachineSpecTest.kt`

이 셋은 spec 밀도가 높고, Android framework/DB/network 의존 없이 가장 빠르게 `red -> green` 사이클을 시작할 수 있다.

## 5. Unit 범위에서 제외하는 것

- Compose screenshot / semantics 검증
- `NavHost` route wiring
- 실제 Room DAO SQL 결과 검증
- 실제 WorkManager enqueue/exponential backoff persistence
- 실제 MediaStore/ContactsContract/SAF 접근
- 실제 Supabase / Railway / OAuth / IMAP / Gmail / MS Graph 왕복

위 항목은 이후 별도 integration 또는 `androidTest` 계획으로 분리한다.
