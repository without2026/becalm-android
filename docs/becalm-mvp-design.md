# BeCalm Android MVP — Design v2 (zero-to-deploy handoff)

**Status**: CTO-approved (v2). 다음 단계 = `.pipeline/platform.yml` 재작성 + zero-to-deploy `L0_BRIEFING` 진입.
**Date**: 2026-04-15
**Approver**: jakekang28

---

## 1. Product Core

- **앱 이름**: BeCalm
- **한 줄 설명**: 전화·오프라인 미팅·이메일·캘린더 4개 소스에서 give/take 업무 약속을 자동 수집·구조화해 Supabase에 손실 없이 적재하는 Android 앱.
- **타겟 유저**: 한국 B2B 비즈니스맨, **삼성 Android 전용 MVP**, 한국어만.
- **핵심 문제**: 타인과의 신뢰 기반 업무 약속이 4개 소스에 흩어져 있어 누구에게 뭘 약속했고 / 받기로 했는지 추적 불가.
- **Give/Take 정의**: Give = 내가 상대에게 해주기로 한 것 / Take = 상대가 나에게 해주기로 한 것 (IOU 모델).

## 2. MVP 성공 기준

**수집 이벤트 100% Supabase 도달 (raw 손실 0)**

- LLM 추출 품질은 MVP 게이트 아님.
- `raw_ingestion_events` 테이블 기준으로 도달률 측정.

## 3. MVP 스코프 (포함 / 제외)

### 포함
- 4개 소스 수집 + 구조화 + Supabase 적재
- 온보딩 UI (핵심)
- 메인 대시보드 = 통합 오늘 뷰 (캘린더 이벤트 + 오늘 due인 commitment)
- 사이드탭: 원문보기 (날짜별 미팅/전화 transcript + 이메일 원문)
- 사이드탭: 설정 (소스 재연결, 로그아웃)
- 이용약관 동의 화면

### 제외 (Future Scope)
- 알림 / 알람
- 인물 엔티티 매칭 + 인물별 상세 타임라인 (삼성 부재중전화 스타일)
- commitment 편집·수동 완료 체크 UI
- 삼성 외 OEM 지원
- 한/영 다국어
- 이메일 draft write-back

## 4. 데이터 소스

| 소스 | 수집 방법 | 권한 |
|---|---|---|
| 📞 전화 | Samsung Voice Recorder 저장 폴더 ContentObserver 감지 → WorkManager STT → LLM 추출 | READ_MEDIA_AUDIO, 녹음 폴더 SAF |
| 🤝 오프라인 미팅 | 동일 (삼성 음성녹음 공용) | 동일 |
| 📧 Gmail | OAuth API + WorkManager periodic sync | OAuth read scopes |
| 📧 Outlook Mail | Microsoft Graph API OAuth + WorkManager periodic | OAuth read scopes |
| 📧 네이버 / 다음 | IMAP + 사용자 앱 비밀번호 입력 | Android Keystore에 credential |
| 📅 Google Calendar | OAuth API + WorkManager periodic | OAuth read scopes |
| 📅 Outlook Calendar | Microsoft Graph API OAuth + WorkManager periodic | OAuth read scopes |

## 5. 데이터 흐름

```
[Source Event]
   → [Collector Service]
   → [Room (WAL + 구조화 row + transcript + 이메일 본문)]   ← primary / source-of-truth
   → [WorkManager upload worker]
   → [Supabase Postgres (mirror — commitment + raw_ingestion + calendar_event)]   ← MVP 성공 지표
```

- **Room → Supabase 일방향 mirror**. Supabase → Room 역방향 sync 없음.
- Transcript·이메일 본문은 **Room에만 저장**. Supabase 업로드 ❌.
- 각 row에 `sync_status` (`pending` / `synced` / `failed`), `retry_count`, `last_attempt_at`.
- WorkManager exponential backoff 재시도. 영구 성공 전까지 Room에서 삭제 안 함.

## 6. 데이터 스키마

### Room (local, 전체 보관)

```kotlin
RawIngestionEvent(
    id, source_type, source_ref, timestamp,
    sync_status, retry_count, last_attempt_at
)

Transcript(
    id, raw_ingestion_id, text, stt_engine, lang, created_at
)
// 로컬 only. Supabase 업로드 없음.

EmailBody(
    id, raw_ingestion_id, subject, body_plain, body_html, from, to, sent_at
)
// 로컬 only. Supabase 업로드 없음.

Commitment(
    id, direction,               // give | take
    counterparty_raw,            // phone# | email | attendee name — 인물 매칭은 Future
    title, description, due_date,
    status,                      // pending | done | overdue (MVP는 pending만)
    source_type, source_ref,
    confidence,                  // 0.0~1.0 LLM 추출 신뢰도
    sync_status, created_at, updated_at
)

CalendarEvent(
    id, source_type, source_ref, title, start_at, end_at,
    attendees_raw, sync_status
)
```

