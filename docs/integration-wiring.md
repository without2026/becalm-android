# BeCalm Android MVP — Integration Wiring

이 문서는 unit test용 API 명세가 아니라, integration test 설계를 위한 **동작 wiring 지도**다.
기준 boundary:

- `Room = source-of-truth`
- `Supabase = Room mirror`
- `server → Room` 역방향 sync 없음
- voice는 `draft extraction only`
- full transcript 저장 없음

테스트 목적은 개별 함수 correctness보다 다음 체인을 검증하는 것이다:

`trigger -> local write -> worker/scheduler -> remote call -> state transition -> UI surface`

---

## 1. Onboarding To Today

| Step | Wiring |
|---|---|
| Trigger | 첫 실행, `onboarding_completed=false` |
| Local write | `terms_accepted`, source consent flags, OAuth token/credential, optional recording folder URI |
| Background | `ColdSyncScreen` 진입 후 `COLD-001` Stage 1 병렬 실행 |
| Remote | source API pull, optional mirror flush |
| Success state | `onboarding_completed=true`, Stage 1 complete 또는 `[나중에 하기]` |
| UI surface | `TodayTimelineScreen` 진입, Stage 2 배너는 non-blocking |

Integration test assertions:

- PIPA 동의 거부 시 `RecordingFolderScreen`이 스킵된다.
- `onboarding_completed=true`는 cold sync 이전이 아니라 `COLD-003` 또는 `COLD-006` 시점에만 기록된다.
- Stage 1 성공 후 `TodayTimelineScreen`은 Room 데이터만으로 렌더된다.

---

## 2. Voice Ingest

| Step | Wiring |
|---|---|
| Trigger | Samsung `Recordings/` 하위 새 `.m4a` 감지 또는 cold sync stage 2 media scan |
| Local write | `raw_ingestion_events INSERT(source_type, timestamp, duration, person_ref?, sync_status)` |
| Background | `VoiceUploadWorker` |
| Remote | `POST /v1/voice/transcribe_extract` |
| Success state | Android가 `commitments INSERT`, `commitments_extracted_count UPDATE`, `event_snippet=first quote` |
| Mirror | 이후 raw/commitment mirror worker가 Railway batch write |
| UI surface | PersonDetail / Source viewer / Commitment list에 quote 기반 recall 표시 |

Integration test assertions:

- full transcript는 Room/Railway/Supabase 어디에도 저장되지 않는다.
- `quote`는 extracted source에서는 `verbatim <=100 chars`.
- extraction 실패가 raw mirror를 롤백하지 않는다.
- consent가 false면 `sync_status='awaiting_consent'`로 머문다.

---

## 3. Email Ingest

| Step | Wiring |
|---|---|
| Trigger | Gmail / Outlook / IMAP periodic sync or foreground catch-up |
| Local write | `EmailBody INSERT`, `raw_ingestion_events INSERT(event_snippet, folder, person_ref)` |
| Background | extraction worker if snippet/body available |
| Remote | raw mirror batch, commitment PATCH/POST batch |
| Success state | Room raw/email 유지, extracted commitments는 Room에 먼저 저장 |
| UI surface | Source viewer에서 email body 로컬 렌더 |

Integration test assertions:

- `EmailBody`는 절대 remote payload에 포함되지 않는다.
- subject-only mail은 raw만 저장되고 extraction skip 된다.
- retention sweep는 `sync_status='synced'` + 30일 경과 row만 삭제한다.

---

## 4. Calendar Ingest

| Step | Wiring |
|---|---|
| Trigger | calendar periodic sync or foreground catch-up |
| Local write | `calendar_events INSERT/UPSERT`, 필요 시 raw event metadata update |
| Background | upload flush |
| Remote | raw/calendar mirror |
| Success state | cancelled event는 `DELETE`가 아니라 `status='cancelled'` |
| UI surface | Today timeline strike-through event |

Integration test assertions:

- today timeline은 Room `calendar_events` 쿼리만 사용한다.
- cancelled event도 타임라인에서 사라지지 않고 dim 처리된다.

---

## 5. Commitment Mutation

| Step | Wiring |
|---|---|
| Trigger | 완료 / 취소 / 편집 / 수동 추가 / supersede |
| Local write | Room optimistic update 또는 insert |
| Background | pending sync queue / upload worker |
| Remote | `PATCH /v1/commitments/{id}` or `POST /v1/commitments` |
| Success state | `sync_status='synced'` |
| Failure state | local optimistic state 유지 + 재시도 |
| UI surface | Commitment list/detail 즉시 반영 |

Integration test assertions:

- mutation 직후 UI는 서버 응답을 기다리지 않고 Room state로 갱신된다.
- quote edit는 불가하고 `quote_disputed`만 가능하다.
- manual commitment는 raw event를 만들지 않는다.

---

## 6. Source Disconnect / Reconnect

| Step | Wiring |
|---|---|
| Trigger | Settings > Source detail |
| Local write | cursor clear, credential/token clear |
| No delete | 기존 Room data 유지 |
| Reconnect | OAuth / IMAP / SAF 재선택 |
| UI surface | source chip `idle/error/disconnected`, 기존 데이터는 read-only 열람 가능 |

Integration test assertions:

- disconnect 후 기존 raw/email/commitment 데이터는 계속 보인다.
- reconnect 전에는 신규 collection만 중단된다.
- recovery CTA는 `[다시 연결]` 또는 `[지금 동기화]`만 사용한다.

---

## 7. Logout / Re-login / Account Switch

| Step | Wiring |
|---|---|
| Trigger | routine logout |
| Local write | auth token clear, current DB close |
| Preserve | user-scoped Room DB file, user-scoped DataStore keys |
| Re-login same user | same DB reopen |
| Login other user | different DB file open |
| UI surface | logout 후 `LoginScreen` 복귀 |

Integration test assertions:

- logout은 데이터 삭제가 아니다.
- same-user relogin 시 기존 data가 즉시 복원된다.
- different-user login 시 이전 계정 데이터가 보이지 않는다.

---

## 8. Processing Pause

| Step | Wiring |
|---|---|
| Trigger | PIPA 권리 > 데이터 처리 일시중단 |
| Local write | `processing_paused=true` |
| Worker behavior | periodic worker early return |
| Observer behavior | observer는 등록 유지, queue insert는 중단 |
| Resume | `processing_paused=false` + `ING-011` catch-up |

Integration test assertions:

- pause 중에는 신규 collection/mirror가 진행되지 않는다.
- resume 시 누락 구간이 foreground catch-up으로 복구된다.

---

## Test Design Guidance

이 문서는 다음 테스트에 직접 연결된다.

- UI integration test: screen action 후 Room/UI state 변화 확인
- repository/worker integration test: fake DAO + fake API + fake WorkManager
- end-to-end instrumentation smoke: onboarding, voice ingest, logout/account-switch

반대로 pure unit test에는 직접 효용이 낮다.
unit test는 함수 입력/출력, parser, mapper, validator에 집중하고,
이 문서는 **경계 사이 wiring**을 검증하는 테스트를 설계할 때 쓰는 게 맞다.
