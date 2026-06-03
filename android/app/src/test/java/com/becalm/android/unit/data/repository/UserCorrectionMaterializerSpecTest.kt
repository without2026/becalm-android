package com.becalm.android.unit.data.repository

import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.ScheduleRowTombstoneDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.ScheduleRowTombstoneEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.local.db.entity.UserCorrectionEntity
import com.becalm.android.data.local.db.entity.UserCorrectionStatus
import com.becalm.android.data.local.db.entity.UserCorrectionSyncStatus
import com.becalm.android.data.repository.UserCorrectionMaterializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UserCorrectionMaterializerSpecTest {

    private val personIndexDao: PersonIndexDao = mockk(relaxed = true)
    private val commitmentDao: CommitmentDao = mockk(relaxed = true)
    private val scheduleRowTombstoneDao: ScheduleRowTombstoneDao = mockk(relaxed = true)
    private val materializer = UserCorrectionMaterializer(
        personIndexDao = personIndexDao,
        commitmentDao = commitmentDao,
        scheduleRowTombstoneDao = scheduleRowTombstoneDao,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun `participant reassign rewrites source participant and commitment participants`() = runTest {
        val original = participant(personId = "person-wrong")
        val updated = original.copy(personId = "person-right", resolutionStatus = "resolved")
        val commitment = commitment(id = "commitment-1", sourceRef = "raw:source-1")
        coEvery {
            personIndexDao.findSourceEventParticipantById(USER_ID, "participant-1")
        } returnsMany listOf(original, updated)
        coEvery {
            commitmentDao.findLiveReviewableCommitmentsForSourceRefs(
                userId = USER_ID,
                sourceType = "gmail",
                sourceRefs = listOf("raw:source-1", "source-1", "gmail-message-1"),
            )
        } returns listOf(commitment)

        materializer.apply(
            correction(
                action = "participant_reassign",
                targetId = "participant-1",
                sourceEventId = "source-1",
                fromPersonId = "person-wrong",
                toPersonId = "person-right",
                payloadJson = """{"identity_type":"name","normalized_value":"right-person","display_name_raw":"Right Person"}""",
            ),
        )

        coVerify(exactly = 1) {
            personIndexDao.reassignSourceEventParticipantById(
                userId = USER_ID,
                participantId = "participant-1",
                personId = "person-right",
                identityType = "name",
                normalizedValue = "right-person",
                displayNameRaw = "Right Person",
                confidence = 1.0,
            )
        }
        coVerify(exactly = 1) {
            personIndexDao.deleteCommitmentParticipantsForCommitments(USER_ID, listOf("commitment-1"))
        }
        coVerify(exactly = 1) {
            personIndexDao.upsertCommitmentParticipants(
                match { rows ->
                    rows.size == 1 &&
                        rows.first().commitmentId == "commitment-1" &&
                        rows.first().personId == "person-right"
                },
            )
        }
        coVerify(exactly = 1) { personIndexDao.deleteInteractionsForSourceEvent(USER_ID, "source-1") }
        coVerify(exactly = 1) { personIndexDao.deleteInteractionsForCommitment(USER_ID, "commitment-1") }
        coVerify(exactly = 1) { personIndexDao.upsertDirtySources(any()) }
    }

    @Test
    fun `participant ignore removes graph links for the source participant`() = runTest {
        val sourceParticipant = participant(personId = "person-wrong")
        val commitment = commitment(id = "commitment-1", sourceRef = "raw:source-1")
        coEvery {
            personIndexDao.findSourceEventParticipantById(USER_ID, "participant-1")
        } returns sourceParticipant
        coEvery {
            commitmentDao.findLiveReviewableCommitmentsForSourceRefs(
                userId = USER_ID,
                sourceType = "gmail",
                sourceRefs = listOf("raw:source-1", "source-1", "gmail-message-1"),
            )
        } returns listOf(commitment)

        materializer.apply(
            correction(
                action = "participant_ignore",
                targetId = "participant-1",
                sourceEventId = "source-1",
                fromPersonId = "person-wrong",
            ),
        )

        coVerify(exactly = 1) {
            personIndexDao.ignoreSourceEventParticipantById(
                userId = USER_ID,
                participantId = "participant-1",
                confidence = 1.0,
            )
        }
        coVerify(exactly = 1) {
            personIndexDao.deleteCommitmentParticipantsForCommitments(USER_ID, listOf("commitment-1"))
        }
        coVerify(exactly = 1) { personIndexDao.deleteInteractionsForSourceEvent(USER_ID, "source-1") }
        coVerify(exactly = 1) { personIndexDao.deleteInteractionsForCommitment(USER_ID, "commitment-1") }
        coVerify(exactly = 1) { personIndexDao.upsertDirtySources(any()) }
    }

    @Test
    fun `commitment not action soft deletes commitment and clears graph rows`() = runTest {
        materializer.apply(
            correction(
                action = "commitment_not_action",
                targetId = "commitment-1",
                commitmentId = "commitment-1",
            ),
        )

        coVerify(exactly = 1) { commitmentDao.softDelete("commitment-1", USER_ID, any()) }
        coVerify(exactly = 1) {
            personIndexDao.deleteCommitmentParticipantsForCommitments(USER_ID, listOf("commitment-1"))
        }
        coVerify(exactly = 1) { personIndexDao.deleteInteractionsForCommitment(USER_ID, "commitment-1") }
        coVerify(exactly = 1) { personIndexDao.upsertDirtySources(any()) }
    }

    @Test
    fun `schedule hide writes a local tombstone synced by the correction log`() = runTest {
        val tombstoneSlot = slot<ScheduleRowTombstoneEntity>()

        materializer.apply(
            correction(
                action = "schedule_hide",
                targetType = "calendar_event",
                targetId = "calendar-1",
                sourceEventId = "calendar-1",
                payloadJson = """{"row_type":"calendar_event","source_type":"google_calendar","source_ref":"provider-event-1"}""",
            ),
        )

        coVerify(exactly = 1) { scheduleRowTombstoneDao.upsert(capture(tombstoneSlot)) }
        assertEquals("$USER_ID:calendar-1", tombstoneSlot.captured.id)
        assertEquals("calendar_event", tombstoneSlot.captured.rowType)
        assertEquals("calendar-1", tombstoneSlot.captured.sourceEventId)
        assertEquals("google_calendar", tombstoneSlot.captured.sourceType)
        assertEquals("provider-event-1", tombstoneSlot.captured.sourceRef)
        assertEquals(UserCorrectionSyncStatus.SYNCED, tombstoneSlot.captured.syncStatus)
    }

    private fun correction(
        action: String,
        targetType: String = "source_event_participant",
        targetId: String,
        sourceEventId: String? = null,
        commitmentId: String? = null,
        fromPersonId: String? = null,
        toPersonId: String? = null,
        payloadJson: String = "{}",
    ): UserCorrectionEntity =
        UserCorrectionEntity(
            id = "correction-$action-$targetId",
            userId = USER_ID,
            domain = "person",
            action = action,
            targetType = targetType,
            targetId = targetId,
            sourceEventId = sourceEventId,
            commitmentId = commitmentId,
            fromPersonId = fromPersonId,
            toPersonId = toPersonId,
            conflictKey = "$action:$targetId",
            idempotencyKey = "android:$action:$targetId",
            targetFingerprint = null,
            payloadJson = payloadJson,
            status = UserCorrectionStatus.ACTIVE,
            syncStatus = UserCorrectionSyncStatus.PENDING,
            failureReason = null,
            clientCreatedAt = NOW,
            appliedAt = null,
            createdAt = NOW,
            updatedAt = NOW,
        )

    private fun participant(personId: String?): SourceEventParticipantEntity =
        SourceEventParticipantEntity(
            id = "participant-1",
            userId = USER_ID,
            sourceEventId = "source-1",
            sourceType = "gmail",
            sourceRef = "gmail-message-1",
            personId = personId,
            role = "sender",
            relationToUser = "counterparty",
            identityType = "name",
            normalizedValue = "wrong-person",
            displayNameRaw = "Wrong Person",
            emailRaw = null,
            phoneRaw = null,
            organizationRaw = null,
            titleRaw = null,
            evidence = "email sender",
            confidence = 0.82,
            resolutionStatus = "person_resolved",
            createdAt = NOW,
        )

    private fun commitment(id: String, sourceRef: String): CommitmentEntity =
        CommitmentEntity(
            id = id,
            userId = USER_ID,
            itemType = CommitmentItemType.ACTION,
            direction = "give",
            counterpartyRaw = "Wrong Person",
            counterpartyRef = "wrong-person",
            title = "Send deck",
            description = null,
            quote = "Please send the deck.",
            sourceEventTitle = "Deck",
            sourceEventOccurredAt = NOW,
            dueAt = null,
            dueHint = null,
            sourceType = "gmail",
            sourceRef = sourceRef,
            sourceEventId = "source-1",
            confidence = 0.82,
            createdAt = NOW,
            updatedAt = NOW,
        )

    private companion object {
        const val USER_ID: String = "user-1"
        val NOW: Instant = Instant.parse("2026-06-02T00:00:00Z")
    }
}
