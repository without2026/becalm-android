package com.becalm.android.unit.worker

import android.content.Context
import com.becalm.android.R
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.receiver.ReminderBroadcastReceiver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderBroadcastReceiverSpecTest {

    @Test
    fun `CMT-008 buildNotificationSpec projects high priority reminder contract for active commitments`() {
        val context: Context = mockk()
        every { context.getString(R.string.commitment_alarm_title) } returns "곧 마감되는 약속 (1시간 뒤)"
        every {
            context.getString(
                R.string.commitment_alarm_body_give_fmt,
                *anyVararg(),
            )
        } returns "[내가 한] 보고서 전달"

        val spec = ReminderBroadcastReceiver.Companion.buildNotificationSpec(
            context = context,
            commitmentId = "pending-1",
            title = "보고서 전달",
            direction = "give",
        )

        assertEquals("곧 마감되는 약속 (1시간 뒤)", spec.title)
        assertEquals("[내가 한] 보고서 전달", spec.body)
        assertEquals("commitment_due_soon", spec.channelId)
        assertEquals("pending-1", spec.commitmentId)
        assertEquals("becalm://commitments/pending-1", spec.deepLinkUri)
    }

    @Test
    fun `CMT-008 handle drops completed and cancelled commitments without posting notification`() = runTest {
        val context: Context = mockk(relaxed = true)
        val commitmentDao: CommitmentDao = mockk()
        val logger: Logger = mockk(relaxed = true)
        val receiver = spyk(ReminderBroadcastReceiver())
        receiver.commitmentDao = commitmentDao
        receiver.logger = logger

        coEvery { commitmentDao.findByIdForUser("user-1", "completed-1") } returns
            entity(id = "completed-1", actionState = "completed")
        coEvery { commitmentDao.findByIdForUser("user-1", "cancelled-1") } returns
            entity(id = "cancelled-1", actionState = "cancelled")

        invokeHandle(receiver, context, "completed-1", "user-1")
        invokeHandle(receiver, context, "cancelled-1", "user-1")

        verify(exactly = 0) { context.getString(any<Int>()) }
        verify(exactly = 0) { context.getString(any<Int>(), *anyVararg()) }
        verify(exactly = 0) { context.packageName }
    }

    @Test
    fun `CMT-008 handle drops no-deadline commitments without posting notification`() = runTest {
        val context: Context = mockk(relaxed = true)
        val commitmentDao: CommitmentDao = mockk()
        val logger: Logger = mockk(relaxed = true)
        val receiver = spyk(ReminderBroadcastReceiver())
        receiver.commitmentDao = commitmentDao
        receiver.logger = logger

        coEvery { commitmentDao.findByIdForUser("user-1", "no-deadline-1") } returns
            entity(id = "no-deadline-1", actionState = "pending", dueAt = null)

        invokeHandle(receiver, context, "no-deadline-1", "user-1")

        verify(exactly = 0) { context.getString(any<Int>()) }
        verify(exactly = 0) { context.getString(any<Int>(), *anyVararg()) }
        verify(exactly = 0) { context.packageName }
    }

    private fun invokeHandle(
        receiver: ReminderBroadcastReceiver,
        context: Context,
        commitmentId: String,
        userId: String,
    ) {
        receiver.javaClass.getMethod(
            "handle\$app_debug",
            Context::class.java,
            String::class.java,
            String::class.java,
            Continuation::class.java,
        ).invoke(
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
        actionState: String,
        dueAt: Instant? = Instant.parse("2026-04-23T01:00:00Z"),
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        direction = "give",
        counterpartyRaw = null,
        personRef = "lee@corp.com",
        title = "보고서 전달",
        description = null,
        quote = "quote body",
        sourceEventTitle = "Call",
        sourceEventOccurredAt = Instant.parse("2026-04-23T00:00:00Z"),
        dueAt = dueAt,
        dueHint = null,
        dueIsApproximate = false,
        actionState = actionState,
        sourceType = "voice",
        sourceRef = null,
        confidence = 0.8,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "synced",
        createdAt = Instant.parse("2026-04-23T00:00:00Z"),
        updatedAt = Instant.parse("2026-04-23T00:00:00Z"),
        lastEditedBy = null,
        lastEditedAt = null,
        quoteDisputed = false,
        quoteDisputedAt = null,
        deletedAt = null,
        supersedesCommitmentId = null,
    )
}