### Supabase (mirror, 구조화만)

- `raw_ingestion_events` — 손실 0 측정용
- `commitments` — 구조화된 약속
- `calendar_events` — 오늘 대시보드 렌더용
- (`transcripts` 없음 — 로컬 only)
- (`email_bodies` 없음 — 로컬 only)
- RLS: user_id 기반 격리
- 리전: ap-northeast-2 (PIPA)

## 7. 음성 파이프라인 (Hybrid Local-first)

1. ContentObserver가 녹음 저장 감지 → Room `raw_ingestion_events` INSERT
2. WorkManager가 on-device STT 시도 (Whisper.cpp 또는 AICore) → Room `transcripts` INSERT
3. on-device Gemini Nano (AICore)로 give/take 추출 → Room `commitments` INSERT
4. WorkManager가 Commitment·RawIngestion·CalendarEvent를 Supabase로 mirror
5. **Phase 2 (MVP 이후)**: 실제 녹음본으로 품질 실험 → Gemini API fallback 설계 (비용 예산은 그때 결정)

## 8. UI 화면

### 8.1 온보딩 (첫 실행 시 순차)

1. **이용약관 동의 화면** — PIPA 기반 Claude 작성 초안. [동의] / [미동의] 버튼. **미동의 시 앱 종료**.
2. 회원가입 / 로그인 (Supabase Auth: 이메일+비번 또는 Google 소셜)
3. **녹음 폴더 자동 감지 + 권한 요청** (SAF. Samsung Voice Recorder 기본 경로 자동 탐색, 실패 시 수동 가이드)
4. Gmail OAuth (스킵 가능)
5. Outlook Mail OAuth (스킵 가능)
6. 네이버 / 다음 IMAP 설정 (스킵 가능)
7. Google Calendar OAuth (스킵 가능)
8. Outlook Calendar OAuth (스킵 가능)
9. **배터리 최적화 예외 요청** (명시 화면, 거부 시 "백그라운드 수집 불안정 경고")
10. 완료 → 메인 대시보드

### 8.2 메인 대시보드 = 통합 오늘 뷰

- **상단 스트립**: 연결된 소스 체크리스트 (6개 아이콘: Gmail·Outlook Mail·네이버·다음·Calendar·녹음. 연결됨 / 도넛지)
- **본문**: 오늘 통합 타임라인 시간순
  - 📅 오늘의 캘린더 이벤트
  - 🎯 due_date = 오늘인 commitment (give / take 배지)
  - 소스 아이콘으로 출처 구분

### 8.3 사이드탭 (햄버거)

- **원문보기** (MVP 포함) — 날짜별 그룹핑
  - 🤝 미팅 / 📞 전화 transcript
  - 📧 이메일 원문 (subject + body_plain)
  - 모두 Room에서 조회
- **설정** — 소스 재연결, 로그아웃
- **(Future placeholder)** 알림 / 인물별 타임라인 / commitment 편집

## 9. 기술 스택

| 계층 | 선택 |
|---|---|
| UI | Kotlin + Jetpack Compose |
| DI | Hilt |
| DB | Room (primary) |
| Background | WorkManager + ContentObserver |
| Network | Ktor client |
| Auth | Supabase Auth (gotrue) |
| Backend | Supabase (Postgres + RLS, ap-northeast-2) |
| STT | on-device Whisper.cpp 또는 AICore (Phase 1 실험) |
| LLM | on-device Gemini Nano → Phase 2에 Gemini API fallback |
| Secrets | Android Keystore / EncryptedSharedPreferences |
| 오류 트래킹 | **Sentry** (온보딩 실패율 추적 포함) |
| min / target SDK | 30 / 34 (가안, spec 단계 확정) |

## 10. 결정 로그 (Q&A 전체)

