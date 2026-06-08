# HTML Prototype Parity Gap Report - 2026-06-04

Baseline prototype: `CEOhandoffs/becalm-v4-ux-inkmist-soft.html`

Screenshot evidence:
- Baseline/live captures: `becalm-android/android/qa-screenshots/ui-parity-20260604-223521/`
- Ink-mist token captures: `becalm-android/android/qa-screenshots/ui-parity-20260604-inkmist-token/`
- Current device parity captures: `becalm-android/android/qa-screenshots/ui-parity-device-20260605-matching-priority-status/`
- Progressive source preview captures: `becalm-android/android/qa-screenshots/ui-parity-device-20260605-progressive-source-preview/html-prototype-20260604/`
- Progressive source side-by-side: `becalm-android/android/qa-screenshots/ui-parity-device-20260605-progressive-source-preview/prototype-vs-progressive-source-preview.png`
- Progressive source inline captures: `becalm-android/android/qa-screenshots/ui-parity-device-20260605-progressive-source-inline/html-prototype-20260604/`
- Progressive source inline side-by-side: `becalm-android/android/qa-screenshots/ui-parity-device-20260605-progressive-source-inline/prototype-vs-progressive-source-inline.png`
- Person-tab stale recall captures: `becalm-android/android/qa-screenshots/ui-parity-device-20260605-stale-main-list/html-prototype-20260604/`
- Person-tab stale recall side-by-side: `becalm-android/android/qa-screenshots/ui-parity-device-20260605-stale-main-list/prototype-vs-current-contact-sheet.png`
- Post-OAuth sync UI captures: `becalm-android/android/qa-screenshots/ui-parity-device-20260605-post-oauth-sync/html-prototype-20260604/`
- Post-OAuth sync side-by-side: `becalm-android/android/qa-screenshots/ui-parity-device-20260605-post-oauth-sync/prototype-vs-current-contact-sheet.png`
- First aha multi-preview captures: `becalm-android/android/qa-screenshots/ui-parity-device-20260605-first-aha-preview/html-prototype-20260604/`
- First aha side-by-side: `becalm-android/android/qa-screenshots/ui-parity-device-20260605-first-aha-preview/prototype-ob-aha-vs-android-gmail-ready.png`
- Conditional evidence captures: `becalm-android/android/qa-screenshots/ui-parity-device-20260606-conditional-evidence/html-prototype-20260604/`
- Conditional evidence side-by-side and metrics:
  `becalm-android/android/qa-screenshots/ui-parity-device-20260606-conditional-evidence/prototype-vs-current-conditional-evidence-contact-sheet.png`,
  `becalm-android/android/qa-screenshots/ui-parity-device-20260606-conditional-evidence/prototype-vs-current-conditional-evidence-metrics.csv`

## Captured Prototype States

- Onboarding: welcome, identity, people, calendar before/connected, mail before/loading/done, done, scan, first aha.
- Main app: persons list, persons empty search, person detail, stale person detail, evidence sheet, compose sheet, schedule, commitments.

## Captured Android States

- Live app: persons unassigned state, persons empty search, schedule empty, commitments empty, import sheet.
- Instrumented UI fixtures: persons action-first list with source status, stale person detail recall,
  persons action-feed degraded status, schedule candidates/conflict review, open Give/Take commitments,
  commitments with source status, persons/commitments with multiple source warnings,
  persons with simultaneous source and processing warnings, persons with matching/source/processing
  warnings, evidence import sheet, commitment compose sheet, onboarding source setup,
  Gmail activation loading, Gmail activation ready, person-action evidence sheet,
  schedule-action evidence sheet, commitment-detail source evidence sheet, and raw-event
  original context detail.
- OAuth return: browser completion page and app return state.
- Live screenshots are redacted where person/event names appeared.

Initial instrumentation fixture path:
`android/qa-screenshots/ui-parity-20260604-223521/android-fixtures/html-prototype-20260604/`

Current ink-mist token fixture path:
`android/qa-screenshots/ui-parity-20260604-inkmist-token/android-fixtures/html-prototype-20260604/`

Instrumentation proof:
`adb shell am instrument -w -r -e class com.becalm.android.ui.parity.HtmlPrototypeParityScreenshotTest com.becalm.android.test/androidx.test.runner.AndroidJUnitRunner`
returned `OK (9 tests)` on SM-F721N / Android 16.

After the theme-token migration, the same instrumentation command returned
`OK (9 tests)` again after reinstalling the updated debug and androidTest APKs.

After the action-feed readiness UI slice, the same instrumentation command returned
`OK (10 tests)` after reinstalling the updated debug and androidTest APKs. The new
fixture is `persons-action-feed-degraded-status.png`.

