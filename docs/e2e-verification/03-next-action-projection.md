# 03. Backend Person Action Feed

Spec: `becalm-android/.spec/next-action-projection.spec.yml`

> Product invariant: source origin can be Android local or backend/provider
> owned, but product action decisions are backend-owned. Person, Schedule, and
> Commitment render cached `PersonActionItem` rows from Railway. Source history,
> timelines, and raw rows are evidence/history only.

## Target Flow

```text
Android local source adapters / Railway provider sync
  -> bounded structured context upload or backend durable fetch
     raw_ingestion_events
     commitments
     calendar_events
     source_event_participants
     commitment_participants
     person_interactions
     person memory context/index metadata
  -> Railway backend action recomputation
     person_action_recompute_jobs
     claim_person_action_recompute_jobs / complete_person_action_recompute_job
     dedicated person-action-recompute Railway worker
     person_action_items
     person_action_item_evidence_refs
     person_action_item_user_states
     person_action_feed_watermarks
  -> GET /v1/person_action_items
  -> Room person_action_item_cache + person_action_mutation_queue
  -> PersonsScreen / ScheduleScreen / CommitmentActionInboxScreen
  -> EvidenceDrillDownSheet when the user asks why
```

## Behavior Trace

| ID | Surface | Target owner | Verification target |
| --- | --- | --- | --- |
| NAP-PERSON-001 | Person list | Railway + `PersonsViewModel` cache reader | primary list reads active `surfaces contains person` rows |
| NAP-PERSON-002 | Person detail | Railway + `PersonDetailViewModel` cache reader | first viewport renders `PersonNextMovePanel` before collapsed history |
| NAP-SCHEDULE-001 | Schedule | Railway + schedule cache reader | today + next 7d attend/prepare/confirm rows |
| NAP-SCHEDULE-002 | Schedule | `ScheduleScreen` | compact agenda rows are secondary to prep/confirm actions |
| NAP-COMMITMENT-001 | Commitment | Railway + `CommitmentActionInboxViewModel` cache reader | active obligations grouped by now/mine/waiting/review |
| NAP-COMMITMENT-002 | Commitment | `/v1/person_action_items/{id}` | backend action mutation updates action row and linked commitment atomically |
| NAP-ACTION-001 | Action lifecycle | Railway action mutation | reminded/followed_up/completed/cancelled transitions |
| NAP-ACTION-002 | Reminder | `ReminderScheduler` + cache | due_at based notification with completed/cancelled suppression |
| NAP-ACTION-003 | Overdue | backend recomputation | commitment first, action rows recomputed second |
| NAP-ACTION-004 | Undo/history | backend action state + mutation queue | history hidden by default, undo calls backend mutation |
| NAP-EVIDENCE-001 | Evidence | `EvidenceDrillDownSheet` | bounded refs only, no source history as first screen |
| NAP-EVIDENCE-002 | Person history | `CollapsedSourceTimeline` | `person_interactions` powers evidence/history only |
| NAP-SYNC-001 | Refresh | foreground catch-up + backend fetch | cached rows stay visible, then changed `/v1/person_action_items` rows apply |
| NAP-SYNC-002 | Source status | source status + backend reconnect action | diagnostic/reconnect action, not a source feed |
| NAP-SYNC-003 | Delta apply | cache repository | rows + deleted_ids applied transactionally before server_watermark advances |
| NAP-SYNC-004 | Retry | cache/outbox workers | retryable errors keep cache, respect Retry-After, and schedule bounded retry |
| NAP-SYNC-005 | Pending context | backend watermarks + UI badge | local pending source scope is shown as pending_context, not complete |
| NAP-REL-001 | People graph | person graph mirror + backend actions | person_id primary, search includes non-primary rows |
| NAP-REL-002 | Person detail | person graph + action cache | next action first, interaction timeline collapsed |

## Drift Gates

These grep checks should fail after the implementation cutover if legacy first
screens are still wired as the product surface:

```bash
grep -R "TodayTimelineScreen" android/app/src/main/java/com/becalm/android/ui/navigation android/app/src/main/java/com/becalm/android/ui/today
grep -R "CommitmentFilterTabs" android/app/src/main/java/com/becalm/android/ui
grep -R "SourceTimelineFilter\\|SourceEventCardRow" android/app/src/main/java/com/becalm/android/ui/persons
grep -R "NextActionProjectionWorker\\|next_action_projections\\|/v1/next_actions" android/app/src/main/java
```

Allowed after cutover:

- raw/source detail screens behind evidence routes
- `person_interactions` projection for collapsed history
- existing commitment edit/detail sheets for canonical source-fact state
- route compatibility for `/today` if it renders the Schedule surface
- Room `person_action_item_cache` and `person_action_mutation_queue` as cache/outbox only

## Reliability Gates

These are required before calling the backend action feed production-ready:

