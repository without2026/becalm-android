package com.becalm.android.unit.data.local.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.becalm.android.data.local.datastore.CalendarWriteJobPrefsSnapshot
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UserPrefsStoreSpecTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun `P1-GAP-005 calendar write snapshots round trip display safe fields`() = runTest {
        val store = newStore("calendar-write-round-trip")

        store.setCurrentUserId("user-1")
        store.setCalendarWriteJobSnapshots(
            listOf(
                CalendarWriteJobPrefsSnapshot(
                    jobId = " job-1 ",
                    actionItemId = " action-1 ",
                    title = " Calendar follow-up ",
                    provider = " google_calendar ",
                    scheduleEventLinkId = " schedule-link-1 ",
                    status = " check_failed ",
                    retryAfterSeconds = -10,
                    attempts = -1,
                    errorCode = " calendar_write_status_unavailable ",
                    clientAction = " retry_later ",
                ),
                CalendarWriteJobPrefsSnapshot(
                    jobId = "",
                    actionItemId = "action-ignored",
                    title = "ignored",
                    provider = null,
                    scheduleEventLinkId = null,
                    status = "queued",
                    retryAfterSeconds = null,
                    attempts = 0,
                    errorCode = null,
                    clientAction = null,
                ),
            ),
        )

        val restored = store.observeCalendarWriteJobSnapshots().first()

        assertEquals(1, restored.size)
        assertEquals(
            CalendarWriteJobPrefsSnapshot(
                jobId = "job-1",
                actionItemId = "action-1",
                title = "Calendar follow-up",
                provider = "google_calendar",
                scheduleEventLinkId = "schedule-link-1",
                status = "check_failed",
                retryAfterSeconds = 0,
                attempts = 0,
                errorCode = "calendar_write_status_unavailable",
                clientAction = "retry_later",
            ),
            restored.single(),
        )

        store.setCalendarWriteJobSnapshots(emptyList())

        assertEquals(emptyList<CalendarWriteJobPrefsSnapshot>(), store.observeCalendarWriteJobSnapshots().first())
    }

    @Test
    fun `P1-GAP-005 calendar write snapshots are isolated by current user`() = runTest {
        val store = newStore("calendar-write-user-scope")

        store.setCurrentUserId("user-1")
        store.setCalendarWriteJobSnapshots(
            listOf(
                snapshot(jobId = "job-user-1", actionItemId = "action-user-1"),
            ),
        )

        store.setCurrentUserId("user-2")
        assertEquals(emptyList<CalendarWriteJobPrefsSnapshot>(), store.observeCalendarWriteJobSnapshots().first())

        store.setCalendarWriteJobSnapshots(
            listOf(
                snapshot(jobId = "job-user-2", actionItemId = "action-user-2"),
            ),
        )

        assertEquals("job-user-2", store.observeCalendarWriteJobSnapshots().first().single().jobId)

        store.setCurrentUserId("user-1")
        assertEquals("job-user-1", store.observeCalendarWriteJobSnapshots().first().single().jobId)
    }

    @Test
    fun `P1-GAP-005 calendar write reauth snapshot keeps reconnect context`() = runTest {
        val store = newStore("calendar-write-reauth")

        store.setCurrentUserId("user-1")
        store.setCalendarWriteJobSnapshots(
            listOf(
                CalendarWriteJobPrefsSnapshot(
                    jobId = "job-reauth",
                    actionItemId = "action-reauth",
                    title = "Reconnect calendar",
                    provider = "google_calendar",
                    scheduleEventLinkId = "schedule-link-reauth",
                    status = "needs_reauth",
                    retryAfterSeconds = null,
                    attempts = 2,
                    errorCode = "provider_token_expired",
                    clientAction = "reconnect_source",
                ),
            ),
        )

        assertEquals(
            CalendarWriteJobPrefsSnapshot(
                jobId = "job-reauth",
                actionItemId = "action-reauth",
                title = "Reconnect calendar",
                provider = "google_calendar",
                scheduleEventLinkId = "schedule-link-reauth",
                status = "needs_reauth",
                retryAfterSeconds = null,
                attempts = 2,
                errorCode = "provider_token_expired",
                clientAction = "reconnect_source",
            ),
            store.observeCalendarWriteJobSnapshots().first().single(),
        )
    }

    private fun newStore(prefix: String): UserPrefsStoreImpl =
        UserPrefsStoreImpl(
            PreferenceDataStoreFactory.create(
                produceFile = {
                    temporaryFolder.newFile("$prefix.preferences_pb")
                },
            ),
        )

    private fun snapshot(
        jobId: String,
        actionItemId: String,
    ): CalendarWriteJobPrefsSnapshot =
        CalendarWriteJobPrefsSnapshot(
            jobId = jobId,
            actionItemId = actionItemId,
            title = actionItemId,
            provider = "google_calendar",
            scheduleEventLinkId = null,
            status = "check_failed",
            retryAfterSeconds = null,
            attempts = 1,
            errorCode = "calendar_write_status_unavailable",
            clientAction = "retry_later",
        )
}