After the contextual import-FAB slice, the same instrumentation command returned
`OK (10 tests)` again. The current action-first persons, schedule review, and
open commitments fixtures no longer render the floating add button over urgent
next-action content.

After the compact main-tab chrome slice, the same instrumentation command returned
`OK (10 tests)` again. Main tab fixtures now use a low brand/status row instead
of the full centered Material top app bar, so the first action content starts
closer to the prototype's first viewport.

After the progressive setup density slice, the same manual instrumentation command
returned `OK (10 tests)` after installing the updated debug and androidTest APKs.
The first-source setup fixture now collapses confirmed self identity into a
compact summary, so Gmail/Calendar connection action cards move into the first
viewport instead of being pushed below full identity input fields.

After the progressive source hero slice, the same manual instrumentation command
returned `OK (10 tests)`. The first-source setup fixture now reuses the onboarding
progress indicator and provider icon so the setup, Gmail loading, and Gmail ready
conditional states share the same visual rhythm as the HTML prototype.

After the cross-tab compact source-status slice, the same manual instrumentation
command returned `OK (11 tests)`. The new fixture is
`commitments-open-source-status.png`; a single failed source now renders as the
same compact one-line warning above open commitment actions instead of only
being visible on the Persons tab.

After the multi-source compact source-status slice, the same manual instrumentation
command returned `OK (13 tests)`. The new fixtures are
`persons-multiple-source-status.png` and `commitments-multiple-source-status.png`;
mixed failed/disconnected source states now render as one compact first-line
summary with a source-management entry point instead of stacking source chips or
silently hiding source degradation on action-first tabs.

After the source-plus-processing compact-status slice, the same manual
instrumentation command returned `OK (14 tests)`. The new fixture is
`persons-source-processing-status.png`; when a source warning and backend
person-action readiness delay happen together, Persons now folds both into one
compact first-line warning instead of hiding the action-feed freshness problem
or stacking two separate warning rows above urgent people.

After the matching-priority compact-status slice, the same manual instrumentation
command returned `OK (15 tests)`. The new fixture is
`persons-matching-source-processing-status.png`; when matching review, source
degradation, and person-action processing delay happen together on an action-first
home, matching review now renders as a compact first-line status instead of a full
banner, the source/processing warning remains visible, and the import FAB stays
hidden so it cannot overlap the urgent next-action area.

After the OAuth completion deeplink slice, `AppDeepLinksSpecTest` passed and
`BecalmNavHostTest` returned `OK (20 tests)` on the same connected device. A
direct device launch of
`becalm://oauth-complete?provider=gmail&family=mail&result=success` cold-started
`com.becalm.android/.MainActivity` without `FATAL EXCEPTION`, `AndroidRuntime`,
or `ANR` logcat matches. Query-bearing completion links now route into the
Settings Sources result path so source status refresh is triggered after browser
return even if foreground source-screen state was lost.

After the Settings source hydration slice, `SourcesListViewModelSpecTest`,
`AppDeepLinksSpecTest`, and `SourcesLocalIntegrationTest` passed. The updated
debug APK also cold-started from the OAuth completion deeplink without crash
matches. Settings Sources resume/result refresh now uses
`SourceConnectionLocalStateHydrator`, so a browser return refreshes
`source_connections`, local backend-managed source flags, source-status cache,
and backend mirror refresh scheduling instead of only refreshing the compact
status line.

After the signed-in dev mirror smoke, a temporary Supabase dev QA user called the
live `dev-dev-7309.up.railway.app` backend with a real JWT and UUID
`client_event_id`. `/v1/raw_ingestion_events:batch`, `/v1/raw_ingestion_events`,
`/v1/source_event_participants`, `/v1/commitments`,
`/v1/commitment_participants`, and `/v1/source_connections` all returned 200.
The connected SM-F721N debug app then accepted the same real session through the
debug receiver, cleared the local Gmail mirror rows/cursors for that user, and
WorkManager mirrored the backend row back into Room:
`rawCount=1`, all four mirror cursors present, and no `FATAL EXCEPTION`,
`AndroidRuntime`, or `ANR` logcat match. Synthetic backend rows and the temporary
auth user were removed after proof; device app data was then cleared to remove the
temporary deleted-user session and local QA DB.

After the local-reset person-action live dev smoke, `person_action_live_dev_device_smoke.py`
created a temporary Supabase dev user, wrote a synthetic manual memory through
`POST /v1/manual_memories`, waited for `/v1/person_action_items?surface=person`
to return one caught-up action, installed the current debug APK on SM-F721N,
ran `pm clear com.becalm.android`, injected the same real dev session through
the debug receiver, refreshed Android Room, and opened `becalm://persons`.
The report
`qa/device/reports/person-action-live-dev-20260604-220941/summary.env` passed:
backend feed 200/count 1, Android refresh success with cachedActions 1,
People UI headline/today section/person/action title present, `secret_free_report=true`,
`fatal_anr_oom_count=0`, and temp auth cleanup true. Recent Railway dev logs
for the same window showed `/health` 200, `/v1/manual_memories` 201,
`/v1/person_action_items` 200, and zero `error OR exception OR 503` matches.

