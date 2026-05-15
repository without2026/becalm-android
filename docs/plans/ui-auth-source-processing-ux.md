# Auth And Source Processing UX SDD

## Goal

Improve the beta user experience for authentication and incoming source processing without making source infrastructure the product's main surface.

The user must understand:

- whether authentication is waiting, failed, or ready to continue;
- whether a source is only connected or actively bringing evidence into BeCalm;
- which incoming source needs user action;
- where to recover from a failed or blocked source.

## Product Constraints

- BeCalm stays person-first. Source state is support information, not a command-center dashboard.
- Normal background processing should stay quiet on the Today surface.
- Waiting, blocked, and failed states must be visible in plain Korean copy.
- Color cannot be the only state carrier. Text labels and icons are required.
- Implementation must reuse existing state stores where possible:
  - `AuthUiState`
  - `SourceStatusRepository`
  - `ProcessingStatusRepository`

## Scope

### Auth UI

1. Login must show an inline trust note explaining that only user-approved sources are connected after login.
2. Login must show a clear inline loading message while auth is in progress.
3. Login must show recoverable inline feedback for auth errors and Google sign-in availability.
4. Google sign-in disabled state must read as beta-user guidance, not developer setup language.
5. Email/password input validation remains local and immediate.

### Source Processing UI

1. Processing status must be grouped into:
   - active work,
   - action needed,
   - recently quiet/complete.
2. Processing status must include a top summary so users do not need to scan every row.
3. Internal phase names must be translated to user-facing copy:
   - scanning -> 새 기록 확인 중
   - new items -> 새 기록 발견
   - gemini -> 내용 정리 중
   - uploading -> 반영 중
   - synced -> 관계 메모리에 반영됨
   - blocked/error -> 확인 필요
4. Empty processing state must explain what will appear later.

### Source Detail UI

1. Source detail must include a compact processing flow:
   - 연결
   - 새 기록 확인
   - 관계 메모리 반영
2. The flow must distinguish `connected`, `syncing`, `error`, and `disconnected`.
3. Manual recovery actions remain in source detail and settings source list.

## Non-goals

- New backend APIs.
- Real-time per-item progress counts from server sync endpoints.
- A full source command-center redesign.
- Replacing snackbar behavior everywhere.

## Acceptance Criteria

- Auth tests cover inline auth guidance, loading, disabled Google guidance, and error recovery copy.
- Processing status tests cover active/action/quiet grouping and empty state copy.
- Source detail tests cover the processing flow for connected/syncing/error states.
- Existing auth, onboarding, source, and settings tests continue to pass.
