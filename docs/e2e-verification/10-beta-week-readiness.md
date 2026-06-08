# 10. Beta Week Readiness

Spec: `becalm-android/.spec/beta-week-readiness.spec.yml`

> Product invariant: a beta invite is allowed only when the app can run for a
> week with no crash/ANR, no unrecoverable server error, no acknowledged data
> failure, and a visible action-first utility path. Implemented features are not
> enough; the release needs evidence.

## Current Decision

Current spec/contract before this readiness layer is **not sufficient** for a
99% confidence beta. The action feed contract covers recomputation,
idempotency, retry, delta, and worker isolation, but beta reliability needs
additional app-level contracts:

- crash/process-death recovery
- every Android-facing API error normalized to retry/user repair/support
- Room migration and local data recovery
- auth/session/source reconnect recovery over 7 days
- remote kill switch / force upgrade / maintenance mode
- privacy-safe observability and release gates
- beta utility proof that users see an action or guided setup path

Latest device proof: on 2026-06-05 KST,
`qa/device/scripts/person_action_live_dev_device_smoke.py --confirm-live-dev --confirm-clear-data`
passed against `https://dev-dev-7309.up.railway.app` on SM-F721N. The smoke
created a temporary dev auth user, wrote a manual memory, observed
`/v1/person_action_items?surface=person` return one `caught_up` action, ran
`pm clear com.becalm.android`, injected the real dev session into the debug app,
refreshed Android Room, and verified the People action-first UI. Report:
`qa/device/reports/person-action-live-dev-20260604-220941/summary.env`
(`backend_feed_status=200`, `backend_feed_count=1`,
`android_refresh_logged_success=true`, `action_title_present=true`,
`fatal_anr_oom_count=0`, `issues=`). Railway dev logs in the same window showed
the smoke calls returning 200/201 and zero `error OR exception OR 503` matches.

Latest source reconnect contract proof: on 2026-06-05 KST, the live dev OAuth
source-sync verifier selected a real Google mail connection in `needs_reauth`.
`POST /v1/source_connections/{id}:sync` returned 409 with
`error=source_connection_needs_reauth` and `client_action=reconnect_source`, not
a queued sync job. Evidence:
`../../becalm-backend/reports/dev-oauth-source-sync-current-20260605.jsonl`.
Android `DefaultSourceSyncPort` now parses that
`ErrorEnvelope`, records canonical `needs_reauth` source/processing status,
refreshes source connection/status mirrors, and avoids surfacing raw 409
`Conflict` copy. Targeted gate:
`./gradlew testDebugUnitTest --tests com.becalm.android.unit.ui.sources.SourceSyncPortSpecTest --tests com.becalm.android.integration.local.ui.settings.SettingsUiTest`
passed.

## Function-Level Gates

| Gate | Required function or boundary | Proof |
| --- | --- | --- |
| Go/no-go | `BetaWeekReadinessGate.evaluate` | unit test: failed/skipped hard gates and bad metrics return `no_go` |
| Remote errors | `ApiErrorNormalizer.normalize` + `SafeRemoteCall.execute` | unit + integration tests for timeout, empty 500, malformed JSON, 401, 404, 409, 422, 429, 503 |
| Crash boundary | `AppCrashBoundary.install` + `SafeCoroutineLauncher.launchObserved` | device smoke: FATAL EXCEPTION / ANR / OOM = 0 |
| Worker mapping | `WorkerResultMapper.map` | unit tests verify transient => retry, repairable terminal => success+state, no raw throw |
| Delta integrity | `TransactionalDeltaApplier.applyPersonActionFeed` | kill-during-transaction integration; previous watermark refetches |
| Snapshot pagination | `PersonActionFeedRepository.fetchSnapshotPages` | `snapshot_id` bound pages; watermark advances only after final page |
| Outbox integrity | `OutboxFlushCoordinator.flush` | duplicate replay, backend 503, idempotency conflict, explicit discard |
| Mutation ledger | backend `person_action_mutation_ledger` | duplicate payload replay and divergent duplicate 409 tests |
| Recompute trigger map | backend write-boundary recompute hooks | source graph, commitment, calendar, source status, person memory, review, schedule link, feedback trigger tests |
| Operator requeue | backend `requeue_person_action_recompute_job` | dead_letter/failed/expired-processing jobs return to pending without marking caught_up |
| Generator determinism | backend `PersonActionGenerator.generate` | golden tests for action kind, surface, stable id, scoring, collapse, user-state overlay |
| Migration | `RoomMigrationReadinessVerifier.verify` + `DatabaseOpenRecoveryPolicy.resolve` | upgrade from every released beta schema; no destructive migration |
| Auth/session | `AuthRefreshSingleFlight.refreshAndRetry` + `UserScopedDatabaseManager.openForUser` | concurrent 401, login switch, logout, revoked token, app restart |
| Feature flags | `ClientConfigRepository.refresh` + `FeatureGate.resolve` | force upgrade, maintenance, source disabled, action feed disabled |
| Data sentinel | `DataIntegritySentinel.scan` | detects cross-user rows, orphan cache rows, stuck outbox, watermark regression |
| Utility | `FirstWeekActionSurfaceProjector.project` + `BetaAhaTelemetry.markActionSeen` | no-source, connected-no-action, pending-context, caught-up active action |
| Observability | Crashlytics/product events/backend logs | sanitized event sample contains no PII and includes request/incident ids |

