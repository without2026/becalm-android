package com.becalm.android.integration.local.data

import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity
import com.becalm.android.data.local.db.entity.PersonActionMutationQueueEntity
import com.becalm.android.data.local.db.entity.PersonActionSyncStateEntity
import com.becalm.android.integration.local.LocalIntegrationSupport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PersonActionDaoLocalIntegrationTest {
    private lateinit var db: BeCalmDatabase

    @Before
    fun setUp() {
        db = LocalIntegrationSupport.inMemoryDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun observeSyncStateReturnsScopedWatermark() = runTest {
        val dao = db.personActionDao()
        dao.upsertSyncState(syncState(userId = "user-1", surfaceKey = "person", recomputeState = "caught_up"))
        dao.upsertSyncState(syncState(userId = "user-2", surfaceKey = "person", recomputeState = "other_user"))

        val row = dao.observeSyncState(userId = "user-1", surfaceKey = "person", status = "active").first()

        assertEquals("user-1", row?.userId)
        assertEquals("person", row?.surfaceKey)
        assertEquals("caught_up", row?.recomputeState)
        assertEquals(Instant.parse("2026-06-03T02:00:00Z"), row?.serverWatermark)
        assertNull(dao.observeSyncState(userId = "user-1", surfaceKey = "schedule", status = "active").first())
    }

    @Test
    fun observeActiveForSurfaceReturnsOnboardingSourceRepairRows() = runTest {
        val dao = db.personActionDao()
        dao.upsertActionItems(
            listOf(
                actionItem(id = "pa-source-repair", surfacesCsv = "onboarding,source_repair"),
                actionItem(id = "pa-person", surfacesCsv = "person", actionKind = "reply", sourceType = "gmail"),
            ),
        )

        val onboardingRows = dao.observeActiveForSurface(userId = "user-1", surface = "onboarding", limit = 10).first()
        val personRows = dao.observeActiveForSurface(userId = "user-1", surface = "person", limit = 10).first()

        assertEquals(listOf("pa-source-repair"), onboardingRows.map { it.id })
        assertEquals("reconnect_source", onboardingRows.single().actionKind)
        assertEquals("source_status", onboardingRows.single().primaryEvidenceKind)
        assertEquals("connection-1", onboardingRows.single().primaryEvidenceId)
        assertEquals(listOf("pa-person"), personRows.map { it.id })
    }

    @Test
    fun observeActiveForSurfaceUsesExactCsvTokenMatches() = runTest {
        val dao = db.personActionDao()
        dao.upsertActionItems(
            listOf(
                actionItem(id = "pa-person-high", surfacesCsv = "person", urgencyScore = 90.0),
                actionItem(id = "pa-onboarding-person", surfacesCsv = "onboarding,person", urgencyScore = 80.0),
                actionItem(id = "pa-person-detail", surfacesCsv = "person_detail", urgencyScore = 99.0),
                actionItem(id = "pa-superperson", surfacesCsv = "superperson", urgencyScore = 98.0),
                actionItem(id = "pa-person2", surfacesCsv = "person2,commitment", urgencyScore = 97.0),
            ),
        )

        val personRows = dao.observeActiveForSurface(userId = "user-1", surface = "person", limit = 10).first()

        assertEquals(listOf("pa-person-high", "pa-onboarding-person"), personRows.map { it.id })
        assertEquals(listOf("pa-person-detail"), dao.observeActiveForSurface(userId = "user-1", surface = "person_detail", limit = 10).first().map { it.id })
        assertEquals(listOf("pa-superperson"), dao.observeActiveForSurface(userId = "user-1", surface = "superperson", limit = 10).first().map { it.id })
        assertEquals(listOf("pa-person2"), dao.observeActiveForSurface(userId = "user-1", surface = "person2", limit = 10).first().map { it.id })
    }

    @Test
    fun observeActiveForEntityFiltersExcludeOtherUsersEntitiesAndInactiveRows() = runTest {
        val dao = db.personActionDao()
        dao.upsertActionItems(
            listOf(
                actionItem(
                    id = "pa-person-1",
                    personId = "person-1",
                    commitmentId = "commitment-1",
                    calendarEventId = "calendar-1",
                    urgencyScore = 90.0,
                ),
                actionItem(id = "pa-person-2", personId = "person-2", commitmentId = "commitment-2", calendarEventId = "calendar-2"),
                actionItem(id = "pa-other-user", userId = "user-2", personId = "person-1", commitmentId = "commitment-1", calendarEventId = "calendar-1"),
                actionItem(id = "pa-completed", personId = "person-1", commitmentId = "commitment-1", calendarEventId = "calendar-1", status = "completed"),
                actionItem(
                    id = "pa-person-1-lower",
                    personId = "person-1",
                    commitmentId = "commitment-1",
                    calendarEventId = "calendar-1",
                    urgencyScore = 70.0,
                    updatedAt = Instant.parse("2026-06-03T02:00:01Z"),
                ),
            ),
        )

        val personRows = dao.observeActiveForPerson(userId = "user-1", personId = "person-1", limit = 10).first()
        val commitmentRows = dao.observeActiveForCommitment(userId = "user-1", commitmentId = "commitment-1", limit = 10).first()
        val calendarRows = dao.observeActiveForCalendarEvent(userId = "user-1", calendarEventId = "calendar-1", limit = 10).first()

        assertEquals(listOf("pa-person-1", "pa-person-1-lower"), personRows.map { it.id })
        assertEquals(listOf("pa-person-1", "pa-person-1-lower"), commitmentRows.map { it.id })
        assertEquals(listOf("pa-person-1", "pa-person-1-lower"), calendarRows.map { it.id })
    }

    @Test
    fun observeActiveForEntityFiltersBoundOneTenHundredRows() = runTest {
        val dao = db.personActionDao()

        for (count in listOf(1, 10, 100)) {
            val userId = "scale-user-$count"
            val personId = "person-scale-$count"
            val commitmentId = "commitment-scale-$count"
            val calendarEventId = "calendar-scale-$count"
            dao.upsertActionItems(
                (1..count).map { index ->
                    actionItem(
                        id = "$userId-pa-$index",
                        userId = userId,
                        personId = personId,
                        commitmentId = commitmentId,
                        calendarEventId = calendarEventId,
                        urgencyScore = (100 - index).toDouble(),
                    )
                } + actionItem(id = "$userId-other-person", userId = userId, personId = "other-person"),
            )

            assertEquals(count, dao.observeActiveForPerson(userId = userId, personId = personId, limit = 100).first().size)
            assertEquals(count, dao.observeActiveForCommitment(userId = userId, commitmentId = commitmentId, limit = 100).first().size)
            assertEquals(count, dao.observeActiveForCalendarEvent(userId = userId, calendarEventId = calendarEventId, limit = 100).first().size)
            assertEquals(minOf(count, 10), dao.observeActiveForPerson(userId = userId, personId = personId, limit = 10).first().size)
        }
    }

    @Test
    fun applyFeedSnapshotFullRefreshClearsScopedRowsAndPreservesOtherSurfaces() = runTest {
        val dao = db.personActionDao()
        dao.upsertActionItems(
            listOf(
                actionItem(id = "pa-person-old", surfacesCsv = "person,commitment"),
                actionItem(id = "pa-schedule-only", surfacesCsv = "schedule"),
            ),
        )

        dao.applyFeedSnapshot(
            userId = "user-1",
            rows = emptyList(),
            deletedIds = emptyList(),
            replaceScope = true,
            surface = "person",
            syncState = syncState(surfaceKey = "person", recomputeState = "caught_up"),
        )

        assertEquals(emptyList<String>(), dao.observeActiveForSurface(userId = "user-1", surface = "person", limit = 10).first().map { it.id })
        assertEquals(listOf("pa-schedule-only"), dao.observeActiveForSurface(userId = "user-1", surface = "schedule", limit = 10).first().map { it.id })
        assertEquals("caught_up", dao.observeSyncState(userId = "user-1", surfaceKey = "person", status = "active").first()?.recomputeState)
    }

    @Test
    fun applyFeedSnapshotFullRefreshClearsOnlyExactSurfaceTokenRows() = runTest {
        val dao = db.personActionDao()
        dao.upsertActionItems(
            listOf(
                actionItem(id = "pa-person-old", surfacesCsv = "person"),
                actionItem(id = "pa-onboarding-person", surfacesCsv = "onboarding,person"),
                actionItem(id = "pa-person-detail", surfacesCsv = "person_detail"),
                actionItem(id = "pa-superperson", surfacesCsv = "superperson"),
                actionItem(id = "pa-person2", surfacesCsv = "person2,commitment"),
            ),
        )

        dao.applyFeedSnapshot(
            userId = "user-1",
            rows = emptyList(),
            deletedIds = emptyList(),
            replaceScope = true,
            surface = "person",
            syncState = syncState(surfaceKey = "person", recomputeState = "caught_up"),
        )

        assertEquals(emptyList<String>(), dao.observeActiveForSurface(userId = "user-1", surface = "person", limit = 10).first().map { it.id })
        assertEquals(listOf("pa-person-detail"), dao.observeActiveForSurface(userId = "user-1", surface = "person_detail", limit = 10).first().map { it.id })
        assertEquals(listOf("pa-superperson"), dao.observeActiveForSurface(userId = "user-1", surface = "superperson", limit = 10).first().map { it.id })
        assertEquals(listOf("pa-person2"), dao.observeActiveForSurface(userId = "user-1", surface = "person2", limit = 10).first().map { it.id })
    }

    @Test
    fun applyFeedSnapshotDeltaDeletesTombstonesWithoutClearingExistingRows() = runTest {
        val dao = db.personActionDao()
        dao.upsertActionItems(
            listOf(
                actionItem(id = "pa-keep", surfacesCsv = "person"),
                actionItem(id = "pa-delete", surfacesCsv = "person"),
            ),
        )

        dao.applyFeedSnapshot(
            userId = "user-1",
            rows = listOf(actionItem(id = "pa-new", surfacesCsv = "person")),
            deletedIds = listOf("pa-delete"),
            replaceScope = false,
            surface = "person",
            syncState = syncState(surfaceKey = "person", recomputeState = "caught_up"),
        )

        assertEquals(listOf("pa-keep", "pa-new"), dao.observeActiveForSurface(userId = "user-1", surface = "person", limit = 10).first().map { it.id }.sorted())
    }

    @Test
    fun pendingMutationQueriesSeparateOutstandingCountFromRetryEligibility() = runTest {
        val dao = db.personActionDao()
        val now = Instant.parse("2026-06-03T02:00:00Z")
        dao.upsertMutation(mutation(index = 1, syncStatus = "pending"))
        dao.upsertMutation(mutation(index = 2, syncStatus = "failed", nextAttemptAt = Instant.parse("2026-06-03T01:59:00Z")))
        dao.upsertMutation(mutation(index = 3, syncStatus = "failed", nextAttemptAt = Instant.parse("2026-06-03T02:05:00Z")))
        dao.upsertMutation(mutation(index = 4, syncStatus = "synced"))
        dao.upsertMutation(mutation(index = 5, syncStatus = "failed", nextAttemptAt = null))

        val outstanding = dao.observePendingMutationCount(userId = "user-1").first()
        val retryableNow = dao.findPendingMutations(userId = "user-1", now = now, limit = 20)

        assertEquals(3, outstanding)
        assertEquals(listOf("client-1", "client-2"), retryableNow.map { it.clientMutationId })
    }

    @Test
    fun pendingMutationQueryBoundsOneTenHundredRows() = runTest {
        val dao = db.personActionDao()
        val now = Instant.parse("2026-06-03T02:00:00Z")

        for (count in listOf(1, 10, 100)) {
            val userId = "scale-user-$count"
            for (index in 1..count) {
                dao.upsertMutation(mutation(index = index, userId = userId))
            }

            assertEquals(count, dao.observePendingMutationCount(userId = userId).first())
            assertEquals(minOf(count, 20), dao.findPendingMutations(userId = userId, now = now, limit = 20).size)
        }
    }
}

private fun syncState(
    userId: String = "user-1",
    surfaceKey: String,
    recomputeState: String,
): PersonActionSyncStateEntity =
    PersonActionSyncStateEntity(
        userId = userId,
        surfaceKey = surfaceKey,
        status = "active",
        serverWatermark = Instant.parse("2026-06-03T02:00:00Z"),
        recomputeState = recomputeState,
        capacityState = null,
        lastSyncedAt = Instant.parse("2026-06-03T02:00:01Z"),
        updatedAt = Instant.parse("2026-06-03T02:00:02Z"),
    )

private fun actionItem(
    id: String,
    userId: String = "user-1",
    personId: String? = null,
    surfacesCsv: String = "person",
    actionKind: String = "reconnect_source",
    sourceType: String? = "gmail",
    commitmentId: String? = null,
    calendarEventId: String? = null,
    status: String = "active",
    urgencyScore: Double? = null,
    updatedAt: Instant = Instant.parse("2026-06-03T02:00:02Z"),
): PersonActionItemCacheEntity =
    PersonActionItemCacheEntity(
        id = id,
        userId = userId,
        personId = personId,
        personDisplayName = null,
        personSortKey = null,
        surfacesCsv = surfacesCsv,
        actionKind = actionKind,
        status = status,
        title = if (actionKind == "reconnect_source") "Work Gmail 재연결" else "Jane Kim follow-up",
        primaryVerb = if (actionKind == "reconnect_source") "재연결" else "답장",
        shortReason = if (actionKind == "reconnect_source") "gmail 연결 인증이 필요합니다" else "근거: proposal thread",
        commitmentId = commitmentId,
        calendarEventId = calendarEventId,
        sourceEventId = null,
        sourceType = sourceType,
        sourceRef = if (actionKind == "reconnect_source") "connection-1" else "gmail-msg-1",
        dueAt = null,
        dueHint = null,
        dueIsApproximate = false,
        staleAfter = Instant.parse("2026-06-04T02:00:00Z"),
        urgencyScore = urgencyScore ?: if (actionKind == "reconnect_source") 96.0 else 80.0,
        importanceScore = if (actionKind == "reconnect_source") 95.0 else 70.0,
        confidence = 1.0,
        reasonCodesCsv = if (actionKind == "reconnect_source") {
            "source_health,source:gmail,source_status:needs_reauth,retryable:user_action"
        } else {
            "source:gmail"
        },
        inputWatermark = Instant.parse("2026-06-03T02:00:00Z"),
        serverWatermark = Instant.parse("2026-06-03T03:00:00Z"),
        computedAt = Instant.parse("2026-06-03T02:00:01Z"),
        updatedAt = updatedAt,
        snoozedUntil = null,
        completedAt = null,
        dismissedAt = null,
        primaryEvidenceKind = if (actionKind == "reconnect_source") "source_status" else "source_event",
        primaryEvidenceId = if (actionKind == "reconnect_source") "connection-1" else "source-event-1",
        primaryEvidenceSourceRef = if (actionKind == "reconnect_source") "connection-1" else "gmail-msg-1",
        primaryEvidenceOccurredAt = Instant.parse("2026-06-03T02:00:00Z"),
        primaryEvidenceLabel = if (actionKind == "reconnect_source") "Work Gmail" else "Proposal thread",
        primaryEvidenceQuote = if (actionKind == "reconnect_source") "provider token expired" else "Please reply",
    )

private fun mutation(
    index: Int,
    userId: String = "user-1",
    syncStatus: String = "pending",
    nextAttemptAt: Instant? = null,
): PersonActionMutationQueueEntity =
    PersonActionMutationQueueEntity(
        id = "$userId-mutation-$index",
        userId = userId,
        actionItemId = "pa-$index",
        clientMutationId = "client-$index",
        mutationKind = "state_patch",
        payloadJson = """{"status":"completed","updated_at":"2026-06-03T02:00:00Z"}""",
        syncStatus = syncStatus,
        attemptCount = 0,
        lastErrorCode = null,
        lastErrorClientAction = null,
        nextAttemptAt = nextAttemptAt,
        createdAt = Instant.parse("2026-06-03T01:00:00Z"),
        updatedAt = Instant.parse("2026-06-03T01:00:00Z"),
    )
