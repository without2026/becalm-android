package com.becalm.android.unit.pipeline

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StagingAuthOauthDeviceSmokeScriptSpecTest {

    @Test
    fun `staging auth oauth smoke is explicit destructive and secret safe`() {
        val script = repoFile("qa/device/scripts/staging_auth_oauth_device_smoke.sh").readText()

        assertTrue(script.contains("--confirm-clear-data"))
        assertTrue(script.contains("--confirm-dev-login"))
        assertTrue(script.contains("--confirm-staging-login"))
        assertTrue(script.contains("BACKEND_ENVIRONMENT=\"dev\""))
        assertTrue(script.contains("BECALM_STAGING_EMAIL"))
        assertTrue(script.contains("BECALM_STAGING_PASSWORD"))
        assertTrue(script.contains("redact(raw_logcat)"))
        assertTrue(script.contains("secret_log_count"))
        assertTrue(script.contains("credential_value_in_logcat"))
        assertTrue(script.contains("accounts.google.com"))
        assertTrue(script.contains("oauth_start_account_chooser_requires_user_consent"))
        assertTrue(script.contains("--wait-for-user-oauth-consent"))
        assertTrue(script.contains("--oauth-consent-timeout-seconds"))
        assertTrue(script.contains("--oauth-source"))
        assertTrue(script.contains("google-calendar"))
        assertTrue(script.contains("target_backend_capability = \"calendar\" if oauth_source == \"google_calendar\" else \"mail\""))
        assertTrue(script.contains("oauth_callback_returned_to_app"))
        assertTrue(script.contains("target_connection_confirmed_after_consent"))
        assertTrue(script.contains("google_calendar_connection_confirmed_after_consent"))
        assertTrue(script.contains("--verify-backend-sync-after-consent"))
        assertTrue(script.contains("--verify-android-mirror-after-backend-sync"))
        assertTrue(script.contains("--android-mirror-timeout-seconds"))
        assertTrue(script.contains("--backend-root"))
        assertTrue(script.contains("backend_target_sync_succeeded"))
        assertTrue(script.contains("backend_gmail_sync_succeeded"))
        assertTrue(script.contains("backend_google_calendar_sync_succeeded"))
        assertTrue(script.contains("backend_jwt_env = \"BECALM_STAGING_JWT\" if backend_environment == \"staging\" else \"BECALM_DEV_JWT\""))
        assertTrue(script.contains("\"--email-env\","))
        assertTrue(script.contains("email_env,"))
        assertTrue(script.contains("\"--password-env\","))
        assertTrue(script.contains("password_env,"))
        assertTrue(script.contains("backend_sync_jwt_env="))
        assertTrue(Regex("""(?m)^summary_lines = \[""").containsMatchIn(script))
        assertFalse(Regex("""(?m)^[ \t]+summary_lines = \[""").containsMatchIn(script))
        assertTrue(script.contains("verify_staging_oauth_source_sync.py"))
        assertTrue(script.contains("--summary-json"))
        assertTrue(script.contains("backend_sync_summary_json="))
        assertTrue(script.contains("backend_downstream_evidence_checked="))
        assertTrue(script.contains("backend_source_events_observed="))
        assertTrue(script.contains("backend_calendar_events_observed="))
        assertTrue(script.contains("backend_source_event_participants_observed="))
        assertTrue(script.contains("backend_person_action_feed_ready="))
        assertTrue(script.contains("backend_source_event_count="))
        assertTrue(script.contains("backend_calendar_event_count="))
        assertTrue(script.contains("backend_calendar_matched_source_event_count="))
        assertTrue(script.contains("backend_actions_with_evidence_refs="))
        assertTrue(script.contains("actions_with_evidence_refs"))
        assertTrue(script.contains("DEBUG_PREPARE_STAGING_MIRROR_SMOKE"))
        assertTrue(script.contains("DEBUG_REPORT_STAGING_MIRROR_SMOKE"))
        assertTrue(script.contains("DEBUG_REFRESH_PERSON_ACTIONS_E2E"))
        assertTrue(script.contains("android_mirror_after_backend_succeeded="))
        assertTrue(script.contains("android_source_mirror_observed="))
        assertTrue(script.contains("android_calendar_event_count="))
        assertTrue(script.contains("android_person_action_cache_ready="))
        assertTrue(script.contains("android_people_ui_ready="))
        assertTrue(script.contains("\"--provider\","))
        assertTrue(script.contains("\"google\","))
        assertTrue(script.contains("\"--capability\","))
        assertTrue(script.contains("target_backend_capability,"))
        assertTrue(script.contains("if proofs[\"oauth_start_accounts_google\"] and wait_for_user_oauth_consent"))
        assertTrue(script.contains("if wait_for_user_oauth_consent or oauth_source == \"google_calendar\":"))
        assertTrue(script.contains("open_deeplink(\"becalm://settings/sources\")"))
        assertTrue(script.contains("open_deeplink(\"becalm://oauth-complete?result=success&provider=google_calendar&family=calendar\")"))
        assertTrue(script.contains("current_focus_package"))
        assertTrue(script.contains("dumpsys\", \"window\""))
        assertTrue(script.contains("last_foreground_package_at_timeout"))
        assertTrue(script.contains("enable_stay_awake_for_consent"))
        assertTrue(script.contains("restore_stay_awake_after_consent"))
        assertTrue(script.contains("stay_on_while_plugged_in"))

        assertFalse(script.contains("set -x"))
        assertFalse(Regex("""echo\s+["']?${'$'}email""").containsMatchIn(script))
        assertFalse(Regex("""echo\s+["']?${'$'}password""").containsMatchIn(script))
        assertFalse(script.contains("print(email)"))
        assertFalse(script.contains("print(password)"))
    }

    @Test
    fun `staging auth oauth smoke sdd captures consent boundary and evidence scope`() {
        val sdd = repoFile("docs/p0-staging-auth-oauth-device-smoke-sdd.md").readText()

        assertTrue(sdd.contains("fresh local app state"))
        assertTrue(sdd.contains("real Supabase Auth test login"))
        assertTrue(sdd.contains("default Railway verifier target is dev"))
        assertTrue(sdd.contains("backend-owned source"))
        assertTrue(sdd.contains("Do not automate Google account selection or OAuth consent."))
        assertTrue(sdd.contains("If `--wait-for-user-oauth-consent` is set"))
        assertTrue(sdd.contains("If `--verify-backend-sync-after-consent` is also set"))
        assertTrue(sdd.contains("`--oauth-source google-calendar`"))
        assertTrue(sdd.contains("Gmail consent cannot be"))
        assertTrue(sdd.contains("must not pre-seed"))
        assertTrue(sdd.contains("Google Calendar target runs"))
        assertTrue(sdd.contains("actual provider"))
        assertTrue(sdd.contains("same email/password"))
        assertTrue(sdd.contains("source/status/person-action"))
        assertTrue(sdd.contains("calendar-events endpoint"))
        assertTrue(sdd.contains("BECALM_DEV_JWT"))
        assertTrue(sdd.contains("staging compatibility"))
        assertTrue(sdd.contains("Reports redact the configured test email/password"))
        assertTrue(sdd.contains("qa/device/scripts/staging_auth_oauth_device_smoke.sh"))
    }

    private fun repoFile(path: String): File {
        val fromAppDir = File("../../$path")
        if (fromAppDir.exists()) return fromAppDir
        val fromAndroidDir = File("../$path")
        if (fromAndroidDir.exists()) return fromAndroidDir
        return File(path)
    }
}