## API Gates

| Endpoint | Beta requirement |
| --- | --- |
| `GET /health` | Secret-safe health includes environment, process role, worker queues, dependency readiness |
| `GET /v1/client_config` | Returns min supported version, force upgrade, maintenance, feature flags, disabled sources |
| `POST /v1/product_events/batch` | Idempotent, bounded, non-PII beta readiness events; ingestion failure does not block UX |
| every Android-facing `/v1/*` endpoint | Non-2xx is `ErrorEnvelope`; legacy body must be normalized by Android |
| `GET /v1/person_action_items` | Delta is snapshot-consistent; paginated responses use `snapshot_id`; old/unknown `changed_since` returns `409 full_refresh`; empty first fetch includes `empty_state` |
| `PATCH /v1/person_action_items/{id}` / feedback | `client_mutation_id` is backed by server payload-hash ledger; duplicate payload replays, divergent duplicate returns 409 |

## Hard Release Budgets

| Metric | Budget |
| --- | ---: |
| crash-free sessions | >= 99.5% |
| crash-free users | >= 99% |
| ANR | 0 |
| unrecoverable server error | 0 |
| acknowledged data loss | 0 |
| missing action delta | 0 |
| cross-user data leak | 0 |
| destructive migration | forbidden |

## Seven-Day Compressed Scenario Matrix

Run before inviting testers:

1. Fresh install -> signup/login -> onboarding -> connect one backend provider -> action feed visible.
2. Fresh install -> local voice/meeting/message import -> upload pending -> backend accept -> action feed visible.
3. Network offline 12h -> foreground cache visible -> mutations queued -> network returns -> outbox flushes exactly once.
4. Backend 503/429 -> retryable UI -> stale cache remains -> later success clears degraded state.
5. Token expires -> refresh single-flight -> request retry once -> success or login route.
6. Provider OAuth revoked -> source reconnect action -> reconnect -> sync resumes.
7. App killed during delta apply -> restart -> previous watermark refetch -> no missing rows.
8. App update migration -> DB opens -> outbox and watermarks preserved.
9. Logout user A -> login user B -> no user A rows visible.
10. Action worker backlog -> `capacity_state=backlog` -> compact health -> caught_up clears.
11. Paginated action delta -> app kill after page 1 -> restart -> previous watermark refetch -> no missing rows.
12. Dead-letter action recompute -> operator requeue -> worker completes -> stale/degraded UI clears.
13. Fresh install with no action rows -> explicit setup/caught-up/sync-pending state, not blank dashboard.

## Minimal Command Gate

Android:

```bash
cd becalm-android/android
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

Samsung smoke:

```bash
ADB=/mnt/c/Users/jakek/AppData/Local/Android/Sdk/platform-tools/adb.exe
$ADB devices
$ADB -s R5CT83SMP4P install -r app/build/outputs/apk/debug/app-debug.apk
$ADB -s R5CT83SMP4P logcat -c
$ADB -s R5CT83SMP4P shell monkey -p com.becalm.android -c android.intent.category.LAUNCHER 1
sleep 5
$ADB -s R5CT83SMP4P logcat -d -v time | rg -i "FATAL EXCEPTION|AndroidRuntime|ANR|OutOfMemoryError|becalm"
```

Backend:

```bash
cd becalm-backend
python3 -m compileall -q app
python3 -m pytest
curl -fsS "$BECALM_API_BASE_URL/health"
curl -fsS "$BECALM_API_BASE_URL/v1/client_config?platform=android&version_code=<build>"
```

## No-Go Conditions

Any one of these blocks tester invite:

- Crash/ANR/OOM in smoke or beta cohort.
- Full Android unit/lint/assemble gate red unless failure is proven unrelated and non-crash/non-data.
- Backend tests or compile red on Android-facing paths.
- Any Android-facing endpoint returns an unnormalized non-2xx.
- Room migration from a released beta build is untested or destructive.
- Missing action delta, watermark regression, stuck durable outbox without CTA, or cross-user row.
- Missing recompute trigger for an action-impacting backend write, unbound snapshot pagination, or mutation idempotency without ledger proof.
- Dead-letter recompute row with no service-role requeue path or no user/ops visible recovery state.
- Action feed empty for >24h for a connected-source tester without no-action caught-up or repair/setup action.
- Remote kill switch/force upgrade path unimplemented.
- Telemetry/support report can include raw source content, token, email, phone, local path, or signed URL.

## Rollout Policy

- Start with internal dogfood.
- Move to <=10 external testers only after all gates are green.
- Expand only after 48h with crash/API/data budgets still inside limits.
- Any data loss, cross-user leak, unrecoverable server error, ANR, or destructive migration stops expansion immediately.
