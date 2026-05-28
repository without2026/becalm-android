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
        assertTrue(script.contains("--confirm-staging-login"))
        assertTrue(script.contains("BECALM_STAGING_EMAIL"))
        assertTrue(script.contains("BECALM_STAGING_PASSWORD"))
        assertTrue(script.contains("redact(raw_logcat)"))
        assertTrue(script.contains("secret_log_count"))
        assertTrue(script.contains("credential_value_in_logcat"))
        assertTrue(script.contains("accounts.google.com"))
        assertTrue(script.contains("oauth_start_account_chooser_requires_user_consent"))
        assertTrue(script.contains("--wait-for-user-oauth-consent"))
        assertTrue(script.contains("--oauth-consent-timeout-seconds"))
        assertTrue(script.contains("oauth_callback_returned_to_app"))
        assertTrue(script.contains("gmail_connection_confirmed_after_consent"))
        assertTrue(script.contains("--verify-backend-sync-after-consent"))
        assertTrue(script.contains("--backend-root"))
        assertTrue(script.contains("backend_gmail_sync_succeeded"))
        assertTrue(script.contains("verify_staging_oauth_source_sync.py"))
        assertTrue(script.contains("--provider\", \"google\""))
        assertTrue(script.contains("--capability\", \"mail\""))
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
        assertTrue(sdd.contains("real staging Supabase Auth login"))
        assertTrue(sdd.contains("backend-owned source"))
        assertTrue(sdd.contains("Do not automate Google account selection or OAuth consent."))
        assertTrue(sdd.contains("If `--wait-for-user-oauth-consent` is set"))
        assertTrue(sdd.contains("If `--verify-backend-sync-after-consent` is also set"))
        assertTrue(sdd.contains("Reports redact staging email/password"))
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