After the source-ingestion latency slice, the backend verifier
`scripts/verify_dev_android_api_call_matrix.py` now includes
`/v1/raw_ingestion_events:batch`, `/v1/source_events`, and
`/v1/source_events/{id}` as required coverage. The first raw-ingestion-inclusive
matrix passed 32/32 calls but showed `raw_ingestion_events:batch` at 1353 ms.
Backend initial raw persistence was then changed to upsert only `source_events`
on the response path and defer next-action/person-memory recompute until
extraction persists relation rows. After dev deployment
`16983be5-e489-45eb-adfb-c9af6ddbdbb7`, the same live matrix passed with
`raw_ingestion_events:batch=753 ms`, user-facing max `911 ms`, p95 `869 ms`,
source event detail covered, and zero recent `error OR exception OR 503` dev log
matches.

After the conditional evidence parity slice, the shared action evidence surface was
changed from an alert dialog to the same bottom-sheet shape as the HTML prototype's
`근거 / 왜 이 행동인가요 / 원문 전체` surface. The parity fixture now captures the
conditional person-action, schedule-action, and commitment-source evidence sheets,
plus raw-event original context detail. The combined device run
`PersonDetailScreenTest,CommitmentSheetsTest,HtmlPrototypeParityScreenshotTest`
returned `OK (37 tests)` on SM-F721N, with `HtmlPrototypeParityScreenshotTest`
covering 19 fixture screenshots. The repeatable comparison helper is
`becalm-android/android/scripts/compare_ui_parity_screenshots.py`; it fails on
missing mapped screenshots and writes the contact sheet and CSV metrics listed
above. Pixel-perfect thresholds are not enforced because native Compose and the
browser-rendered HTML differ in status-bar geometry, fonts, and antialiasing.

The prototype's `팔로업 · AI 초안` sheet remains a beta-excluded generation action.
The retained requirement is context foundation only: source originals, evidence
quotes, person memory context, and commitment/source anchors must remain stored
and retrievable so a later draft generator can consume them without making the
beta UI claim that it can generate or send a draft.

## Confirmed Gaps

1. Primary live action-list path now has synthetic dev proof, but real provider data proof is still separate.
   The earlier manual test account landed on a blocking unassigned-record state,
   so fixture-backed screenshots remain useful for stable normal action rows,
   person detail next actions, stale recall, schedule candidates, and open
   commitments. However, the local-reset person-action live dev smoke now proves
   the backend next-action API can repopulate Android Room and render the
   prototype's `지금 챙길 사람` People surface after local app data is wiped.
   Real Gmail/Calendar provider-token data still needs its own end-to-end proof.

2. Fixture-backed action-first rendering exists, and the first viewport is now less crowded.
   The instrumented persons fixture proves backend person action cache can render as the prototype's
   action-first home. This slice reduced the action header/search spacing, compacted the single
   failed-source statusline, and hides the separate source chip strip when that statusline is already
   present. The first action rows now remain in the first viewport with the source warning visible.

3. First-line status surfaces are improved and now include action-feed readiness.
   Prototype uses one compact status line under search. The persons surface now avoids stacking a
   failed-source line plus source chips. It also projects backend person-action sync readiness
   (`pending`, `stale`, `degraded`, `quota_degraded`) into a compact line when sources are otherwise
   healthy, with a processing-status entry point. When source degradation exists at the same time,
   Persons now uses compact action-feed wording inside the source warning line so the user can see
   both that the source snapshot and the next-action freshness are degraded while urgent rows remain
   in the first viewport. When matching review is also required, action-first homes use a compact
   matching line rather than the full review banner so source/processing/matching warnings can
   coexist without hiding the urgent person rows.

4. Schedule fixture confirms the right logical priority.
   The instrumented schedule fixture renders schedule conflict review and calendar-missing candidates above the normal list. This matches the prototype's product intent: future schedule action items beat passive history.

5. Commitments fixture confirms top actions, and legacy rows are now deferred.
   The open Give/Take fixture renders `지금 처리할 일` first. This slice changed the commitments
   screen so legacy confirmed/review/past sections default to collapsed when top actions exist.
   The user can still expand the section header, but non-action history no longer occupies the
   first viewport outside person detail.

