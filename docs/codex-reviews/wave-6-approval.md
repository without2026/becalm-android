# Wave 6 — codex:review approval evidence

**Branch**: `feat/ui/wave-6`
**Base**: `origin/main` (wave 5 merged at a54bcd7 / 7aeabb9)
**Final sha**: `1e2c5d2`
**Date**: 2026-04-22
**Rounds**: 5

## Round-by-round verdicts

| Round | Verdict | P1 count | Notes |
|-------|---------|----------|-------|
| 1 | REJECTED | 3 | R1-P1-01 (MS Graph cleanup), R1-P1-02 (PIPA deny navigation), R1-P1-03 (IMAP consent per-recipient) |
| 2 | REJECTED | 1 | R2-P1 DataStore user-scoped keys (AUTH-008) |
| 3 | REJECTED | 2 | R3-P1-01 (invalidateSession DB close regression), R3-P1-02 (onSkipStep index-driven bug) |
| 4 | REJECTED | 2 | R4-P1-01 (non-atomic IMAP PIPA write), R4-P1-02 (same-process account swap not enforced) |
| 5 | **APPROVED** | 0 | All eight prior P1s confirmed-fixed; no new findings |

No circling: each round's P1 set was strictly disjoint from the prior rounds, and Codex's own Round 5 enumeration independently confirmed every prior fix is still intact in the final diff.

## P1 findings and resolution

### R1-P1-01 — MS Graph credentials not cleared on sign-out
- **Cite**: `.spec/auth.spec.yml:43-49` AUTH-005 ("로컬 토큰 및 해당 user의 in-memory Room 연결이 해제된다"), PIPA Article 17 cross-account recipients
- **Fix**: commit `f3d957b` — `AuthRepositoryImpl` injects `MsGraphTokenProviderImpl` (concrete type, same rationale as `GoogleAuthTokenProviderImpl`); both `signOut()` and `invalidateSession()` step lists gained a `msGraphSignOut` step that runs after `googleOAuthCleanup`, draining MSAL's single-account cache and the mirrored `OAuthCredentialStore` entry.

### R1-P1-02 — PIPA deny still navigated to connectable OAuth screen
- **Cite**: PIPA Article 17 per-recipient consent invariant; plan `ui-onboarding-pipa-email-consent.md` §5.1
- **Fix**: commit `f3d957b` — `OnboardingViewModel.onEmailPipaConsent` is now `suspend` and returns `Boolean`; `OnboardingEmailPipaConsentScreen` awaits the return and navigates to `connectRoute` only on true; deny routes to `skipAheadRoute` (next disclosure or `OnboardingGoogleCalendar` for IMAP). `onConnectEmailProvider` and `saveImapCredentials` hard-gate on persisted consent, emitting `pipa_consent_missing` + `onboarding_step_failed` when the DataStore flag is false.

### R1-P1-03 — IMAP consent stored as a single shared key
- **Cite**: PIPA Article 17 — Naver Corp and Kakao Corp are legally distinct recipients
- **Fix**: commit `f3d957b` — `EmailPipaProvider` enum split into `NAVER_IMAP` + `DAUM_IMAP` with an `IMAP_GROUP` companion list; `saveImapCredentials` maps `sourceType → specific recipient` and persists the matching `<recipient>_connected` flag so revocation can target Naver or Daum independently.

### R2-P1 — DataStore keys globally scoped (AUTH-008 violation)
- **Cite**: `.spec/auth.spec.yml:73` AUTH-008 ("DataStore의 사용자별 상태(cursor_*, contacts_permission_asked, pipa_consent_timestamp 등)는 동일하게 user_<hash> 네임스페이스로 분리 저장")
- **Fix**: commit `3a27ee9` — `UserPrefsStoreImpl` rewrites PIPA and onboarding keys as `user_<sha256(user_id)[:16]>_<base>`; getters fall back to the default when `current_user_id` is null, setters silently no-op on the same precondition. `BeCalmDatabase.databaseFilename` prefix aligned from `becalm-<hash>.db` to AUTH-008's `becalm_<hash>.db`. Regression: `UserPrefsStoreAuth008Test` covers A→signout→B→signin isolation for onboarding, email PIPA consent, source-connected, and voice consent, plus A re-sign-in preservation (AUTH-005).

### R3-P1-01 — invalidateSession closed DB → same-account re-login regression
- **Cite**: `.spec/auth.spec.yml:45-49` AUTH-005 ("동일 계정으로 재로그인 시 데이터가 복원된다")
- **Fix**: commit `0166318` — removed the `databaseClose` step from the `invalidateSession` step list (close is now reserved for the full-wipe `signOut` flow, which runs `clearAllTables` first). Rationale documented inline: `@Singleton` repositories captured their DAO references at first injection, so closing the underlying DB here would leave those references pointing at a dead file handle on the most common routine sign-out → sign-back-in path.

### R3-P1-02 — onSkipStep index-driven → ONB-008 gate failures
- **Cite**: `.spec/onboarding.spec.yml:97-100` ONB-008 terminal gate
- **Fix**: commit `0166318` — public signature is now `onSkipStep(step: OnboardingStep)`; every onboarding screen (Contacts, RecordingFolder, Battery, Notification, Gmail, Outlook, IMAP, GCal, OCal) passes its explicit step. Regression: `mixed_grant_and_skip_decisions_all_reach_onCompleteOnboarding_terminal_gate` exercises a realistic path (grant TERMS/LOGIN, deny PIPA, skip RECORDING, grant CONTACTS, skip all three email providers, connect both calendars, skip notifications, grant battery, finish ColdSync).

