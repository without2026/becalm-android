package com.becalm.android.unit.worker

import android.app.AlarmManager
import android.content.Context
import com.becalm.android.R
import com.becalm.android.core.util.FakeClock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.domain.reminder.ReminderScheduler
import com.becalm.android.receiver.ReminderBroadcastReceiver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Test

class Checkpoint5ReminderContractSpecTest {

    @Test
    fun `E2E-061 active exact due commitment builds notification and deep link contract`() = runTest {
        val context: Context = mockk(relaxed = true)
        val commitmentDao: CommitmentDao = mockk()
        val logger: Logger = mockk(relaxed = true)
        val receiver = spyk(ReminderBroadcastReceiver())
        val specs = mutableListOf<com.becalm.android.receiver.ReminderNotificationSpec>()
        receiver.commitmentDao = commitmentDao
        receiver.logger = logger
        every { context.getString(R.string.commitment_alarm_title) } returns "곧 마감되는 약속 (1시간 뒤)"
        every { context.getString(R.string.commitment_alarm_body_give_fmt, *anyVararg()) } returns "[내가 할 일] 제안서 보내기"
        every { receiver["postNotification"](context, any<com.becalm.android.receiver.ReminderNotificationSpec>()) } answers {
            specs += secondArg<com.becalm.android.receiver.ReminderNotificationSpec>()
        }
        coEvery { commitmentDao.findByIdForUser("user-1", "due-1") } returns
            entity(id = "due-1", title = "제안서 보내기", direction = "give", dueAt = Instant.parse("2026-05-07T02:00:00Z"))

        invokeHandle(receiver, context, "due-1", "user-1")

        assertEquals(1, specs.size)
        assertEquals("due-1", specs.single().commitmentId)
        assertEquals("commitment_due_soon", specs.single().channelId)
        assertEquals("becalm://commitments/due-1", specs.single().deepLinkUri)
    }

    @Test
    fun `E2E-062 no-time commitment skips scheduler alarm and receiver notification`() = runTest {
        val context: Context = mockk()
        val alarmManager: AlarmManager = mockk(relaxed = true)
        val userPrefsStore: UserPrefsStore = mockk()
        val logger: Logger = mockk(relaxed = true)
        every { context.getSystemService(Context.ALARM_SERVICE) } returns alarmManager
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        val scheduler = ReminderScheduler(
            context = context,
            clock = FakeClock(Instant.parse("2026-05-07T00:00:00Z")),
            userPrefsStore = userPrefsStore,
            logger = logger,
        )

        scheduler.schedule("no-time-1", null)

        verify(exactly = 0) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
        verify(exactly = 0) { alarmManager.setWindow(any(), any(), any(), any()) }

        val receiver = spyk(ReminderBroadcastReceiver())
        val commitmentDao: CommitmentDao = mockk()
        receiver.commitmentDao = commitmentDao
        receiver.logger = logger
        every {
            receiver["postNotification"](any<Context>(), any<com.becalm.android.receiver.ReminderNotificationSpec>())
        } answers { Unit }
        coEvery { commitmentDao.findByIdForUser("user-1", "no-time-1") } returns
            entity(id = "no-time-1", title = "시간 없는 약속", direction = "give", dueAt = null)

        invokeHandle(receiver, mockk(relaxed = true), "no-time-1", "user-1")

        verify(exactly = 0) {
            receiver["postNotification"](any<Context>(), any<com.becalm.android.receiver.ReminderNotificationSpec>())
        }
    }

    private fun invokeHandle(
        receiver: ReminderBroadcastReceiver,
        context: Context,
        commitmentId: String,
        userId: String,
    ) {
        receiver.javaClass.methods.single {
            it.name.startsWith("handle\$app_") &&
                it.parameterTypes.contentEquals(
                    arrayOf(
                        Context::class.java,
                        String::class.java,
                        String::class.java,
                        Continuation::class.java,
                    ),
                )
        }.invoke(
            receiver,
            context,
            commitmentId,
            userId,
            object : Continuation<Unit> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) = Unit
            },
        )
    }

    private fun entity(
        id: String,
        title: String,
        direction: String,
        dueAt: Instant?,
    ): CommitmentEntity =
        CommitmentEntity(
            id = id,
            userId = "user-1",
            direction = direction,
            counterpartyRaw = null,
            counterpartyRef = "alice@example.com",
            title = title,
            description = null,
            quote = "quote",
            sourceEventTitle = "Gmail",
            sourceEventOccurredAt = Instant.parse("2026-05-07T00:00:00Z"),
            dueAt = dueAt,
            dueHint = null,
            dueIsApproximate = false,
            actionState = "pending",
            sourceType = "gmail",
            sourceRef = "raw-1",
            confidence = 0.9,
            commitmentState = CommitmentLifecycleLegacy.DRAFT,
            syncStatus = "synced",
            createdAt = Instant.parse("2026-05-07T00:00:00Z"),
            updatedAt = Instant.parse("2026-05-07T00:00:00Z"),
        )
}