6. Korean commitments title drifted from prototype.
   `values-ko/strings.xml` used `약속 관리`, while prototype and bottom navigation use `약속`. This was corrected in this slice.

7. Global visual language token drift has been reduced.
   `Color.kt`, `BecalmColors.kt`, `Shape.kt`, `Dimens.kt`, and the shared
   surface helpers now use the prototype ink-mist palette: white app surfaces,
   navy primary actions, cool grey line/chip colors, muted give/take accents,
   and slimmer 18 dp card / 24 dp sheet radius. Native Material3
   `surfaceContainer*` slots are also pinned so modal sheets no longer inherit
   lavender default surfaces.

8. Main-tab native chrome drift is reduced.
   Android no longer uses the full centered Material top app bar on the three
   main action-first tabs. The main tabs now render a lower brand/status row
   with a quieter Settings action, which moves urgent content closer to the
   prototype's first viewport. The prototype phone frame still uses a pure
   statusbar brand line, so residual difference remains around the Settings
   affordance and exact statusbar geometry. This slice also keeps the import FAB
   hidden when action-first rows, schedule conflict/candidate review, or open
   commitment top actions are present, while preserving the import sheet on
   empty/search/import-focused states.

9. OAuth completion deeplink return is now explicit and crash-free in Android.
   Gmail OAuth completed in the browser and showed an `앱으로 돌아가기` action. The
   app previously relied on foreground source-screen lifecycle refresh, so
   process death or a cold return could open the app without guaranteeing source
   status refresh. Query-bearing `becalm://oauth-complete?...` links now route
   to the Settings Sources result path, preserve `result`, `provider`, and
   `family` as navigation arguments, and trigger the same connection hydration
   used at authenticated startup. That closes the Settings-specific gap where
   the list status could refresh while the local source connection mirror,
   backend-managed flags, and mirror WorkManager scheduling stayed stale. Live
   provider OAuth still needs real-account provider-token proof when the user
   wants to validate Google consent end to end, but the signed-in Android Room
   mirror path has now been proven with live dev backend calls and a real dev JWT.

10. Progressive setup source density is reduced.
   In setup/onboarding entry points, the full identity form still appears until
   identity is confirmed. Once confirmed, the progressive source-first screen no
   longer renders a separate identity card, keeping the source-ingestion decision
   surface closer to the prototype's single-source first viewport. The same
   screen now also renders the onboarding progress rail, provider icon,
   prototype-like mail promise preview card, Gmail connect CTA, and inline
   add-later note in the first viewport.

11. Source warning is now cross-tab for single and mixed source degradation.
   Persons and Commitments share the same compact source-attention policy. A
   single failed source still renders a direct retry line; multiple failed,
   disconnected, or mixed source states collapse into one first-line summary with
   a source-management entry point. Open commitments keep `지금 처리할 일` first,
   but Gmail/Calendar degradation is now visible on that tab too. This reduces a
   real logical gap where source truth could be degraded while the commitments
   surface stayed silent or the persons surface stacked duplicate source chips.

12. Source-plus-processing degradation no longer hides next-action freshness.
   Persons previously suppressed the action-feed readiness line whenever a source
   warning was present. The new compact source line accepts a short processing
   freshness suffix, so a user can see `다음 행동 지연`, lag minutes, and the last
   snapshot basis in the same first-line warning without pushing the action list
   down.

13. Matching-required review no longer takes over action-first homes.
   When person action rows exist, unassigned interaction review now uses a compact
   matching line above the source/processing line. The full matching banner still
   remains for unassigned-only blocking states, but it no longer crowds out urgent
   next actions when the app already has people to act on. The evidence import FAB
   is also hidden in this compact matching state so it cannot overlap the bottom
   action/navigation area.

14. Person-tab stale recall now appears in the first action list.
   The prototype keeps most history hidden but exposes one old-contact recall row
   on the People tab. The Android action-first fixture now includes the same
   `다시 이어갈 때` section after today's and this week's next actions, with
   `24일째 연락 없음 — 복기하고 재개하기` as a person-level recall prompt. This
   keeps stale history out of Schedule/Commitments while preserving the only
   history surface the product goal explicitly allows.

15. Backend raw ingestion invalid-client-id hardening is closed in dev.
   During the signed-in mirror smoke, a deliberately non-UUID synthetic
   `client_event_id` reproduced a live backend 503 from Supabase UUID parsing.
   Android-generated raw events use UUIDs, and the UUID smoke passed, so this is
   not blocking the current Android path. A narrow backend hardening slice now
   maps that UUID-cast failure to sanitized 422 `client_event_id_invalid`,
   `retryable=false`, `client_action=fix_payload`, with no DB detail in the
   public message. After dev deployment, a live temporary dev JWT smoke confirmed
   the invalid path returns 422 and the normal UUID path still returns 200.