| Gate | Requirement | Proof |
| --- | --- | --- |
| Idempotent recompute | Duplicate source/person triggers create one effective recompute per scope/input_watermark | backend unit + queue integration |
| Worker crash recovery | Claimed recompute job becomes claimable after `locked_until` and completes without data loss | backend integration |
| No missing delta | `changed_since` response includes changed rows, `deleted_ids`, `server_watermark`, `input_watermarks`, and `recompute_state` from one snapshot | API contract test |
| Snapshot pagination | full refresh/delta pagination requires `snapshot_id`; Android advances watermark only after final `has_more=false` page | API + Android cache integration |
| Transactional cache apply | Android advances watermark only after rows and tombstones are applied | Android unit/integration |
| Mutation replay | Duplicate `client_mutation_id` returns the same committed row; changed payload returns 409 | backend API test |
| Mutation ledger | `person_action_mutation_ledger` stores payload hash/result so replay does not depend on latest state row | backend API + DB test |
| Retry contract | 429/503 responses include `retryable` and `retry_after_seconds` when known | API error-envelope test |
| Stale visibility | Backend outage shows stale/offline cache state and never local-generated actions | Android UI test |
| Recovery closure | Every failed/stale/degraded/pending action-feed state maps to auto retry, full refresh, user repair, operator requeue, app update/support, or explicit discard | API + Android state-machine tests |
| Mutation failure recovery | 404/409/422 mutation failures refetch, fix input, retry, or discard; no outbox row remains failed without CTA | Android outbox integration |
| Dead-letter recovery | dead-letter recompute row keeps incident/reason and can be requeued; UI remains degraded with recovery action until caught up | backend worker + ops runbook smoke |
| Operator requeue | `requeue_person_action_recompute_job` moves eligible dead_letter/failed/expired-processing jobs to pending and does not mark feed caught_up directly | backend worker + RPC test |
| Trigger coverage | source graph, commitment, calendar, source status, person memory, review, schedule link, and feedback writes enqueue/coalesce recompute jobs before durable success | backend service tests |
| Deterministic generation | action kind, surface, stable id, score tie-breaks, collapse, and user-state overlay are versioned and deterministic | backend generator golden tests |
| Fresh no-cache UX | first install with empty, disabled, unavailable, or maintenance action feed shows explicit setup/caught-up/retry/support state, not blank UI | Android UI + API error test |
| Worker lane isolation | `person-action-recompute` runs in a dedicated Railway worker queue and is disabled in production web/API background loops by default | backend config + live worker health verifier |
| Atomic concurrent claim | Multiple workers claim shared backlog through `FOR UPDATE SKIP LOCKED` RPC with zero duplicate job ids and zero missing completions | backend queue race integration |
| Stale lease reclaim | Expired processing jobs are reclaimable by a different worker; late older completions cannot overwrite newer watermarks | backend worker integration |
| Latency | cached first paint p95 <= 500ms, delta fetch p95 <= 1500ms on healthy network | performance smoke |
| Backlog drain | 1000 synthetic users with 365-day seeded history drain p95 <= 15 minutes and deploy catch-up p95 <= 10 minutes | backend worker smoke |
| Noisy-user fairness | One user cannot consume more than 20% of rolling 5m recompute claims while other users have eligible jobs | backend load test |
| Retention/full refresh | 90-day tombstones produce `deleted_ids`; older `changed_since` returns 409/full_refresh and Android preserves stale cache until snapshot apply | API + Android delta test |
| Long degraded UX | backlog/degraded >24h shows compact health row, last successful refresh, one CTA, and no repeated blocking modal/endless spinner | Android UI test |
| Privacy | action-feed telemetry and evidence refs contain no raw content, local paths, signed URLs, or raw model output | sanitizer/analytics tests |

## Current Implementation Gap

The current codebase still contains legacy `TodayTimelineScreen`,
`CommitmentManagementScreen`, and person source-timeline components. They are
implementation debt against this target contract, not active product specs.

Required implementation slices:

1. Add backend `person_action_items`, `person_action_item_evidence_refs`, and `person_action_item_user_states`.
2. Add backend `person_action_mutation_ledger` for mutation/feedback idempotency replay.
3. Add backend `person_action_recompute_jobs`, `person_action_feed_watermarks`, and the `claim`/`complete`/`fail`/`requeue` RPCs with lease reclaim.
4. Add dedicated Railway `person-action-recompute` worker lane, queue health, fairness cap, and 1000-user backlog verifier.
5. Add Railway recomputation triggers after source graph, commitment, calendar, source status, person memory, person/review, schedule link, correction, and feedback writes.
6. Add deterministic generator package: candidate collection/filter, action kind precedence, surfaces, stable id, scoring/tie-breaks, collapse, user-state overlay, and action_version migration.
7. Expose `GET /v1/person_action_items`, `PATCH /v1/person_action_items/{id}`, and `POST /v1/person_action_items/{id}:feedback`.
8. Make `GET /v1/person_action_items` snapshot-pagination safe with `snapshot_id`, `snapshot_expires_at`, `changed_since_min`, full-refresh fallback, and final-page watermark advancement.
9. Add Android Room `person_action_item_cache` and `person_action_mutation_queue`.
10. Add transactional delta apply, 90-day tombstone handling, full-refresh fallback, watermark persistence, and mutation replay handling.
11. Add fresh-install no-cache UI for caught-up/setup/sync-pending/feature-disabled/maintenance/endpoint-unavailable states.
12. Add action-feed recovery state machine for retry/full refresh/source repair/mutation discard/dead-letter/operator requeue/long-degraded UI.
13. Replace Person list/detail first viewport with backend cache-backed state.
14. Replace `/today` UI content with `ScheduleScreen` while preserving route compatibility.
15. Replace Commitment tab first viewport with `CommitmentActionInboxScreen`.
16. Move source timelines to evidence/history affordances only.
17. Delete local projection/ranking compatibility residue after the cutover.