| # | 질문 | 결정 |
|---|---|---|
| 1 | Give/Take 정의 | 내가 약속한 것 / 받기로 한 것 (IOU 모델) |
| 2 | 음성 파이프라인 위치 | Local-first, 품질 부족 시 API fallback (Phase 2에 실험 후 결정) |
| 3 | Fallback 트리거 | 실제 녹음본 실험 후 설계 변환 (MVP엔 없음) |
| 4 | 타겟 디바이스 | **삼성 전용 MVP** |
| 5 | Write-back 범위 | 로컬 알림만 (Future, MVP는 write-back 없음) |
| 6 | 이메일 소스 범위 | Gmail/Outlook API + 네이버/다음 IMAP |
| 7 | 인물 매칭 | 수동 매칭 UI + 추천 (MVP 제외, Future) |
| 8 | 계정 모델 | **클라우드 계정 필수** (Supabase Auth) |
| 9 | MVP 스코프 | 4개 소스 전부 |
| 10 | 1인용 / 양방향 | **1인용 tracker** |
| 11 | 녹음 감지 후 자동화 | 백그라운드 자동 처리 + 결과만 알림 (알림은 Future) |
| 12 | 완료 감지 | 사용자 수동 체크만 (Future), 후속 일정 1시간 전 알림 (Future) |
| 13 | 배포 지역·언어 | 한국 전용, 한국어만 |
| 14 | 백엔드 | **Supabase** (ap-northeast-2) |
| 15 | LLM 선택 | **Gemini API** 중심 (Phase 2) / Gemini Nano (Phase 1) |
| 16 | 원본 보관 정책 | transcript·이메일 본문 = 로컬 Room only / 음성·메일 원본 서버 업로드 ❌ |
| 17 | Commitment 추가 필드 | **confidence만** (priority/category는 MVP 제외) |
| 18 | Room 역할 | **Room = source-of-truth, Supabase = mirror** |
| 19 | Transcript 저장 | commitment만 Supabase, transcript는 로컬 보존 (사용자 열람용) |
| 20 | MVP UI 범위 | **소스 연결 온보딩 + 메인 대시보드 + 원문보기 사이드탭** |
| 21 | 성공 지표 | raw 수집 이벤트 100% Supabase 도달 (raw 손실 0) |
| 22 | 대시보드 "오늘" 정의 | 캘린더 이벤트 + due=오늘 commitment 통합 뷰 |
| 23 | 원문보기 데이터 저장 | transcript + 이메일 본문 로컬 Room에 저장 |
| 24 | 소스 상태 표시 | 간단 체크리스트 (아이콘) |
| 25 | 이용약관 본문 | Claude가 PIPA 기반 초안 작성 → CTO 검토 |
| 26 | 온보딩 실패 트래킹 | **Sentry** |
| 27 | 녹음 폴더 감지 | **자동 감지 필수** (실패 시 수동 fallback) |
| 28 | Gemini API 비용 | 나중 결정 |
| 29 | 앱 이름 | **BeCalm** |

## 11. 제약·오픈 이슈

### 해결됨 (이 문서)
- 배터리 최적화 예외 → 온보딩 9단계로 명시화
- 앱 이름 → BeCalm
- 온보딩 실패 트래킹 → Sentry
- 녹음 폴더 → 자동 감지
- 이용약관 → Claude가 PIPA 기반 draft

### 미해결 / spec 단계로 이월
- Gemini API 월 예산 한도
- 녹음 참석자 고지 책임 분담 (앱 강제 vs 사용자 책임) — 이용약관에서 명시 필요
- min / target SDK 최종 확정 (가안: 30 / 34)
- Play Store 메타데이터 / 스크린샷
- LLM 추출 프롬프트 설계
- transcript·이메일 본문 로컬 보관 기간 정책
- 삼성 One UI 버전별 녹음 경로 매트릭스

### Local-first 원칙과의 해석
CLAUDE.md의 "로컬 먼저 저장 + 동의된 것만 서버 전송" 원칙을 이렇게 구체화:
- 로컬(Room) = 모든 데이터의 primary store
- Supabase = 구조화된 데이터만 mirror (음성·메일 원본·transcript 제외)
- 원본 파일은 삼성 녹음 앱이 소유, BeCalm은 미접근
- 이 해석으로 **"local-first + 클라우드 계정 필수"가 양립 가능** 하다고 판단

## 12. Future Scope (v2+)

- 🔔 알림 시스템 (due-date 접근, overdue, 후속 일정 1시간 전)
- 👤 인물 엔티티 매칭 + 인물별 상세 타임라인 (삼성 부재중전화 스타일)
- ✅ Commitment 편집·수동 완료 체크 UI
- 📱 삼성 외 OEM (Pixel 등) 지원
- 🌐 한/영 다국어
- ✉️ Gmail/Outlook draft write-back

**MVP 스키마가 v2 기능의 기반** — `counterparty_raw`, `source_ref`, `status`, `raw_ingestion_events` 필드가 이미 있어 마이그레이션 비용 최소.

## 13. 다음 단계 — zero-to-deploy 진입 시 체크리스트

1. `.pipeline/platform.yml` 재작성 (현재는 `Todo Share` 타겟으로 남아 있음)
   - `project_root`, `display_name`, `github.repo`, `governance.cto_handle` 등 BeCalm 기준 재설정
   - BeCalmv4 repo를 사용할지 / 신규 repo를 팔지 CTO 결정 필요
2. 이 설계 문서를 L0_BRIEFING 컨텍스트로 전달
3. Supabase 프로젝트 생성 (ap-northeast-2) → URL·anon key 환경변수 등록
4. OAuth 클라이언트 ID 발급 (Google, Microsoft)
5. Sentry 프로젝트 생성 → DSN 환경변수
6. Claude가 이용약관 PIPA 초안 작성 (L0에서 spec으로 생성)