16. Source-ingestion latency is now inside the 1s non-LLM budget in live dev.
   The raw ingestion endpoint used to do source graph persistence plus immediate
   next-action/person-memory recompute enqueue before responding, even when no
   extracted relationship rows existed yet. Initial raw ingest now only writes
   the source event and processing queue on the response path; extraction later
   runs the full source graph persistence and recompute enqueue once relation
   rows or commitments exist. This keeps source ingestion as the source of truth
   while removing unnecessary pre-extraction engine work from the user-facing
   API call.

17. Person action evidence-original proof is now closed across web and worker services.
   The Android action sheet already calls
   `/v1/person_action_items/{action}/evidence/{kind}/{id}`, but live dev proved
   a real gap that the prototype could not show: action rows were generated while
   `person_action_item_evidence_refs` stayed empty, so the original evidence
   sheet had no durable source to open. The backend verifier now requires the
   evidence-original endpoint to be called and pass, not just action visibility.
   The fix was deployed to both `dev` and `person-action-recompute-worker`,
   because the recompute worker owns evidence ref publication. Completion now
   publishes evidence refs based on the committed action row `input_watermark`
   instead of feed-watermark string equality, and RPC single-row list responses
   are handled on both recompute completion and action mutation paths. Live dev
   proof after worker deployment showed DB evidence refs present, feed
   `evidence_refs` present, and the evidence-original endpoint returning 200.
   Final matrix report
   `becalm-backend/reports/android-api-call-matrix-evidence-original-after-mutation-rpc-fix.json`
   passed 34/34 calls with `evidence_checked=true`, user-facing p95 `789 ms`,
   max `827 ms`, raw ingestion `735 ms`, action mutation `477 ms`, idempotent
   replay `466 ms`, and temp-auth cleanup complete.

18. Vertex image extraction now persists durable commitments for next actions.
   Live dev Vertex proof exposed a real source-of-truth gap: the extraction
   endpoint returned `items` and marked the `source_events` row `extracted`, but
   those LLM items were not written to `commitments`, so no urgent future action
   could be generated from the screenshot source. The backend now projects
   image/audio extraction `items` into deterministic source-scoped commitment
   rows, writes them through the existing source graph persistence contract, and
   keeps the same replace-source-event cleanup and recompute enqueue semantics
   used by provider ingestion. The fix was deployed to both `dev` and
   `extraction-worker`, because durable async extraction jobs use the same
   persistence path. A synthetic non-PII screenshot live proof on
   `dev-dev-7309.up.railway.app` returned 200 from Vertex with 2 extracted items,
   then showed 1 `source_events` row (`extraction_status=extracted`,
   `extracted_count=2`), 2 `commitments` rows, 1 source participant, and the
   commitment-surface action feed visible with 2 matched actions by poll 2.
   The LLM call took `12610 ms` and is outside the non-LLM 1s target; the
   subsequent action-feed response reported backend `server_timing_ms.total`
   around `138 ms`. The post-deploy Android API matrix passed 33/33 calls with
   issues `[]`, p95 `911 ms`, and cleanup complete. A focused 20-call action-feed
   latency check then showed client max `831 ms`, client p95 `807 ms`, backend
   server max `165 ms`, and no issues.

19. OAuth `needs_reauth` no longer enters source-sync work queues or stuck loading.
   Android and backend already displayed `needs_reauth` as a source error, but a
   real flow gap remained: manual sync/background sync could still treat any
   non-`disconnected` backend connection as syncable, and the backend direct sync
   endpoint only rejected `disconnected`. That allowed a stale Gmail/Calendar
   connection to create a source-sync job and fail later at provider fetch,
   which is the kind of delayed failure the HTML prototype cannot expose. The
   backend now rejects `source_connections/{id}:sync` and activation preview
   immediately for `needs_reauth` with 409, `retryable=false`, and
   `client_action=reconnect_source`; no durable sync job is queued. Android now
   treats only `connected`, `syncing`, `synced`, and retryable `failed` backend
   connections as syncable, while preserving `needs_reauth` as the local error
   message instead of overwriting it with a generic missing-connection error.
   Live dev proof on `dev-dev-7309.up.railway.app` created a temporary auth user
   and synthetic Gmail connection in `needs_reauth`, then confirmed sync returned
   409 `source_connection_needs_reauth`, `client_action=reconnect_source`,
   source status projected Gmail as `connection_state=needs_reauth`/`state=error`,
   `source_sync_jobs_count=0`, and cleanup removed the temp user and connection.

