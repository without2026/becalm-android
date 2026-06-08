# P0 Dev Auth OAuth Device Smoke SDD

## Problem

The dev auth and OAuth path needs device evidence that starts from fresh local app state and uses a real Supabase Auth test login. The smoke must prove the browser handoff and return without exposing secrets or treating Google account consent as something the script can own.

## Current Behavior

`qa/device/scripts/staging_auth_oauth_device_smoke.sh` clears app data only when explicitly confirmed, signs in with configured test credentials from environment variables, and starts a selected Google OAuth connection flow for a backend-owned source. The script filename and legacy staging flags remain compatibility aliases; the default Railway verifier target is dev.

## Target Behavior

The smoke verifies that BeCalm can sign in on dev, reach Google OAuth, return from OAuth, recover the source state, and optionally verify backend sync for the selected Google source.

The default `--oauth-source` is `gmail`, preserving the original device smoke
behavior. `--oauth-source google-calendar` opens and verifies the Google
Calendar source instead. These are separate proofs; a Gmail consent cannot be
used as Calendar provider-token evidence.

Android must open the authorization URL through the trusted OAuth browser
launcher, preferring a known browser Custom Tabs provider and otherwise a known
system browser package. Disallowed in-app browsers such as Naver must not be
selected even if they can handle generic `https` view intents.

Do not automate Google account selection or OAuth consent.

If `--wait-for-user-oauth-consent` is set, the script waits for the tester to finish the Google consent flow on the device, then confirms that the OAuth callback returned to the app.

If `--verify-backend-sync-after-consent` is also set, the script runs the
backend verifier after consent and waits for selected-source sync evidence. The
backend verifier summary must be stored in the device report and surfaced in
`summary.env` as source-event, source-participant, and person-action-feed
evidence booleans/counts, not only as a single sync-success flag. For Calendar,
the summary must also expose calendar-events endpoint evidence because Android
mirrors Calendar through `/v1/calendar_events`, not the generic source-events
endpoint. The Calendar proof must additionally report whether a selected
connection-scoped source event id is visible through the calendar-events
endpoint, so an old unrelated calendar row cannot satisfy provider-token proof.

If `--verify-android-mirror-after-backend-sync` is also set, the script must only
run it after the backend verifier succeeds. It clears the selected local mirror
rows through the debug receiver, refreshes Android Room from the backend, records
mail raw-row or calendar-event mirror counts, refreshes the person-action cache,
and checks the People UI when backend actions exist. This keeps provider sync,
Android mirror recovery, and visible next-action readiness as separate evidence.

Consent-mode runs, and all Google Calendar target runs, must not pre-seed the
selected source with a fake `oauth-complete?result=success` callback before
opening the real provider flow. They navigate directly to the Sources screen and
let the actual provider callback prove completion.

The backend verifier must mint the Supabase user JWT from the same email/password
environment variables used for the Android app login. A consent proof is not
valid if the device signs in as one test user while the backend verifier checks
another test user's source connections. The dev default JWT env is
`BECALM_DEV_JWT`; `BECALM_STAGING_JWT` is kept only as a staging compatibility
alias when the Railway environment is explicitly `staging`.

Reports redact the configured test email/password and must not print raw credentials in stdout, logcat excerpts, or generated report files.

## Acceptance

- The smoke command requires destructive flags before clearing app data.
- The script reads credentials from environment variables only.
- The script can target Gmail or Google Calendar independently with
  `--oauth-source`.
- OAuth start uses a trusted browser package and does not rely on an unconstrained
  `ACTION_VIEW` chooser that can land in a disallowed in-app browser.
- The backend verifier uses the same credential env names as the Android login
  and reports the non-secret JWT env alias and provider capability it used.
- Consent-plus-backend reports expose downstream source/status/person-action
  evidence from the verifier JSON so later readiness audits can distinguish
  provider sync success from next-action projection readiness.
- Calendar consent-plus-backend reports expose both source-events and
  calendar-events endpoint evidence, including the matched selected-source-event
  count.
- Consent-plus-Android reports expose local mirror counts, person-action cache
  readiness, and People UI readiness separately after backend sync succeeds.
- The report contains redacted evidence, source-state booleans, and focused UI dumps.
- The script path remains `qa/device/scripts/staging_auth_oauth_device_smoke.sh`.
