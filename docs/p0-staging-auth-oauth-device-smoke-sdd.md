# P0 Staging Auth OAuth Device Smoke SDD

## Problem

The staging auth and OAuth path needs device evidence that starts from fresh local app state and uses a real staging Supabase Auth login. The smoke must prove the browser handoff and return without exposing secrets or treating Google account consent as something the script can own.

## Current Behavior

`qa/device/scripts/staging_auth_oauth_device_smoke.sh` clears app data only when explicitly confirmed, signs in with staging credentials from environment variables, and starts the Gmail OAuth connection flow for a backend-owned source.

## Target Behavior

The smoke verifies that BeCalm can sign in on staging, reach Google OAuth, return from OAuth, recover the source state, and optionally verify backend Gmail sync.

Do not automate Google account selection or OAuth consent.

If `--wait-for-user-oauth-consent` is set, the script waits for the tester to finish the Google consent flow on the device, then confirms that the OAuth callback returned to the app.

If `--verify-backend-sync-after-consent` is also set, the script runs the backend verifier after consent and waits for Gmail source sync evidence.

Reports redact staging email/password and must not print raw credentials in stdout, logcat excerpts, or generated report files.

## Acceptance

- The smoke command requires destructive flags before clearing app data.
- The script reads credentials from environment variables only.
- The report contains redacted evidence, source-state booleans, and focused UI dumps.
- The script path remains `qa/device/scripts/staging_auth_oauth_device_smoke.sh`.
