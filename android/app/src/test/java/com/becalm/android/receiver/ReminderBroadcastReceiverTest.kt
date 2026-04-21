package com.becalm.android.receiver

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for [ReminderBroadcastReceiver] CMT-008 silent-drop rules.
 *
 * We exercise the suspend [ReminderBroadcastReceiver.handle] function directly
 * rather than the [BroadcastReceiver.onReceive] entry point — `goAsync()` requires
 * an active broadcast dispatch that a plain unit test cannot synthesize. The
 * handle() extraction is documented on the receiver itself as "Business logic
 * extracted for unit testability"; its contract is identical to onReceive's
 * async body, so coverage here pins the spec behavior without platform cruft.
 *
 * [NotificationManagerCompat.from] is mocked statically so we can assert the exact
 * number of notify() invocations without reading Robolectric's shadow queue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class ReminderBroadcastReceiverTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var commitmentDao: CommitmentDao
    private lateinit var logger: Logger
    private lateinit var notificationManagerCompat: NotificationManagerCompat

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        commitmentDao = mockk(relaxed = true)
        logger = mockk(relaxed = true)
        notificationManagerCompat = mockk(relaxed = true)

        // NotificationManagerCompat.from is a static factory; mock it so notify() assertions
        // do not depend on Robolectric's ShadowNotificationManager queue state.
        mockkStatic(NotificationManagerCompat::class)
        every { NotificationManagerCompat.from(any()) } returns notificationManagerCompat

        // The receiver's POST_NOTIFICATIONS check runs only on API 33+ (TIRAMISU); at our
        // test SDK (S = API 31) it is bypassed, so no PackageManager stub is required.
    }

    @After
    fun tearDown() {
        unmockkStatic(NotificationManagerCompat::class)
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /**
     * Spy so we can stub [ReminderBroadcastReceiver.postNotification] — otherwise
     * the NotificationCompat builder would resolve real string resources which
     * Robolectric's in-memory resource table only loads for the app resources
     * via the merged-res pass; in a pure unit test the receiver's Context doesn't
     * carry the app resource arsc. Spying the open method lets us assert the
     * call count while bypassing the platform cruft.
     */
    private fun buildReceiver(): ReminderBroadcastReceiver =
        spyk(
            ReminderBroadcastReceiver().apply {
                commitmentDao = this@ReminderBroadcastReceiverTest.commitmentDao
                logger = this@ReminderBroadcastReceiverTest.logger
            },
            recordPrivateCalls = false,
        ).also { spy ->
            // Stub the notification composition; it shells out to android.app.Notification
            // APIs that require a live Resources table. We separately assert that
            // NotificationManagerCompat.notify is invoked zero or one times via the
            // spied `postNotification` stub delegating to the real
            // NotificationManagerCompat.from(context).notify path.
            justRun { spy.postNotification(any(), any(), any(), any()) }
            every { spy.postNotification(any(), any(), any(), any()) } answers {
                // Simulate the real receiver's final call to NotificationManagerCompat
                // so the assertion on notify() still exercises the notify path.
                notificationManagerCompat.notify(
                    args[1].toString().hashCode(),
                    mockk(relaxed = true),
                )
            }
        }

    private fun intentFor(id: String): Intent =
        Intent().apply { putExtra(ReminderBroadcastReceiver.EXTRA_COMMITMENT_ID, id) }

    private fun commitmentEntity(
        id: String = "c-1",
        actionState: String = "pending",
        direction: String = "give",
        title: String = "Call the doctor",
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        direction = direction,
        counterpartyRaw = null,
        personRef = null,
        title = title,
        description = null,
        quote = "Q",
        sourceEventTitle = null,
        sourceEventOccurredAt = Instant.DISTANT_PAST,
        dueAt = null,
        dueHint = null,
        dueIsApproximate = false,
        actionState = actionState,
        sourceType = "voice",
        sourceRef = null,
        confidence = 1.0,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "synced",
        createdAt = Instant.DISTANT_PAST,
        updatedAt = Instant.DISTANT_PAST,
    )

    /** Drives Robolectric's main looper so the goAsync() IO coroutine's side effects flush. */
    private fun flushLoopers() {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        // Give the IO dispatcher a beat — goAsync uses its own scope, not testDispatcher,
        // so idle the shadow looper a second time after any pending main-thread posts.
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `pending commitment posts a notification`() = runTest(testDispatcher) {
        val entity = commitmentEntity(id = "c-1", actionState = "pending")
        coEvery { commitmentDao.findByIdForUser("user-1","c-1") } returns entity

        buildReceiver().handle(context, scheduledUserId = "user-1", commitmentId = "c-1")

        verify(exactly = 1) { notificationManagerCompat.notify(any<Int>(), any()) }
    }

    @Test
    fun `completed commitment is silently dropped`() = runTest(testDispatcher) {
        val entity = commitmentEntity(id = "c-2", actionState = "completed")
        coEvery { commitmentDao.findByIdForUser("user-1","c-2") } returns entity

        buildReceiver().handle(context, scheduledUserId = "user-1", commitmentId = "c-2")

        verify(exactly = 0) { notificationManagerCompat.notify(any<Int>(), any()) }
    }

    @Test
    fun `cancelled commitment is silently dropped`() = runTest(testDispatcher) {
        val entity = commitmentEntity(id = "c-3", actionState = "cancelled")
        coEvery { commitmentDao.findByIdForUser("user-1","c-3") } returns entity

        buildReceiver().handle(context, scheduledUserId = "user-1", commitmentId = "c-3")

        verify(exactly = 0) { notificationManagerCompat.notify(any<Int>(), any()) }
    }

    @Test
    fun `missing commitment is silently dropped`() = runTest(testDispatcher) {
        coEvery { commitmentDao.findByIdForUser("user-1","c-4") } returns null

        buildReceiver().handle(context, scheduledUserId = "user-1", commitmentId = "c-4")

        verify(exactly = 0) { notificationManagerCompat.notify(any<Int>(), any()) }
    }
}
