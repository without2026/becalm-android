# 11. Historical Regression Guards

Spec: `becalm-android/.spec/historical-regression-guards.spec.yml`

> Product invariant: a previously observed bug is not considered fixed for beta
> until the equivalent regression guard exists in spec, contract, and test/smoke
> proof. This document lists the bug class, required boundary, and proof target.

## Guard Matrix

| ID | Previous bug class | Required boundary | Proof |
| --- | --- | --- | --- |
| BREG-001 | dev/staging/Auth/Data runtime mismatch | `RuntimeEnvironmentGuard.validate`, `/health`, cutover preflight | secret-safe env alignment test + live health |
| BREG-002 | OAuth callback treated as sync success | `OAuthCallbackStateMachine`, `SourceConnectionPoller`, sync trigger response | callback -> job -> source status integration |
| BREG-003 | Gmail activation preview empty/stalled | `ActivationPreviewRepository`, `SourceSyncJobPoller.awaitPreviewReady` | local-first preview + backend `include_unresolved_counterparty=true` fallback |
| BREG-004 | Self-identity Next looked frozen | `SelfIdentityReadinessPolicy`, `OnboardingViewModel.onIdentityNext` | validation/loading/retry/support UI test |
| BREG-005 | Backend fix did not repair stale local rows | `BackendRepairVersionPublisher`, `LocalProjectionRepairCoordinator` | stale projection version triggers bounded refresh |
| BREG-006 | People count overcount from joins | `PersonInteractionCountQuery` | multiple identities do not multiply count |
| BREG-007 | Speaker labels promoted to false people | `MeetingSpeakerPromotionPolicy`, `PersonMatchingEngine` | speaker-label-only stays review evidence |
| BREG-008 | Preview/cache extraction errors opaque | `ExtractionErrorMapper`, Android extraction handler | structured retry/review/support envelope |
| BREG-009 | Foreground catch-up wrong lane/noise | `ForegroundCatchUpScheduler`, `WorkSchedulerRequests` | backend-managed source exclusion + cancellation non-fatal smoke |
| BREG-010 | Reinstall/logout user-boundary leaks | `AuthBoundaryTeardown`, `ManualMemoryOutbox` | workers/reminders/notifications cancelled; idempotent memory retry |
| BREG-011 | KST/link resolver false overlaps | `ScheduleTimeNormalizer`, `ScheduleEventLinkResolver` | vague time stays approximate; ambiguous link needs review |
| BREG-012 | Source status and processing state conflated | `SourceStatusMerger`, `ProcessingStatusRepository` | connected/syncing/synced/needs_reauth/client_managed states distinct |
| BREG-013 | Analytics/support became PII sink | `ProductAnalyticsValidation`, `ObservabilityClient` | PII sample validation rejects raw content |
| BREG-014 | Readiness evidence drift | `ReleaseEvidenceCollector` | target SDK/runtime/test evidence matches current checkout |

## API Regression Checks

| Surface | Required contract |
| --- | --- |
| `GET /v1/commitments` | Supports `source_type`, `source_connection_id`, `include_unresolved_counterparty`, and `preview_mode` for activation preview. |
| `POST /v1/mail_sources:sync` | Returns `SourceSyncTriggerResponse`; accepted/running is not terminal synced. |
| `POST /v1/calendar_events:sync` | Returns `SourceSyncTriggerResponse`; Android waits for terminal source status/mirror refresh. |
| OAuth status endpoints | `connected` is token durability only; status may include `sync_state`, `job_id`, `retry_after_seconds`. |
| extraction endpoints | preview/cache/speaker/model failures use ErrorEnvelope with retryability and client action. |
| `/health` | Proves active runtime safely without secrets. |

## Required Test Names

Use these names or clearer local equivalents:

- `RuntimeEnvironmentGuardSpec`
- `OAuthCallbackStateMachineSpec`
- `ActivationPreviewRepositorySpec`
- `SelfIdentityReadinessPolicySpec`
- `PersonInteractionCountQuerySpec`
- `MeetingSpeakerPromotionPolicySpec`
- `ExtractionErrorMapperSpec`
- `ForegroundCatchUpSchedulerRegressionSpec`
- `AuthBoundaryTeardownSpec`
- `ManualMemoryOutboxSpec`
- `ScheduleTimeNormalizerSpec`
- `ScheduleEventLinkResolverSpec`
- `SourceStatusMergerSpec`
- `ProductAnalyticsValidationSpec`
- `ReleaseEvidenceCollectorSpec`

## No-Go Rule

If a feature slice touches any guarded area and the matching BREG test/smoke is
missing, beta readiness remains conditional/no-go even if the happy path works.

## Manual Smoke Additions

- OAuth return: verify UI says connected/syncing before synced, then synced only
  after terminal source status and local mirror refresh.
- Activation preview: connect Gmail/Outlook and confirm preview shows either
  cards, pending with progress, reconnect, or explicit empty reason within the
  foreground budget.
- User switch: sign out from user A, sign in user B, confirm no reminders,
  notifications, workers, or rows from A appear.
- Device logcat: lifecycle cancellation must not appear as a fatal crash.
- Schedule: ambiguous extracted time/link must show review/approximate state, not
  a false precise Today overlap.
