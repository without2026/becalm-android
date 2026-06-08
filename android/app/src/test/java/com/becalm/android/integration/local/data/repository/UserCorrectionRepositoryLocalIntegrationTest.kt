package com.becalm.android.integration.local.data.repository

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.db.entity.UserCorrectionEntity
import com.becalm.android.data.local.db.entity.UserCorrectionStatus
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.UserCorrectionBatchResponseDto
import com.becalm.android.data.remote.dto.UserCorrectionDto
import com.becalm.android.data.remote.dto.UserCorrectionFailedDto
import com.becalm.android.data.remote.dto.UserCorrectionsResponseDto
import com.becalm.android.data.repository.UserCorrectionMaterializer
import com.becalm.android.data.repository.UserCorrectionRepository
import com.becalm.android.data.repository.UserCorrectionRepositoryImpl
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UserCorrectionRepositoryLocalIntegrationTest {
    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val api = mockk<RailwayApi>(relaxed = true)
    private val workScheduler = mockk<WorkScheduler>(relaxed = true)
    private val repository = UserCorrectionRepositoryImpl(
        dao = db.userCorrectionDao(),
        materializer = UserCorrectionMaterializer(
            personIndexDao = db.personIndexDao(),
            commitmentDao = db.commitmentDao(),
            scheduleRowTombstoneDao = db.scheduleRowTombstoneDao(),
        ),
        api = api,
        workScheduler = workScheduler,
        logger = RecordingLogger(),
    )

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `uploadBatch preserves one ten hundred correction commands and partial failures`() = runTest {
        for (scale in listOf(1, 10, 100)) {
            val requestSlot = slot<com.becalm.android.data.remote.dto.UserCorrectionBatchRequestDto>()
            coEvery {
                api.uploadUserCorrectionsBatch(request = capture(requestSlot))
            } returns Response.success(
                UserCorrectionBatchResponseDto(
                    acknowledged = scale - 1,
                    failed = listOf(
                        UserCorrectionFailedDto(
                            id = "correction-$scale-0",
                            idempotencyKey = "idem-$scale-0",
                            error = "validation_failed",
                            message = "target_id is required",
                            retryable = false,
                        ),
                    ),
                    data = emptyList(),
                ),
            )

            val result = repository.uploadBatch((0 until scale).map { correctionEntity(scale, it) })

            assertTrue(result is BecalmResult.Success)
            val body = (result as BecalmResult.Success<UserCorrectionRepository.BatchResponse>).value
            assertEquals(scale - 1, body.acknowledged)
            assertEquals("validation_failed", body.failed.single().error)
            assertEquals(false, body.failed.single().retryable)
            assertEquals(scale, requestSlot.captured.items.size)
            assertEquals("participant_reassign", requestSlot.captured.items[0].action)
        }
    }

    @Test
    fun `refreshSince mirrors one ten hundred correction rows into Room`() = runTest {
        for (scale in listOf(1, 10, 100)) {
            db.userCorrectionDao().deleteAllForUser(USER_ID)
            coEvery {
                api.getUserCorrections(
                    cursor = null,
                    limit = any(),
                    since = null,
                    status = null,
                )
            } returns Response.success(
                UserCorrectionsResponseDto(
                    data = (0 until scale).map { correctionDto(scale, it) },
                    cursor = "cursor-$scale",
                    hasMore = false,
                ),
            )

            val result = repository.refreshSince(userId = USER_ID, since = null)

            assertTrue(result is BecalmResult.Success)
            val stats = (result as BecalmResult.Success<UserCorrectionRepository.RefreshStats>).value
            assertEquals(scale, stats.fetched)
            assertEquals(scale, stats.upserted)
            assertEquals("cursor-$scale", stats.nextCursor)
            val rows = db.userCorrectionDao().findActiveForUser(USER_ID)
            assertEquals(scale, rows.size)
            assertEquals("idem-$scale-0", rows.first().idempotencyKey)
            assertEquals("""{"source_type":"gmail","role":"sender"}""", rows.first().payloadJson)
        }
    }

    private fun correctionEntity(scale: Int, index: Int): UserCorrectionEntity {
        val minute = "%02d".format(index % 60)
        val now = Instant.parse("2026-06-03T01:$minute:00Z")
        return UserCorrectionEntity(
            id = "correction-$scale-$index",
            userId = USER_ID,
            domain = "person",
            action = "participant_reassign",
            targetType = "source_event_participant",
            targetId = "participant-$scale-$index",
            sourceEventId = "source-event-$scale-$index",
            commitmentId = null,
            fromPersonId = "person-old-$index",
            toPersonId = "person-new-$index",
            conflictKey = "participant:$scale:$index",
            idempotencyKey = "idem-$scale-$index",
            targetFingerprint = "fingerprint-$scale-$index",
            payloadJson = """{"source_type":"gmail","role":"sender"}""",
            status = UserCorrectionStatus.ACTIVE,
            failureReason = null,
            clientCreatedAt = now,
            appliedAt = now,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun correctionDto(scale: Int, index: Int): UserCorrectionDto =
        "%02d".format(index % 60).let { minute ->
            UserCorrectionDto(
                id = "correction-$scale-$index",
                userId = USER_ID,
                domain = "person",
                action = "participant_reassign",
                targetType = "source_event_participant",
                targetId = "participant-$scale-$index",
                sourceEventId = "source-event-$scale-$index",
                commitmentId = null,
                fromPersonId = "person-old-$index",
                toPersonId = "person-new-$index",
                conflictKey = "participant:$scale:$index",
                idempotencyKey = "idem-$scale-$index",
                targetFingerprint = "fingerprint-$scale-$index",
                payload = mapOf("source_type" to "gmail", "role" to "sender"),
                status = UserCorrectionStatus.ACTIVE,
                failureReason = null,
                clientCreatedAt = Instant.parse("2026-06-03T01:$minute:00Z"),
                appliedAt = Instant.parse("2026-06-03T02:$minute:00Z"),
                createdAt = Instant.parse("2026-06-03T01:$minute:00Z"),
                updatedAt = Instant.parse("2026-06-03T03:$minute:00Z"),
            )
        }

    private companion object {
        private const val USER_ID = "user-1"
    }
}