20. Settings OAuth success now triggers one immediate backend source sync.
   A completed browser callback proves token/connection persistence, not provider
   fetch, source-event persistence, local mirror refresh, or next-action projection.
   The Settings Sources completion route already hydrated source connections and
   status, but it could still wait for a later periodic backend worker before the
   newly connected Gmail/Calendar source was fetched. `SourcesListViewModel` now
   handles query-bearing success links by hydrating source state and then calling
   `SourceSyncPort.requestManualSync(...)` once for Gmail, Outlook Mail, Google
   Calendar, or Outlook Calendar. Error completion links still refresh state but
   do not request provider fetch. `SourcesListViewModelSpecTest`,
   `SourcesLocalIntegrationTest`, and `AppDeepLinksSpecTest` passed after the
   slice, and `:app:compileDebugKotlin` passed.

21. OAuth-start device smoke is now dev-default and still passes after the
    Settings completion sync slice. The destructive auth/OAuth script keeps its
    legacy filename and compatibility summary key, but `--confirm-dev-login` is
    now the primary confirmation flag and the backend verifier defaults to
    Railway `dev`. The latest SM-F721N report
    `qa/device/reports/staging-auth-oauth-device-20260605-113525/summary.env`
    passed with `dev_auth_oauth_device_smoke_result=passed`,
    `fresh_local_data=true`, `login_succeeded=true`,
    `oauth_start_accounts_google=true`, `fatal_anr_oom_count=0`, and
    `secret_log_count=0`. The browser UI dump shows Samsung Internet on
    `accounts.google.com`, not Naver or a disallowed user-agent. Consent was not
    performed in that smoke, so `oauth_callback_returned_to_app`,
    `gmail_connection_confirmed_after_consent`, and `backend_gmail_sync_succeeded`
    correctly remain false until a tester completes Google consent.

22. Post-OAuth sync feedback now matches the real work being started.
    Settings OAuth completion now requests immediate backend source sync, so the
    completion snackbar was updated from plain account-connected copy to
    `계정이 연결됐습니다. 새 기록 확인을 시작했습니다.`. This keeps the UI from
    implying that connection persistence is the whole workflow and tells the
    user that provider fetch/source-ingestion work has begun. After the resource
    update, `SourcesListViewModelSpecTest` and `AppDeepLinksSpecTest` passed,
    `:app:assembleDebug` passed, and `HtmlPrototypeParityScreenshotTest` returned
    `OK (15 tests)` on SM-F721N / Android 16. The 15 current captures were pulled
    into `android/qa-screenshots/ui-parity-device-20260605-post-oauth-sync/`;
    logcat did not show BeCalm `FATAL EXCEPTION`, `ANR`, or crash matches.

23. Backend Android-facing runtime warmups now close the 1s non-LLM outliers.
    The post-OAuth matrix initially passed coverage but exposed real user-facing
    outliers in `manual_memory_create_shared_schedule` and `source_connections`.
    The backend now warms Supabase JWKS verification and the Supabase REST client
    during FastAPI startup, and the schedule-link sentinel missing-id path
    fast-fails without a DB round trip. After dev deployment, live matrix
    `becalm-backend/reports/dev_android_api_call_matrix_runtime_warmups_20260605-120707.json`
    passed 52/52 calls with `issues=[]`, `successful_calls=52`,
    `recoverable_error_surfaces=true`, raw source ingestion/detail/evidence
    coverage true, temp Auth cleanup true, user-facing max `711 ms`, and p95
    `620 ms`. This keeps the backend readiness baseline ahead of the UI polish
    work and preserves the rule that LLM extraction latency is outside the
    non-LLM 1s target.

24. OAuth consent proof now uses the same test account on device and backend.
    The destructive dev auth/OAuth smoke previously signed into Android with the
    configured `EMAIL_ENV`/`PASSWORD_ENV` but could run the backend verifier with
    the runner defaults. That made a future consent proof weaker because the
    device user and verifier user could diverge. The smoke now passes the same
    email/password env names into `scripts/run_with_staging_user_jwt.py`, uses
    `BECALM_DEV_JWT` for dev and keeps `BECALM_STAGING_JWT` only for explicit
    staging compatibility. It also records `backend_sync_jwt_env` in
    `summary.env`. `bash -n`, embedded Python compile, Android
    `StagingAuthOauthDeviceSmokeScriptSpecTest`, backend
    `test_run_with_staging_user_jwt.py`, and
    `test_verify_staging_oauth_source_sync.py` passed after this change.
    The attempted SM-F721N consent smoke
    `qa/device/reports/staging-auth-oauth-device-20260605-121836/summary.env`
    reached dev login and `accounts.google.com` in Samsung Internet with
    `fatal_anr_oom_count=0`, `secret_log_count=0`, and
    `backend_sync_jwt_env=BECALM_DEV_JWT`, but timed out waiting for user consent
    (`last_foreground_package_at_timeout=com.sec.android.app.sbrowser`). Follow-up
    same-account backend verifier runs for Gmail and Google Calendar both
    returned immediate 409 `source_connection_needs_reauth` with
    `client_action=reconnect_source` and no queued sync job:
    `becalm-backend/reports/oauth-source-sync-dev-mail-after-consent-timeout-20260605-122813.json`
    and
    `becalm-backend/reports/oauth-source-sync-dev-calendar-after-consent-timeout-20260605-122828.json`.