### R4-P1-01 — non-atomic IMAP consent batch
- **Cite**: PIPA Article 17 — combined IMAP disclosure presents a single Agree tap, so the durable record must also be all-or-none
- **Fix**: commit `1e2c5d2` — new `UserPrefsStore.setEmailPipaConsents(providers, granted)` writes every recipient inside a single `DataStore.edit` transaction; the single-recipient overload delegates to the batch. `OnboardingViewModel.onEmailPipaConsent` emits observability events only AFTER the batch commits so the audit trail cannot record "consent granted" for a recipient whose key rolled back. Regression: `onEmailPipaConsent_batch_failure_does_not_emit_audit_events_or_mark_steps` forces the batched write to throw and asserts zero side-effects.

### R4-P1-02 — same-process account swap unsafe
- **Cite**: `.spec/auth.spec.yml:73` AUTH-008 cross-account isolation
- **Fix**: commit `1e2c5d2` — new `ProcessRestarter` interface + `ProcessRestarterImpl` (`AlarmManager` ELAPSED_REALTIME schedules a 100 ms-delayed re-launch, then `exitProcess(0)`). `BeCalmDatabaseProvider` exposes `currentUserIdHash()`. `AuthRepositoryImpl.applySignInState` compares the pre-sign-in hash against the newly-signed-in user's hash; on mismatch (and non-null prior hash) it calls `processRestarter.restart()` (declared `Nothing`). Same-user re-sign-in (identical hash) and first-sign-in (null prior hash) skip the restart. Regression: `AuthRepositoryAccountSwapTest` covers `triggersRestart_whenAccountSwapDetected`, `doesNotRestart_whenSameUserReSignsIn`, `doesNotRestart_onFirstSignInOfProcess`, and the Google variant.

## Scope fences upheld across rounds

Codex correctly classified the following as out of scope per the supplied rubric and plan §7 declarations:

- W3/W5-era pre-existing test drift (`BecalmError.safeMessage` copy, `SettingsViewModelTest`, 67 unrelated failures, `ImapCredentialStoreTest` KeyStore issues)
- Sentry / Firebase SDK integration — `LoggerObservabilityClient` is the confirmed alpha target
- AGP `META-INF/LICENSE.md` packaging collision in `assembleDebug` (pre-existing)
- W7 items: PIPA consent revocation UI, PIPA action log UI, account deletion, per-source disconnect
- W8 items: source-management error banners, Sources reconnect flow
- In-process Hilt user-swap refactor to `Provider<Dao>` — deferred by plan §Appendix; alpha contract is process restart (now enforced by `ProcessRestarter`)
- Spec amendments (onboarding 12→13 step count) — separate PR
- Existing alpha users' pre-S6-A globally-keyed DataStore values — plan §7
- Other DataStore surfaces (SyncCursorStore, EncryptedTokenStore) — plan scope limited to AUTH-008 cited keys
- kapt→KSP Moshi and hiltViewModel deprecation warnings

## Wave-6 scope summary

**Plans implemented (8):**
- S6-A db-auth-user-scoped-database
- S6-B domain-auth-signout-preserve-data
- S6-C ui-auth-google-signin-wiring
- S6-D ui-onboarding-pipa-email-consent
- S6-E ui-onboarding-notifications-sentry
- S6-F ui-onboarding-gmail-oauth
- S6-G ui-onboarding-outlook-oauth
- S6-H ui-onboarding-imap-provider-selector

**Commits (8):**
- 217cfef docs(plans): seed 4 Wave 6 missing plan docs (S6-A/B/C/E)
- d2b4537 feat(db/auth): per-user Room file via BeCalmDatabaseProvider (S6-A)
- 6396bb7 refactor(domain/auth): routine sign-out preserves Room data (S6-B)
- ef64a2d feat(ui/onboarding): POST_NOTIFICATIONS step + ObservabilityClient (S6-E)
- feafc0c feat(ui/auth): wire real Google sign-in via CredentialManager (S6-C)
- 1a7344d feat(ui/onboarding): per-provider email PIPA 제3자 제공 consent (S6-D)
- 4a14681 feat(ui/onboarding): real email OAuth + IMAP credential save (S6-F/G/H)
- f3d957b fix(wave-6): resolve three Codex adversarial-review P1 blockers
- 3a27ee9 fix(wave-6): user-scope DataStore keys (AUTH-008) + align DB filename with spec
- 0166318 fix(wave-6): preserve Room on routine sign-out + explicit-step onSkipStep (round 3)
- 1e2c5d2 fix(wave-6): atomic PIPA consent + process-restart on account swap (R4)

**Test coverage added:**
- `BeCalmDatabaseHashTest` — deriveUserIdHash, databaseFilename AUTH-008 conformance
- `BeCalmDatabaseProviderTest` — Robolectric provider swap / lazy / close
- `UserPrefsStoreAuth008Test` — real DataStore, A/B isolation
- `LoggerObservabilityClientTest` — PII scrub, event routing
- `GoogleSignInResultTest` — sealed shape
- `OnboardingViewModelTest` — 32 cases covering S6-A..H + R1..R4 regressions
- `AuthViewModelTest` — S6-B invalidateSession path + pre-existing suite
- `AuthRepositoryAccountSwapTest` — R4 restart contract
