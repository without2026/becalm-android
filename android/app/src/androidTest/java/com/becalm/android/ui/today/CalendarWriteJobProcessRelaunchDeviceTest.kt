package com.becalm.android.ui.today

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.BuildConfig
import com.becalm.android.MainActivity
import com.becalm.android.R
import com.becalm.android.core.di.DataStoreModule
import com.becalm.android.data.local.datastore.CalendarWriteJobPrefsSnapshot
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.secure.EncryptedTokenStore
import com.becalm.android.data.remote.interceptor.AuthInterceptor
import com.becalm.android.data.remote.supabase.SupabaseSession
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarWriteJobProcessRelaunchDeviceTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    @Test
    fun seedCalendarWriteSnapshotForProcessRelaunchProof() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val userPrefsStore = UserPrefsStoreImpl(DataStoreModule.provideUserPrefsDataStore(context))
        val sessionStore = EncryptedTokenStore(context, Dispatchers.IO)

        userPrefsStore.setCurrentUserId(DEBUG_USER_ID)
        userPrefsStore.setTermsAccepted(true)
        userPrefsStore.setOnboardingCompleted(true)
        sessionStore.save(
            SupabaseSession(
                accessToken = debugAccessToken(DEBUG_USER_ID),
                refreshToken = "debug-calendar-write-relaunch-refresh",
                userId = DEBUG_USER_ID,
                email = "debug.calendar.write@becalm.local",
                expiresAt = Instant.parse("2099-01-01T00:00:00Z"),
            ),
        )
        userPrefsStore.setCalendarWriteJobSnapshots(
            listOf(
                CalendarWriteJobPrefsSnapshot(
                    jobId = JOB_ID,
                    actionItemId = "pa-calendar-write-relaunch",
                    title = JOB_TITLE,
                    provider = "google_calendar",
                    scheduleEventLinkId = "schedule-link-relaunch",
                    status = "check_failed",
                    retryAfterSeconds = null,
                    attempts = 1,
                    errorCode = "calendar_write_status_unavailable",
                    clientAction = "retry_later",
                ),
            ),
        )

        val restored = userPrefsStore.observeCalendarWriteJobSnapshots().first()
        assertEquals(1, restored.size)
        assertEquals(JOB_ID, restored.single().jobId)
        assertEquals("check_failed", restored.single().status)
    }

    @Test
    fun calendarWriteSnapshotRestoresAfterColdRelaunch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(intent).use {
            val todayTab = string(context, R.string.nav_today)
            composeTestRule.waitUntil(timeoutMillis = 20_000) {
                composeTestRule.onAllNodesWithText(todayTab, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            composeTestRule.onAllNodesWithText(todayTab, useUnmergedTree = true)
                .onFirst()
                .performClick()
            composeTestRule.waitUntil(timeoutMillis = 20_000) {
                composeTestRule.onAllNodesWithTag(
                    "schedule-calendar-write-status-$JOB_ID",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithTag(
                "schedule-calendar-write-status-panel",
                useUnmergedTree = true,
            ).assertIsDisplayed()
            composeTestRule.onNodeWithTag(
                "schedule-calendar-write-status-$JOB_ID",
                useUnmergedTree = true,
            ).assertIsDisplayed()
            composeTestRule.onNodeWithText(JOB_TITLE).assertIsDisplayed()
            composeTestRule.onNodeWithText(string(context, R.string.schedule_calendar_write_status_check_failed))
                .assertIsDisplayed()
            composeTestRule.onNodeWithText(string(context, R.string.schedule_calendar_write_status_retry_action))
                .assertIsDisplayed()
        }
    }

    private fun string(context: Context, resId: Int): String = context.getString(resId)

    private fun debugAccessToken(userId: String): String {
        val nowSeconds = System.currentTimeMillis() / 1000L
        val issuer = BuildConfig.SUPABASE_URL
            .trim()
            .trimEnd('/')
            .takeIf { it.isNotBlank() }
            ?.let { "$it/auth/v1" }
            ?: "debug"
        val header = """{"alg":"none","typ":"JWT"}"""
        val payload = """
            {"iss":"${issuer.jsonEscaped()}","sub":"${userId.jsonEscaped()}","aud":"authenticated","iat":$nowSeconds,"exp":${nowSeconds + 365L * 24L * 60L * 60L}}
        """.trimIndent()
        return "${header.base64Url()}." +
            "${payload.base64Url()}." +
            AuthInterceptor.DEBUG_LOCAL_ONLY_TOKEN_SIGNATURE
    }

    private fun String.base64Url(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))

    private fun String.jsonEscaped(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val DEBUG_USER_ID = "00000000-0000-4000-8000-000000000001"
        const val JOB_ID = "job-device-relaunch"
        const val JOB_TITLE = "Jane Kim calendar candidate"
    }
}