25. OAuth-start device smoke is now source-selectable for Gmail and Google Calendar.
    The smoke now accepts `--oauth-source gmail` or
    `--oauth-source google-calendar`, normalizes each source to the matching
    Settings detail route, and records the backend verifier capability as
    `mail` or `calendar`. Consent-mode runs and all Google Calendar target runs
    no longer pre-seed the selected source with a fake success callback before
    opening the provider flow. This closes the proof gap where Gmail could reach
    Google OAuth but Calendar still only had mock-style route coverage. The
    SM-F721N Calendar run
    `qa/device/reports/staging-auth-oauth-device-20260605-124157/summary.env`
    passed with `oauth_source=google_calendar`,
    `backend_sync_capability=calendar`, `oauth_start_accounts_google=true`,
    `fatal_anr_oom_count=0`, `secret_log_count=0`, and `issues=`. No user
    consent or backend sync verifier was requested in that run, so
    `backend_google_calendar_sync_succeeded=false` remains the correct state
    until a tester completes Google consent.

26. OAuth browser launch is defended against disallowed in-app browsers.
    Android now routes mail and calendar authorization URLs through
    `OAuthBrowserLauncher`, which rejects non-HTTPS URLs and selects only known
    trusted browser packages, preferring Custom Tabs-capable packages when
    available. The policy test explicitly rejects `com.nhn.android.search`, so
    the earlier `403: disallowed_useragent` Naver in-app browser failure is
    covered as an Android-side launcher bug, not a provider-token or test-user
    issue. The updated Gmail SM-F721N run
    `qa/device/reports/staging-auth-oauth-device-20260605-124849/summary.env`
    passed with `oauth_source=gmail`, `backend_sync_capability=mail`,
    `oauth_start_accounts_google=true`, `fatal_anr_oom_count=0`,
    `secret_log_count=0`, and `issues=`. Logcat showed Gmail OAuth launching
    `package=com.sec.android.app.sbrowser`; no `disallowed_useragent` or `403`
    match was present. The targeted unit gate
    `OAuthBrowserLauncherSpecTest`, `EmailOAuthConnectorSpecTest`,
    `CalendarOAuthConnectorSpecTest`, and
    `StagingAuthOauthDeviceSmokeScriptSpecTest` also passed.

27. Consent-ready backend proof now separates provider sync from next-action readiness.
    The post-consent branch of `staging_auth_oauth_device_smoke.sh` now calls
    `verify_staging_oauth_source_sync.py` with `--summary-json` and records the
    verifier JSON inside the device report. `summary.env` now includes
    `backend_downstream_evidence_checked`, `backend_source_events_observed`,
    `backend_source_event_participants_observed`,
    `backend_person_action_feed_ready`, source/action counts, and evidence-ref
    counts. This reduces the final proof risk: after real Gmail or Calendar
    consent, a passing smoke no longer means only "provider job succeeded"; it
    also exposes whether source events and person-action projection were visible
    through the Android-facing backend APIs. The non-consent SM-F721N regression
    run `qa/device/reports/staging-auth-oauth-device-20260605-125607/summary.env`
    passed with `oauth_source=gmail`, `oauth_start_accounts_google=true`,
    `backend_downstream_evidence_checked=false`, the new backend evidence counts
    at `0`, `fatal_anr_oom_count=0`, `secret_log_count=0`, and `issues=`.

28. Consent-ready Android mirror proof now separates local recovery from backend sync.
    The same smoke now has an optional
    `--verify-android-mirror-after-backend-sync` branch that only runs after the
    backend verifier succeeds. It uses the debug receiver to clear the selected
    local mirror rows, refresh Android Room from the backend, record Gmail raw
    row or Google Calendar event counts, refresh the person-action cache, and
    check the People UI when backend person actions exist. The non-consent
    SM-F721N regression run
    `qa/device/reports/staging-auth-oauth-device-20260605-130611/summary.env`
    passed with `oauth_source=gmail`, `oauth_start_accounts_google=true`,
    `android_mirror_after_backend_requested=false`, all Android mirror counts at
    `0`, `fatal_anr_oom_count=0`, `secret_log_count=0`, and `issues=`. This does
    not prove provider-derived local recovery yet; it proves the proof path is
    safe when consent is not completed and ready to run after real consent. The
    backend verifier now also exposes `/v1/calendar_events` evidence for Calendar
    consent runs, so the Android mirror branch cannot pass Calendar provider
    proof solely on the generic source-events API while the actual
    Android-facing calendar mirror remains unproven. It also reports whether the
    selected connection's source-event id is present in the calendar endpoint,
    preventing stale unrelated calendar rows from satisfying the proof.

