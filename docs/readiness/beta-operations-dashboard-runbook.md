# Beta 운영 Dashboard Runbook

날짜: 2026-05-16 KST

범위: 10명 / 5일 controlled beta. 목표는 완벽한 자동화를 증명하는 것이 아니라,
사용자가 실패했을 때 팀이 실패와 원인을 같은 날 볼 수 있게 하는 것이다.

## Day-one Dashboard

Supabase SQL editor 또는 같은 권한을 가진 BI 도구에서 아래 view를 본다.
View는 `security_invoker = true`로 생성되어 RLS를 따른다. 전체 beta 운영 집계는
service role 또는 운영자용 읽기 권한으로 조회한다.

### 1. Source / OAuth / Extraction Funnel

```sql
select *
from public.beta_dashboard_product_events_24h
order by last_occurred_at desc, event_count desc;
```

반드시 보는 event:

- `source_oauth_started`
- `source_oauth_browser_opened`
- `source_oauth_status_checked`
- `source_status_refreshed`
- `source_status_refresh_failed`
- `source_sync_started`
- `source_sync_completed`
- `source_sync_failed`
- `extraction_started`
- `extraction_completed`
- `extraction_failed`

운영 판단:

- `source_oauth_started`는 있는데 `source_oauth_status_checked`가 없으면 앱 복귀 또는
  status refresh 경로를 의심한다.
- `source_oauth_status_checked` 결과가 `not_connected`, `network_error`,
  `oauth_status_empty`에 쏠리면 provider callback/status endpoint를 확인한다.
- `extraction_started` 대비 `extraction_completed`가 낮고 `extraction_failed`가 높으면
  source type, input modality, result별로 장애를 분리한다.

### 2. Source Health

```sql
select *
from public.beta_dashboard_source_health
where needs_attention = true
order by updated_at desc;
```

운영 판단:

- `failed`, `needs_reauth`는 tester에게 reconnect 안내가 필요하다.
- `connected` 또는 `synced`인데 `last_sync_at`이 없거나 24시간 이상 오래되면 worker,
  backend sync, provider token 상태를 확인한다.

### 3. Unresolved / Suggested Self Participants

```sql
select *
from public.beta_dashboard_unresolved_participants
order by participant_count desc, newest_created_at desc;
```

운영 판단:

- `relation_to_self = unknown` 또는 `resolution_status = suggested_self`가 많으면
  self identity/person matching 품질 이슈다.
- Gmail, Google Calendar, meeting에서 unresolved가 집중되면 해당 source의 identity
  anchor 또는 speaker/self mapping fixture를 추가한다.

### 4. PMF Summary

```sql
select *
from public.beta_dashboard_pmf_summary
order by last_occurred_at desc;
```

운영 판단:

- 5일 beta 종료 시 `very_disappointed` 비율을 본다.
- free-text 원문은 PII 가능성이 있으므로 dashboard 기본 view에는 포함하지 않는다.

## Triage 기준

Blocker:

- wrong person, wrong self/counterparty, wrong give/take direction이 반복된다.
- private source leakage 또는 PII가 telemetry/log로 보인다.
- required source의 OAuth 성공 후 앱 복귀/connected 상태 수렴이 실패한다.
- extraction failure가 특정 required source에서 30% 이상 지속된다.

High:

- source sync failure가 하루 이상 남아 있다.
- unresolved participant가 tester당 하루 5개 이상 쌓이고 review로 복구되지 않는다.
- PMF 응답이 `not_disappointed`에 쏠리거나 핵심 benefit이 비어 있다.

Medium:

- optional source의 provider-specific failure.
- low-confidence false positive extraction.
- duplicate sync event 또는 retry 후 성공하는 transient failure.

## Daily 운영 루틴

매일 오전:

1. source health에서 `needs_attention = true` row를 확인한다.
2. 전날 24시간 funnel에서 OAuth/status/sync/extraction failure를 source별로 본다.
3. unresolved participants를 source별로 보고 self/person matching fixture 후보를 기록한다.
4. Crashlytics/Sentry에서 fatal, ANR, OOM을 확인한다.
5. tester report channel의 사용자 제보와 dashboard event를 연결한다.

매일 오후:

1. required source별 실제 기기 QA smoke를 1회 실행한다.
2. reconnect, retry, manual/self match repair flow가 작동하는지 확인한다.
3. blocker/high issue는 beta scope 축소 또는 hotfix 후보로 분류한다.

## Beta 종료 Report

5일 종료 시 최소 아래 숫자를 남긴다.

- active testers
- required source별 OAuth start/status connected rate
- required source별 sync success/failure count
- extraction started/completed/failed count
- unresolved/suggested self participant count
- manual/self match count
- PMF disappointment distribution
- crash-free users와 fatal/ANR/OOM count

## 아직 외부 도구가 필요한 항목

- Crash-free users, ANR, OOM은 Firebase Crashlytics 또는 Sentry dashboard에서 본다.
- Play Console internal testing install/update/rollback 상태는 Play Console에서 본다.
- Supabase view는 product telemetry와 source graph만 다루며 crash dashboard를 대체하지 않는다.