After the progressive source preview parity slice, the same device instrumentation
command returned `OK (15 tests)` after installing the updated debug and
androidTest APKs on SM-F721N / Android 16. The new capture set is
`android/qa-screenshots/ui-parity-device-20260605-progressive-source-preview/html-prototype-20260604/`;
`onboarding-source-connections-first-source.png` now renders the prototype-like
mail promise example, Gmail provider row, and primary connect CTA instead of a
generic first-source list.

After the progressive source inline parity slice, `OnboardingCheckpoint1E2eTest`
returned `OK (6 tests)` and `HtmlPrototypeParityScreenshotTest` returned
`OK (15 tests)` on the same SM-F721N device. The capture set is
`android/qa-screenshots/ui-parity-device-20260605-progressive-source-inline/html-prototype-20260604/`;
the first-source setup fixture no longer shows the confirmed self-identity card,
and the add-later copy is now an inline note below the Gmail card rather than a
separate card that pushes the primary source story down.

After the person-tab stale recall parity slice,
`./gradlew testDebugUnitTest --tests com.becalm.android.integration.local.ui.persons.PersonsUiTest`
passed and `HtmlPrototypeParityScreenshotTest` returned `OK (15 tests)` on
SM-F721N / Android 16. The new capture set is
`android/qa-screenshots/ui-parity-device-20260605-stale-main-list/html-prototype-20260604/`;
the action-first Persons fixture now includes the prototype's `다시 이어갈 때`
row with an old-contact recall prompt, while keeping history off the other main
action tabs.

After the first aha multi-preview slice,
`./gradlew testDebugUnitTest --tests com.becalm.android.integration.local.ui.onboarding.OnboardingUiTest --tests com.becalm.android.unit.ui.onboarding.OnboardingViewModelSpecTest`
passed, `./gradlew assembleDebug assembleDebugAndroidTest lintDebug` passed, and
`HtmlPrototypeParityScreenshotTest` returned `OK (15 tests)` on SM-F721N /
Android 16. The updated capture set is
`android/qa-screenshots/ui-parity-device-20260605-first-aha-preview/html-prototype-20260604/`;
`onboarding-gmail-activation-ready.png` now shows three source-derived future
next-action previews plus the real `BeCalm 시작하기` transition. Android's local
activation preview limit is now three items, matching the prototype's first aha
shape without changing the backend API contract. The side-by-side evidence is
`android/qa-screenshots/ui-parity-device-20260605-first-aha-preview/prototype-ob-aha-vs-android-gmail-ready.png`.

## Next UI Slice

- Keep the new instrumentation screenshot fixture for:
  persons action list, stale person detail, schedule candidates, open commitments.
- Keep deterministic screenshots for evidence sheet, compose sheet, and onboarding conditional states
  in the parity fixture suite.
- Keep compact first-line status policy covered for source degradation,
  source-plus-processing, and matching-plus-source-plus-processing combinations.
  The remaining status work is mainly real provider-token OAuth proof and any
  backend-originated status combination that is not represented in the fixture
  suite yet.
- Keep the Settings OAuth completion path covered as a real backend sync trigger:
  success should request exactly one immediate source sync for the returned
  backend-managed provider, while error/cancel paths should only refresh
  recoverable source status.
- Complete real provider-token proof for Gmail and Google Calendar separately:
  user consent must return to BeCalm, the selected source must become connected,
  the same-account backend verifier must observe provider sync success, and the
  resulting source events/person actions must survive local DB reset/mirroring.
  The device smoke now records both backend downstream source/person-action
  evidence and Android local mirror/person-action UI readiness after consent; the
  still-missing proof is a real Gmail consent run and a real Google Calendar
  consent run with both verifier branches enabled.
- Keep commitment legacy sections collapsed by default when top actions exist; extend the same
  rule if new non-person history surfaces appear outside person detail.
- Align remaining statusbar/settings affordance details and title/eyebrow/tag typography
  against the prototype screenshots now that global color/radius tokens are in
  the ink-mist family.
- Keep the first aha preview path covered with three future next-action items.
  Remaining first-aha work is no longer basic visibility; it is the real
  correction contract for dismissing or accepting individual suggestions and
  the live provider-token proof that those three items are produced from actual
  Gmail/Calendar consent data.
