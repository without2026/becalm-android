package com.becalm.android.unit.data.repository

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.addBecalmAdapters
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.dao.SourceEventAnchorDao
import com.becalm.android.data.local.db.dao.PersonActionDao
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity
import com.becalm.android.data.local.db.entity.PersonActionMutationQueueEntity
import com.becalm.android.data.local.db.entity.PersonActionSyncStateEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SourceEventAnchorEntity
import com.becalm.android.data.local.db.entity.SourceEventAnchorOrigin
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.CalendarWriteJobResponseDto
import com.becalm.android.data.remote.dto.PersonActionDraftDto
import com.becalm.android.data.remote.dto.PersonActionDraftProvenanceDto
import com.becalm.android.data.remote.dto.PersonActionDraftRequestDto
import com.becalm.android.data.remote.dto.PersonActionDraftResponseDto
import com.becalm.android.data.remote.dto.PersonActionDraftSafetyDto
import com.becalm.android.data.remote.dto.PersonActionEvidenceOriginalDetailDto
import com.becalm.android.data.remote.dto.PersonActionEvidenceOriginalDto
import com.becalm.android.data.remote.dto.PersonActionEvidenceOriginalResponseDto
import com.becalm.android.data.remote.dto.PersonActionEvidenceRefDto
import com.becalm.android.data.remote.dto.PersonActionFeedResponseDto
import com.becalm.android.data.remote.dto.PersonActionCapacityStateDto
import com.becalm.android.data.remote.dto.PersonActionEmptyStateDto
import com.becalm.android.data.remote.dto.PersonActionItemDto
import com.becalm.android.data.remote.dto.PersonActionProviderWriteDto
import com.becalm.android.data.remote.dto.PersonActionRecoveryActionDto
import com.becalm.android.data.remote.dto.PersonActionStatePatchDto
import com.becalm.android.data.remote.dto.SinglePersonActionItemResponseDto
import com.becalm.android.data.repository.PersonActionDraftEvidenceRef
import com.becalm.android.data.repository.PersonActionRepositoryImpl
import com.becalm.android.data.repository.PersonActionProviderWriteRequest
import com.becalm.android.data.repository.SourceOriginalContext
import com.becalm.android.data.repository.SourceOriginalResolver
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import javax.inject.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

class PersonActionRepositorySpecTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun completeActionWritesOutboxThenSyncsAndUpdatesCache() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        val request = slot<PersonActionStatePatchDto>()
        coEvery {
            api.patchPersonActionItem("pa-1", any(), capture(request))
        } returns Response.success(SinglePersonActionItemResponseDto(data = actionDto(status = "completed")))
        val repository = repository(dao, api)

        val result = repository.completeAction(userId = "user-1", actionItemId = "pa-1")

        check(result is BecalmResult.Success)
        assertEquals(1, result.value.queued)
        assertEquals(1, result.value.synced)
        assertEquals("completed", request.captured.status)
        assertEquals("synced", dao.mutations.single().syncStatus)
        assertEquals("completed", dao.cachedActions.single().status)
    }

    @Test
    fun completeActionWithProviderWriteSendsExplicitCalendarWritePayload() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        val request = slot<PersonActionStatePatchDto>()
        coEvery {
            api.patchPersonActionItem("pa-schedule-1", any(), capture(request))
        } returns Response.success(
            SinglePersonActionItemResponseDto(
                data = scheduleActionDto(
                    status = "completed",
                    providerWriteState = "queued",
                    providerWriteJobId = "job-1",
                ),
            ),
        )
        val repository = repository(dao, api)

        val result = repository.completeActionWithProviderWrite(
            userId = "user-1",
            actionItemId = "pa-schedule-1",
            providerWrite = PersonActionProviderWriteRequest(
                provider = "google_calendar",
                sourceConnectionId = "conn-calendar-write",
                scheduleEventLinkId = "schedule-link-1",
            ),
        )

        check(result is BecalmResult.Success)
        assertEquals("job-1", result.value.providerWriteJobId)
        val providerWrite = requireNotNull(request.captured.providerWrite)
        assertEquals("completed", request.captured.status)
        assertEquals("add_to_calendar", providerWrite.kind)
        assertEquals("google_calendar", providerWrite.provider)
        assertEquals("conn-calendar-write", providerWrite.sourceConnectionId)
        assertEquals("schedule-link-1", providerWrite.scheduleEventLinkId)
        assertEquals("synced", dao.mutations.single().syncStatus)
    }

    @Test
    fun fetchCalendarWriteJobStatusMapsDirectEndpointResponse() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        coEvery {
            api.getCalendarWriteJob("job-1")
        } returns Response.success(
            CalendarWriteJobResponseDto(
                jobId = "job-1",
                status = "needs_reauth",
                accepted = true,
                retryAfterSeconds = null,
                provider = "google_calendar",
                writeKind = "insert_event",
                actionItemId = "pa-schedule-1",
                scheduleEventLinkId = "schedule-link-1",
                sourceConnectionId = "conn-calendar-write",
                attempts = 1,
                errorCode = "provider_auth_expired",
                errorMessage = "calendar reconnect required",
                clientAction = "connect_calendar",
            ),
        )
        val repository = repository(dao, api)

        val result = repository.fetchCalendarWriteJobStatus(userId = "user-1", jobId = "job-1")

        check(result is BecalmResult.Success)
        assertEquals("needs_reauth", result.value.status)
        assertEquals("google_calendar", result.value.provider)
        assertEquals("schedule-link-1", result.value.scheduleEventLinkId)
        assertEquals("connect_calendar", result.value.clientAction)
    }

    @Test
    fun completeActionKeepsRetryableMutationWhenServerFails() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        coEvery {
            api.patchPersonActionItem("pa-1", any(), any())
        } returns Response.error(503, """{"error":"upstream_unavailable"}""".toResponseBody())
        val repository = repository(dao, api)

        val result = repository.completeAction(userId = "user-1", actionItemId = "pa-1")

        check(result is BecalmResult.Success)
        assertEquals(1, result.value.retryable)
        val mutation = dao.mutations.single()
        assertEquals("failed", mutation.syncStatus)
        assertEquals("server_error", mutation.lastErrorCode)
        assertEquals("retry_later", mutation.lastErrorClientAction)
        assertNotNull(mutation.nextAttemptAt)
    }

    @Test
    fun completeActionUsesRetryAfterHeaderForRateLimitBackoff() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        val rawResponse = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("https://becalm.test/v1/person_action_items/pa-1").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(429)
            .message("Too Many Requests")
            .header("Retry-After", "60")
            .build()
        coEvery {
            api.patchPersonActionItem("pa-1", any(), any())
        } returns Response.error("""{"error":"quota_exceeded","client_action":"retry_later"}""".toResponseBody(), rawResponse)
        val repository = repository(dao, api)
        val before = Clock.System.now()

        val result = repository.completeAction(userId = "user-1", actionItemId = "pa-1")

        check(result is BecalmResult.Success)
        assertEquals(1, result.value.retryable)
        val mutation = dao.mutations.single()
        assertEquals("failed", mutation.syncStatus)
        assertEquals("rate_limited", mutation.lastErrorCode)
        assertEquals("retry_later", mutation.lastErrorClientAction)
        val nextAttemptAt = mutation.nextAttemptAt
        assertNotNull(nextAttemptAt)
        check(nextAttemptAt != null)
        assertTrue(nextAttemptAt > before.plus(30.seconds))
    }

    @Test
    fun completeActionKeepsIdempotencyConflictTerminalAndRefreshesFeed() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        coEvery {
            api.patchPersonActionItem("pa-1", any(), any())
        } returns Response.error(
            409,
            """{"error":"idempotency_conflict","client_action":"discard_local_mutation"}""".toResponseBody(),
        )
        coEvery {
            api.getPersonActionItems(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Response.success(feedDto(data = listOf(actionDto(status = "active"))))
        val repository = repository(dao, api)

        val result = repository.completeAction(userId = "user-1", actionItemId = "pa-1")

        check(result is BecalmResult.Success)
        assertEquals(1, result.value.queued)
        assertEquals(0, result.value.synced)
        assertEquals(0, result.value.retryable)
        assertEquals(1, result.value.failed)
        val mutation = dao.mutations.single()
        assertEquals("failed", mutation.syncStatus)
        assertEquals("idempotency_conflict", mutation.lastErrorCode)
        assertEquals("discard_local_mutation", mutation.lastErrorClientAction)
        assertEquals(null, mutation.nextAttemptAt)
        assertEquals("active", dao.cachedActions.single().status)
        assertEquals("__all__", dao.syncStates.single().surfaceKey)
        coVerify(exactly = 1) { api.getPersonActionItems(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun completeActionUsesTypedConflictEnvelopeBeforeMessageText() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        coEvery {
            api.patchPersonActionItem("pa-1", any(), any())
        } returns Response.error(
            409,
            """
            {
              "error": "stale_action",
              "message": "Legacy diagnostics mention idempotency_conflict but the typed recovery action is refresh.",
              "retryable": false,
              "client_action": "refresh_action"
            }
            """.trimIndent().toResponseBody(),
        )
        coEvery {
            api.getPersonActionItems(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Response.success(feedDto(data = listOf(actionDto(status = "active"))))
        val repository = repository(dao, api)

        val result = repository.completeAction(userId = "user-1", actionItemId = "pa-1")

        check(result is BecalmResult.Success)
        assertEquals(1, result.value.queued)
        assertEquals(1, result.value.synced)
        assertEquals(0, result.value.retryable)
        assertEquals(0, result.value.failed)
        val mutation = dao.mutations.single()
        assertEquals("synced", mutation.syncStatus)
        assertEquals(null, mutation.lastErrorCode)
        assertEquals(null, mutation.lastErrorClientAction)
        assertEquals(null, mutation.nextAttemptAt)
        assertEquals("active", dao.cachedActions.single().status)
        assertEquals("__all__", dao.syncStates.single().surfaceKey)
        coVerify(exactly = 1) { api.getPersonActionItems(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun completeActionRefreshesAndReconcilesStaleActionMutation() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        coEvery {
            api.patchPersonActionItem("pa-1", any(), any())
        } returns Response.error(
            409,
            """{"error":"stale_action","client_action":"refresh_action"}""".toResponseBody(),
        )
        coEvery {
            api.getPersonActionItems(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Response.success(feedDto(data = listOf(actionDto(status = "active"))))
        val repository = repository(dao, api)

        val result = repository.completeAction(userId = "user-1", actionItemId = "pa-1")

        check(result is BecalmResult.Success)
        assertEquals(1, result.value.queued)
        assertEquals(1, result.value.synced)
        assertEquals(0, result.value.failed)
        val mutation = dao.mutations.single()
        assertEquals("synced", mutation.syncStatus)
        assertEquals(null, mutation.lastErrorCode)
        assertEquals(null, mutation.lastErrorClientAction)
        assertEquals(null, mutation.nextAttemptAt)
        assertEquals("active", dao.cachedActions.single().status)
        assertEquals("__all__", dao.syncStates.single().surfaceKey)
        coVerify(exactly = 1) { api.getPersonActionItems(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun refreshCachesOnboardingSourceRepairActionWithPrimarySourceStatusEvidence() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        coEvery {
            api.getPersonActionItems(any(), any(), any(), any(), "onboarding", any(), any(), any(), any(), any())
        } returns Response.success(feedDto(data = listOf(sourceRepairActionDto())))
        val repository = repository(dao, api)

        val result = repository.refresh(userId = "user-1", surface = "onboarding")

        check(result is BecalmResult.Success)
        assertEquals(1, result.value.fetched)
        val cached = dao.cachedActions.single()
        assertEquals("reconnect_source", cached.actionKind)
        assertEquals("onboarding,source_repair", cached.surfacesCsv)
        assertEquals("gmail", cached.sourceType)
        assertEquals("connection-1", cached.sourceRef)
        assertEquals("source_status", cached.primaryEvidenceKind)
        assertEquals("connection-1", cached.primaryEvidenceId)
        assertEquals("provider token expired", cached.primaryEvidenceQuote)
        assertEquals("onboarding", dao.syncStates.single().surfaceKey)
        coVerify(exactly = 1) {
            api.getPersonActionItems(any(), any(), any(), any(), "onboarding", "active", any(), any(), any(), false)
        }
    }

    @Test
    fun refreshCachesAddToCalendarProviderWriteMetadata() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        coEvery {
            api.getPersonActionItems(any(), any(), any(), any(), "schedule", any(), any(), any(), any(), any())
        } returns Response.success(feedDto(data = listOf(scheduleActionDto(status = "active"))))
        val repository = repository(dao, api)

        val result = repository.refresh(userId = "user-1", surface = "schedule")

        check(result is BecalmResult.Success)
        val cached = dao.cachedActions.single()
        assertEquals("add_to_calendar", cached.actionKind)
        assertEquals("add_to_calendar", cached.providerWriteKind)
        assertEquals("ready", cached.providerWriteState)
        assertEquals("google_calendar", cached.providerWriteProvider)
        assertEquals("conn-calendar-write", cached.providerWriteSourceConnectionId)
        assertEquals("schedule-link-1", cached.providerWriteScheduleEventLinkId)
    }

    @Test
    fun refreshCachesOnboardingQuotaSourceRepairActionWithCapacityReason() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        coEvery {
            api.getPersonActionItems(any(), any(), any(), any(), "onboarding", any(), any(), any(), any(), any())
        } returns Response.success(feedDto(data = listOf(sourceQuotaRepairActionDto())))
        val repository = repository(dao, api)

        val result = repository.refresh(userId = "user-1", surface = "onboarding")

        check(result is BecalmResult.Success)
        val cached = dao.cachedActions.single()
        assertEquals("reconnect_source", cached.actionKind)
        assertEquals("대기", cached.primaryVerb)
        assertEquals("onboarding,source_repair", cached.surfacesCsv)
        assertTrue(cached.reasonCodesCsv.split(",").contains("source_repair:quota"))
        assertTrue(cached.reasonCodesCsv.split(",").contains("blocked:quota"))
        assertEquals("source_status", cached.primaryEvidenceKind)
        assertEquals("connection-quota-1", cached.primaryEvidenceId)
        assertEquals("vertex_rate_limited quota exceeded 429", cached.primaryEvidenceQuote)
    }

    @Test
    fun refreshCachesOnboardingHighConfidenceActionWithSourceEvidence() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        coEvery {
            api.getPersonActionItems(any(), any(), any(), any(), "onboarding", any(), any(), any(), any(), any())
        } returns Response.success(feedDto(data = listOf(onboardingCommitmentActionDto())))
        val repository = repository(dao, api)

        val result = repository.refresh(userId = "user-1", surface = "onboarding")

        check(result is BecalmResult.Success)
        assertEquals(1, result.value.fetched)
        val cached = dao.cachedActions.single()
        assertEquals("follow_up", cached.actionKind)
        assertEquals("commitment,onboarding,person,schedule", cached.surfacesCsv)
        assertTrue(cached.reasonCodesCsv.split(",").contains("onboarding:high_confidence_7d"))
        assertEquals("source_event", cached.primaryEvidenceKind)
        assertEquals("source-event-onboarding-1", cached.primaryEvidenceId)
        assertEquals("Acme legal thread", cached.primaryEvidenceLabel)
        assertEquals("onboarding", dao.syncStates.single().surfaceKey)
    }

    @Test
    fun refreshCachesConfirmOnboardingCandidateWithSourceEvidence() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        coEvery {
            api.getPersonActionItems(any(), any(), any(), any(), "onboarding", any(), any(), any(), any(), any())
        } returns Response.success(feedDto(data = listOf(confirmOnboardingActionDto())))
        val repository = repository(dao, api)

        val result = repository.refresh(userId = "user-1", surface = "onboarding")

        check(result is BecalmResult.Success)
        assertEquals(1, result.value.fetched)
        val cached = dao.cachedActions.single()
        assertEquals("confirm_onboarding", cached.actionKind)
        assertEquals("확인", cached.primaryVerb)
        assertEquals("commitment,onboarding,person", cached.surfacesCsv)
        assertTrue(cached.reasonCodesCsv.split(",").contains("onboarding:confirm_candidate"))
        assertEquals("source_event", cached.primaryEvidenceKind)
        assertEquals("source-event-confirm-onboarding-1", cached.primaryEvidenceId)
        assertEquals("onboarding", dao.syncStates.single().surfaceKey)
    }

    @Test
    fun refreshCachesReviewMatchActionForParticipantResolution() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        coEvery {
            api.getPersonActionItems(any(), any(), any(), any(), "person", any(), any(), any(), any(), any())
        } returns Response.success(feedDto(data = listOf(reviewMatchActionDto())))
        val repository = repository(dao, api)

        val result = repository.refresh(userId = "user-1", surface = "person")

        check(result is BecalmResult.Success)
        assertEquals(1, result.value.fetched)
        val cached = dao.cachedActions.single()
        assertEquals("review_match", cached.actionKind)
        assertEquals("onboarding,person", cached.surfacesCsv)
        assertEquals("Jane Kim", cached.personDisplayName)
        assertTrue(cached.reasonCodesCsv.split(",").contains("participant_unresolved"))
        assertEquals("source_event", cached.primaryEvidenceKind)
        assertEquals("source-event-review-1", cached.primaryEvidenceId)
        assertEquals("From: Jane Kim", cached.primaryEvidenceQuote)
    }

    @Test
    fun refreshNormalizesCsvTokensBeforeCachingActionRows() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        coEvery {
            api.getPersonActionItems(any(), any(), any(), any(), "person", any(), any(), any(), any(), any())
        } returns Response.success(
            feedDto(
                data = listOf(
                    actionDto(
                        status = "active",
                        surfaces = listOf(" person ", "commitment", "", "person", "schedule"),
                        reasonCodes = listOf(" source:gmail ", "waiting_on", "", "source:gmail"),
                    ),
                ),
            ),
        )
        val repository = repository(dao, api)

        val result = repository.refresh(userId = "user-1", surface = "person")

        check(result is BecalmResult.Success)
        val cached = dao.cachedActions.single()
        assertEquals("person,commitment,schedule", cached.surfacesCsv)
        assertEquals("source:gmail,waiting_on", cached.reasonCodesCsv)
        assertEquals(listOf("pa-1"), dao.observeActiveForSurface(userId = "user-1", surface = "person", limit = 10).first().map { it.id })
    }

    @Test
    fun feedDtoParsesServerTimingMetadata() {
        val dto = Moshi.Builder()
            .addBecalmAdapters()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(PersonActionFeedResponseDto::class.java)
            .fromJson(
                """
                {
                  "data": [],
                  "deleted_ids": [],
                  "cursor": "",
                  "has_more": false,
                  "watermark_scope": "main_feed",
                  "server_watermark": "2026-06-03T03:00:00Z",
                  "input_watermarks": [],
                  "recompute_state": "caught_up",
                  "capacity_state": {"state": "normal"},
                  "changed_since_min": "2026-03-05T00:00:00Z",
                  "tombstone_window_days": 90,
                  "server_timing_ms": {
                    "feed_watermark": 0.4,
                    "action_rows": 2.1,
                    "evidence_refs": 0.7,
                    "deleted_ids": 0.3,
                    "total": 3.8
                  }
                }
                """.trimIndent(),
            )

        assertNotNull(dto)
        assertEquals(3.8, dto?.serverTimingMs?.get("total") ?: -1.0, 0.0)
        assertEquals(2.1, dto?.serverTimingMs?.get("action_rows") ?: -1.0, 0.0)
    }

    @Test
    fun refreshPersistsRecoveryCapacityAndTimingMetadataInSyncState() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        coEvery {
            api.getPersonActionItems(any(), any(), any(), any(), "person", any(), any(), any(), any(), any())
        } returns Response.success(
            feedDto(
                data = listOf(actionDto(status = "active")),
                recomputeState = "degraded",
                capacityState = PersonActionCapacityStateDto(
                    state = "degraded",
                    backlogLagSeconds = 47,
                    lastCaughtUpAt = Instant.parse("2026-06-03T02:59:00Z"),
                    incidentId = "inc-action-1",
                ),
                recoveryActions = listOf(
                    PersonActionRecoveryActionDto(
                        kind = "refresh_action",
                        label = "새로고침",
                        reasonCode = "degraded",
                    ),
                ),
                emptyState = PersonActionEmptyStateDto(
                    state = "waiting_for_source",
                    title = "출처 정리 중",
                    message = "잠시 후 다시 확인하세요.",
                    recoveryActions = listOf(
                        PersonActionRecoveryActionDto(
                            kind = "refresh_action",
                            label = "다시 시도",
                            reasonCode = "waiting_for_source",
                        ),
                    ),
                ),
                serverTimingMs = mapOf("total" to 3.8, "action_rows" to 2.1),
            ),
        )
        val repository = repository(dao, api)

        val result = repository.refresh(userId = "user-1", surface = "person")

        check(result is BecalmResult.Success)
        assertEquals(1, result.value.fetched)
        assertEquals("pa-1", dao.cachedActions.single().id)
        assertEquals(Instant.parse("2026-06-03T03:00:00Z"), dao.syncStates.single().serverWatermark)
        val syncState = dao.syncStates.single()
        assertEquals("degraded", syncState.recomputeState)
        assertEquals("degraded", syncState.capacityState)
        assertEquals(47, syncState.capacityBacklogLagSeconds)
        assertEquals(Instant.parse("2026-06-03T02:59:00Z"), syncState.capacityLastCaughtUpAt)
        assertEquals("inc-action-1", syncState.capacityIncidentId)
        assertTrue(syncState.recoveryActionsJson.orEmpty().contains("refresh_action"))
        assertTrue(syncState.emptyStateJson.orEmpty().contains("waiting_for_source"))
        assertTrue(syncState.serverTimingJson.orEmpty().contains("\"action_rows\":2.1"))
        assertEquals(3.8, syncState.serverTimingTotalMs ?: -1.0, 0.0)
    }

    @Test
    fun fullRefreshEmptyFeedClearsOnlyScopedRowsAndPreservesOtherSurfaces() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        dao.cachedActions += listOf(
            actionEntity(id = "pa-person-old", surfacesCsv = "person,commitment"),
            actionEntity(id = "pa-schedule-only", surfacesCsv = "schedule"),
        )
        val api = mockk<RailwayApi>()
        coEvery {
            api.getPersonActionItems(any(), any(), any(), any(), "person", any(), any(), any(), any(), any())
        } returns Response.success(feedDto(data = emptyList(), serverWatermark = Instant.parse("2026-06-03T04:00:00Z")))
        val repository = repository(dao, api)

        val result = repository.refresh(userId = "user-1", surface = "person")

        check(result is BecalmResult.Success)
        assertEquals(0, result.value.fetched)
        assertEquals(listOf("pa-schedule-only"), dao.cachedActions.map { it.id })
        assertEquals("person", dao.syncStates.single().surfaceKey)
        assertEquals(Instant.parse("2026-06-03T04:00:00Z"), dao.syncStates.single().serverWatermark)
    }

    @Test
    fun deltaRefreshAppliesRowsAndTombstonesWithoutClearingExistingCache() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        dao.cachedActions += listOf(
            actionEntity(id = "pa-keep", surfacesCsv = "person"),
            actionEntity(id = "pa-delete", surfacesCsv = "person"),
        )
        dao.syncStates += syncStateEntity(surfaceKey = "person", serverWatermark = Instant.parse("2026-06-03T01:00:00Z"))
        val api = mockk<RailwayApi>()
        coEvery {
            api.getPersonActionItems(
                cursor = null,
                snapshotId = null,
                limit = any(),
                changedSince = "2026-06-03T01:00:00Z",
                surface = "person",
                status = "active",
                personId = null,
                commitmentId = null,
                calendarEventId = null,
                includeStale = false,
            )
        } returns Response.success(
            feedDto(
                data = listOf(actionDto(id = "pa-delta-new", status = "active")),
                deletedIds = listOf("pa-delete"),
                serverWatermark = Instant.parse("2026-06-03T04:00:00Z"),
            ),
        )
        val repository = repository(dao, api)

        val result = repository.refresh(userId = "user-1", surface = "person")

        check(result is BecalmResult.Success)
        assertEquals(1, result.value.fetched)
        assertEquals(1, result.value.deleted)
        assertEquals(listOf("pa-delta-new", "pa-keep"), dao.cachedActions.map { it.id }.sorted())
        assertEquals(Instant.parse("2026-06-03T04:00:00Z"), dao.syncStates.single().serverWatermark)
    }

    @Test
    fun deltaRefreshFallsBackToFullRefreshWhenSnapshotExpired() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        dao.cachedActions += listOf(
            actionEntity(id = "pa-old", surfacesCsv = "person"),
            actionEntity(id = "pa-schedule-only", surfacesCsv = "schedule"),
        )
        dao.syncStates += syncStateEntity(surfaceKey = "person", serverWatermark = Instant.parse("2026-06-03T01:00:00Z"))
        val api = mockk<RailwayApi>()
        coEvery {
            api.getPersonActionItems(any(), any(), any(), any(), "person", any(), any(), any(), any(), any())
        } returnsMany listOf(
            Response.error(
                409,
                """{"error":"snapshot_expired","client_action":"full_refresh"}""".toResponseBody(),
            ),
            Response.success(
                feedDto(
                    data = listOf(actionDto(id = "pa-full-refresh", status = "active", surfaces = listOf("person"))),
                    serverWatermark = Instant.parse("2026-06-03T04:00:00Z"),
                ),
            ),
        )
        val repository = repository(dao, api)

        val result = repository.refresh(userId = "user-1", surface = "person")

        check(result is BecalmResult.Success)
        assertEquals(1, result.value.fetched)
        assertEquals(listOf("pa-full-refresh", "pa-schedule-only"), dao.cachedActions.map { it.id }.sorted())
        assertEquals(Instant.parse("2026-06-03T04:00:00Z"), dao.syncStates.single().serverWatermark)
        coVerify(exactly = 1) {
            api.getPersonActionItems(
                cursor = null,
                snapshotId = null,
                limit = any(),
                changedSince = "2026-06-03T01:00:00Z",
                surface = "person",
                status = "active",
                personId = null,
                commitmentId = null,
                calendarEventId = null,
                includeStale = false,
            )
        }
        coVerify(exactly = 1) {
            api.getPersonActionItems(
                cursor = null,
                snapshotId = null,
                limit = any(),
                changedSince = null,
                surface = "person",
                status = "active",
                personId = null,
                commitmentId = null,
                calendarEventId = null,
                includeStale = false,
            )
        }
    }

    @Test
    fun refreshRejectsMalformedPaginationWithoutApplyingPartialRows() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        dao.cachedActions += actionEntity(id = "pa-old", surfacesCsv = "person")
        val api = mockk<RailwayApi>()
        coEvery {
            api.getPersonActionItems(any(), any(), any(), any(), "person", any(), any(), any(), any(), any())
        } returns Response.success(
            feedDto(
                data = listOf(actionDto(id = "pa-partial", status = "active", surfaces = listOf("person"))),
                cursor = "",
                hasMore = true,
                snapshotId = "pas2:1893456000:snapshot",
            ),
        )
        val repository = repository(dao, api)

        val result = repository.refresh(userId = "user-1", surface = "person")

        check(result is BecalmResult.Failure)
        assertEquals(listOf("pa-old"), dao.cachedActions.map { it.id })
        assertEquals(emptyList<PersonActionSyncStateEntity>(), dao.syncStates)
    }

    @Test
    fun refreshPaginatesWithSnapshotAndAppliesTerminalSnapshotOnce() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        dao.cachedActions += actionEntity(id = "pa-old", surfacesCsv = "person")
        val api = mockk<RailwayApi>()
        coEvery {
            api.getPersonActionItems(any(), any(), any(), any(), "person", any(), any(), any(), any(), any())
        } returnsMany listOf(
            Response.success(
                feedDto(
                    data = listOf(actionDto(id = "pa-page-1", status = "active")),
                    cursor = "50",
                    hasMore = true,
                    snapshotId = "pas2:1893456000:snapshot",
                    serverWatermark = Instant.parse("2026-06-03T04:00:00Z"),
                ),
            ),
            Response.success(
                feedDto(
                    data = listOf(actionDto(id = "pa-page-2", status = "active")),
                    serverWatermark = Instant.parse("2026-06-03T04:00:00Z"),
                ),
            ),
        )
        val repository = repository(dao, api)

        val result = repository.refresh(userId = "user-1", surface = "person")

        check(result is BecalmResult.Success)
        assertEquals(2, result.value.fetched)
        assertEquals(listOf("pa-page-1", "pa-page-2"), dao.cachedActions.map { it.id }.sorted())
        assertEquals("person", dao.syncStates.single().surfaceKey)
        coVerify(exactly = 1) {
            api.getPersonActionItems(
                cursor = null,
                snapshotId = null,
                limit = any(),
                changedSince = null,
                surface = "person",
                status = "active",
                personId = null,
                commitmentId = null,
                calendarEventId = null,
                includeStale = false,
            )
        }
        coVerify(exactly = 1) {
            api.getPersonActionItems(
                cursor = "50",
                snapshotId = "pas2:1893456000:snapshot",
                limit = any(),
                changedSince = null,
                surface = "person",
                status = "active",
                personId = null,
                commitmentId = null,
                calendarEventId = null,
                includeStale = false,
            )
        }
    }

    @Test
    fun refreshAppliesOneTenHundredRowsWithoutLosingOrDuplicatingCachedActions() = runTest(dispatcher) {
        for (count in listOf(1, 10, 100)) {
            val dao = FakePersonActionDao()
            dao.cachedActions += actionEntity(id = "pa-old-$count", surfacesCsv = "person")
            val api = mockk<RailwayApi>()
            val rows = (1..count).map { index ->
                actionDto(
                    id = "pa-scale-$count-$index",
                    status = "active",
                    surfaces = listOf("person"),
                )
            }
            coEvery {
                api.getPersonActionItems(any(), any(), any(), any(), "person", any(), any(), any(), any(), any())
            } returns Response.success(feedDto(data = rows))
            val repository = repository(dao, api)

            val result = repository.refresh(userId = "user-1", surface = "person")

            check(result is BecalmResult.Success)
            assertEquals(count, result.value.fetched)
            assertEquals(0, result.value.deleted)
            assertEquals((1..count).map { "pa-scale-$count-$it" }.toSet(), dao.cachedActions.map { it.id }.toSet())
            assertEquals(count, dao.observeActiveForSurface(userId = "user-1", surface = "person", limit = 100).first().size)
            assertEquals("person", dao.syncStates.single().surfaceKey)
            coVerify(exactly = 1) {
                api.getPersonActionItems(
                    cursor = null,
                    snapshotId = null,
                    limit = any(),
                    changedSince = null,
                    surface = "person",
                    status = "active",
                    personId = null,
                    commitmentId = null,
                    calendarEventId = null,
                    includeStale = false,
                )
            }
        }
    }

    @Test
    fun observeActiveEntityFiltersDelegateToRoomDao() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        dao.cachedActions += listOf(
            actionEntity(id = "pa-person-1", personId = "person-1", commitmentId = "commitment-1", calendarEventId = "calendar-1"),
            actionEntity(id = "pa-person-2", personId = "person-2", commitmentId = "commitment-2", calendarEventId = "calendar-2"),
            actionEntity(id = "pa-completed", personId = "person-1", commitmentId = "commitment-1", status = "completed"),
        )
        val repository = repository(dao, mockk(relaxed = true))

        assertEquals(listOf("pa-person-1"), repository.observeActiveForPerson("user-1", "person-1").first().map { it.id })
        assertEquals(listOf("pa-person-1"), repository.observeActiveForCommitment("user-1", "commitment-1").first().map { it.id })
        assertEquals(listOf("pa-person-1"), repository.observeActiveForCalendarEvent("user-1", "calendar-1").first().map { it.id })
    }

    @Test
    fun fetchEvidenceOriginalReturnsDetailWithoutMutatingRoomCache() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        dao.cachedActions += actionEntity(id = "pa-cached")
        val api = mockk<RailwayApi>()
        coEvery {
            api.getPersonActionEvidenceOriginal("pa-1", "source_event", "source-event-1")
        } returns Response.success(evidenceOriginalResponseDto())
        val repository = repository(dao, api)

        val result = repository.fetchEvidenceOriginal(
            userId = "user-1",
            actionItemId = "pa-1",
            evidenceKind = "source_event",
            evidenceId = "source-event-1",
        )

        check(result is BecalmResult.Success)
        assertEquals("pa-1", result.value.actionItemId)
        assertEquals("source_event", result.value.evidence.kind)
        assertEquals("source-event-1", result.value.original.id)
        assertEquals(false, result.value.original.rawBodyIncluded)
        assertEquals(listOf("pa-cached"), dao.cachedActions.map { it.id })
        coVerify(exactly = 1) { api.getPersonActionEvidenceOriginal("pa-1", "source_event", "source-event-1") }
    }

    @Test
    fun fetchEvidenceOriginalMergesServerMetadataWithLocalOriginal() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        val rawDao = mockk<RawIngestionEventDao>()
        val anchorDao = mockk<SourceEventAnchorDao>()
        val sourceOriginalResolver = mockk<SourceOriginalResolver>()
        val rawEvent = rawEvent(id = "raw-local-1", sourceRef = "gmail-msg-1")
        coEvery {
            api.getPersonActionEvidenceOriginal("pa-1", "source_event", "source-event-1")
        } returns Response.success(
            evidenceOriginalResponseDto(
                actionItemId = "pa-1",
                evidenceId = "source-event-1",
                sourceEventId = "backend-source-event-1",
                sourceRef = "gmail-msg-1",
            ),
        )
        coEvery { anchorDao.findBestForEventRef(any(), any()) } returns null
        coEvery {
            anchorDao.findBestForEventRef("user-1", "backend-source-event-1")
        } returns sourceEventAnchor(
            sourceEventId = "backend-source-event-1",
            localRawEventId = "raw-local-1",
            sourceRef = "gmail-msg-1",
        )
        coEvery { rawDao.findById("raw-local-1", "user-1") } returns rawEvent
        coEvery {
            sourceOriginalResolver.resolve(
                userId = "user-1",
                event = rawEvent,
                fallbackRawEventIds = listOf("backend-source-event-1"),
            )
        } returns SourceOriginalContext(
            emailBody = emailBody(rawEventId = "raw-local-1", subject = "자료 확인 메일", bodyPlain = "긴 이메일 원문"),
            archivedOriginal = null,
        )
        val repository = repository(
            dao = dao,
            api = api,
            rawIngestionEventDao = rawDao,
            sourceEventAnchorDao = anchorDao,
            sourceOriginalResolver = sourceOriginalResolver,
        )

        val result = repository.fetchEvidenceOriginal(
            userId = "user-1",
            actionItemId = "pa-1",
            evidenceKind = "source_event",
            evidenceId = "source-event-1",
        )

        check(result is BecalmResult.Success)
        assertEquals("Please send the proposal tomorrow.", result.value.original.quote)
        assertEquals("자료 확인 메일", result.value.original.localOriginalTitle)
        assertEquals("긴 이메일 원문", result.value.original.localOriginalText)
        assertEquals(false, result.value.original.localOriginalTruncated)
    }

    @Test
    fun fetchEvidenceOriginalMapsNotFoundEnvelopeToHideEvidence() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        coEvery {
            api.getPersonActionEvidenceOriginal("pa-1", "source_event", "source-missing")
        } returns Response.error(
            404,
            """
            {
              "error": "action_evidence_not_found",
              "message": "Evidence is not attached to action item.",
              "retryable": false,
              "client_action": "hide_evidence"
            }
            """.trimIndent().toResponseBody(),
        )
        val repository = repository(dao, api)

        val result = repository.fetchEvidenceOriginal(
            userId = "user-1",
            actionItemId = "pa-1",
            evidenceKind = "source_event",
            evidenceId = "source-missing",
        )

        check(result is BecalmResult.Failure)
        assertEquals(BecalmError.NotFound("hide_evidence"), result.error)
    }

    @Test
    fun fetchEvidenceOriginalUsesRetryAfterForRateLimit() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        val rawResponse = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("https://becalm.test/v1/person_action_items/pa-1/evidence/source_event/source-1").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(429)
            .message("Too Many Requests")
            .header("Retry-After", "45")
            .build()
        coEvery {
            api.getPersonActionEvidenceOriginal("pa-1", "source_event", "source-1")
        } returns Response.error(
            """
            {
              "error": "quota_exceeded",
              "message": "Evidence resolver quota exceeded.",
              "retryable": true,
              "retry_after_seconds": 30,
              "client_action": "retry_later"
            }
            """.trimIndent().toResponseBody(),
            rawResponse,
        )
        val repository = repository(dao, api)

        val result = repository.fetchEvidenceOriginal(
            userId = "user-1",
            actionItemId = "pa-1",
            evidenceKind = "source_event",
            evidenceId = "source-1",
        )

        check(result is BecalmResult.Failure)
        assertEquals(BecalmError.RateLimited(45), result.error)
    }

    @Test
    fun fetchEvidenceOriginalMapsServerErrorAsRetryableServerFailure() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        coEvery {
            api.getPersonActionEvidenceOriginal("pa-1", "source_event", "source-1")
        } returns Response.error(
            503,
            """
            {
              "error": "action_evidence_unavailable",
              "message": "Action evidence is temporarily unavailable.",
              "retryable": true,
              "client_action": "retry_later"
            }
            """.trimIndent().toResponseBody(),
        )
        val repository = repository(dao, api)

        val result = repository.fetchEvidenceOriginal(
            userId = "user-1",
            actionItemId = "pa-1",
            evidenceKind = "source_event",
            evidenceId = "source-1",
        )

        check(result is BecalmResult.Failure)
        val error = result.error
        check(error is BecalmError.ServerError)
        assertEquals(503, error.code)
        assertTrue(error.body?.contains("action_evidence_unavailable") == true)
    }

    @Test
    fun fetchEvidenceOriginalValidatesIdsBeforeNetworkCall() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        val repository = repository(dao, api)

        val result = repository.fetchEvidenceOriginal(
            userId = "user-1",
            actionItemId = "",
            evidenceKind = "source_event",
            evidenceId = "source-1",
        )

        check(result is BecalmResult.Failure)
        assertEquals(BecalmError.Validation("evidence", "missing evidence original lookup id"), result.error)
        coVerify(exactly = 0) { api.getPersonActionEvidenceOriginal(any(), any(), any()) }
    }

    @Test
    fun fetchEvidenceOriginalMapsIoFailureToNetworkError() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        coEvery {
            api.getPersonActionEvidenceOriginal("pa-1", "source_event", "source-1")
        } throws IOException("timeout")
        val repository = repository(dao, api)

        val result = repository.fetchEvidenceOriginal(
            userId = "user-1",
            actionItemId = "pa-1",
            evidenceKind = "source_event",
            evidenceId = "source-1",
        )

        check(result is BecalmResult.Failure)
        assertEquals(BecalmError.Network(0, "timeout"), result.error)
    }

    @Test
    fun fetchEvidenceOriginalHandlesOneTenHundredLookupsWithoutCacheMutation() = runTest(dispatcher) {
        for (count in listOf(1, 10, 100)) {
            val dao = FakePersonActionDao()
            dao.cachedActions += actionEntity(id = "pa-cached-$count")
            val api = mockk<RailwayApi>()
            coEvery {
                api.getPersonActionEvidenceOriginal("pa-1", "source_event", "source-event-1")
            } returns Response.success(evidenceOriginalResponseDto())
            val repository = repository(dao, api)

            repeat(count) {
                val result = repository.fetchEvidenceOriginal(
                    userId = "user-1",
                    actionItemId = "pa-1",
                    evidenceKind = "source_event",
                    evidenceId = "source-event-1",
                )
                check(result is BecalmResult.Success)
            }

            assertEquals(listOf("pa-cached-$count"), dao.cachedActions.map { it.id })
            coVerify(exactly = count) { api.getPersonActionEvidenceOriginal("pa-1", "source_event", "source-event-1") }
        }
    }

    @Test
    fun generateDraftPostsContractBodyAndReturnsEditableDraft() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        dao.cachedActions += actionEntity(id = "pa-cached")
        val api = mockk<RailwayApi>()
        val request = slot<PersonActionDraftRequestDto>()
        coEvery {
            api.generatePersonActionDraft("pa-1", any(), capture(request))
        } returns Response.success(draftResponseDto())
        val repository = repository(dao, api)

        val result = repository.generateDraft(
            userId = "user-1",
            actionItemId = "pa-1",
            draftKind = "reply",
            evidenceRefs = listOf(
                PersonActionDraftEvidenceRef(kind = "source_event", evidenceId = "source-event-1"),
                PersonActionDraftEvidenceRef(kind = "", evidenceId = "ignored"),
            ),
            userInstruction = "  더 짧게  ",
        )

        check(result is BecalmResult.Success)
        assertEquals("draft-1", result.value.draftId)
        assertEquals("Jane Kim proposal reply", result.value.subject)
        assertEquals("Jane님, 제안서 확인했습니다.", result.value.body)
        assertEquals("reply", request.captured.draftKind)
        assertEquals("email", request.captured.channel)
        assertEquals("ko-KR", request.captured.locale)
        assertEquals("warm_brief", request.captured.tone)
        assertEquals(900, request.captured.maxChars)
        assertTrue(request.captured.clientRequestId.isNotBlank())
        assertEquals("더 짧게", request.captured.userInstruction)
        assertEquals(1, request.captured.evidenceRefs.size)
        assertEquals("source_event", request.captured.evidenceRefs.single().kind)
        assertEquals("source-event-1", request.captured.evidenceRefs.single().evidenceId)
        assertEquals(listOf("pa-cached"), dao.cachedActions.map { it.id })
    }

    @Test
    fun generateDraftMapsUnsupportedEnvelopeToValidationState() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        coEvery {
            api.generatePersonActionDraft("pa-1", any(), any())
        } returns Response.error(
            422,
            """
            {
              "error": "unsupported_draft_kind",
              "message": "Draft is not supported for this action.",
              "retryable": false,
              "client_action": "hide_draft"
            }
            """.trimIndent().toResponseBody(),
        )
        val repository = repository(dao, api)

        val result = repository.generateDraft(userId = "user-1", actionItemId = "pa-1", draftKind = "review_match")

        check(result is BecalmResult.Failure)
        assertEquals(BecalmError.Validation("draft", "hide_draft"), result.error)
    }

    @Test
    fun generateDraftMapsIoFailureToNetworkError() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        coEvery {
            api.generatePersonActionDraft("pa-1", any(), any())
        } throws IOException("timeout")
        val repository = repository(dao, api)

        val result = repository.generateDraft(userId = "user-1", actionItemId = "pa-1", draftKind = "reply")

        check(result is BecalmResult.Failure)
        assertEquals(BecalmError.Network(0, "timeout"), result.error)
    }

    @Test
    fun generateDraftValidatesIdsBeforeNetworkCall() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        val repository = repository(dao, api)

        val result = repository.generateDraft(userId = "user-1", actionItemId = "", draftKind = "reply")

        check(result is BecalmResult.Failure)
        assertEquals(BecalmError.Validation("draft", "missing draft lookup id"), result.error)
        coVerify(exactly = 0) { api.generatePersonActionDraft(any(), any(), any()) }
    }

    @Test
    fun submitActionFeedbackWritesOutboxThenSyncsFeedbackPayload() = runTest(dispatcher) {
        val dao = FakePersonActionDao()
        val api = mockk<RailwayApi>()
        val request = slot<com.becalm.android.data.remote.dto.PersonActionFeedbackDto>()
        coEvery {
            api.submitPersonActionFeedback("pa-1", any(), capture(request))
        } returns Response.success(SinglePersonActionItemResponseDto(data = actionDto(status = "active")))
        val repository = repository(dao, api)

        val result = repository.submitActionFeedback(
            userId = "user-1",
            actionItemId = "pa-1",
            feedbackType = "correct_due_date",
            reason = "Should be tomorrow",
            correctedPersonId = "person-2",
            correctedDueAt = Instant.parse("2026-06-04T03:00:00Z"),
        )

        check(result is BecalmResult.Success)
        assertEquals(1, result.value.queued)
        assertEquals(1, result.value.synced)
        assertEquals("feedback", dao.mutations.single().mutationKind)
        assertEquals("correct_due_date", request.captured.feedbackType)
        assertEquals("Should be tomorrow", request.captured.reason)
        assertEquals("person-2", request.captured.correctedPersonId)
        assertEquals(Instant.parse("2026-06-04T03:00:00Z"), request.captured.correctedDueAt)
    }

    @Test
    fun syncPendingMutationsDrainsOneTenHundredQueuedMutationsWithoutDuplicates() = runTest(dispatcher) {
        for (count in listOf(1, 10, 100)) {
            val dao = FakePersonActionDao()
            dao.mutations += (1..count).map { index -> queuedStateMutation(index) }
            val api = mockk<RailwayApi>()
            coEvery {
                api.patchPersonActionItem(any(), any(), any())
            } returns Response.success(SinglePersonActionItemResponseDto(data = actionDto(status = "completed")))
            val repository = repository(dao, api)

            var synced = 0
            repeat(10) {
                val result = repository.syncPendingMutations(userId = "user-1", limit = 20)
                check(result is BecalmResult.Success)
                synced += result.value.synced
                if (synced == count) return@repeat
            }

            assertEquals(count, synced)
            assertTrue(dao.mutations.all { it.syncStatus == "synced" })
            val secondDrain = repository.syncPendingMutations(userId = "user-1", limit = 20)
            check(secondDrain is BecalmResult.Success)
            assertEquals(0, secondDrain.value.synced)
            coVerify(exactly = count) { api.patchPersonActionItem(any(), any(), any()) }
        }
    }

    private fun repository(
        dao: PersonActionDao,
        api: RailwayApi,
        rawIngestionEventDao: RawIngestionEventDao? = null,
        sourceEventAnchorDao: SourceEventAnchorDao? = null,
        sourceOriginalResolver: SourceOriginalResolver? = null,
    ): PersonActionRepositoryImpl =
        PersonActionRepositoryImpl(
            dao = dao,
            apiProvider = Provider { api },
            rawIngestionEventDao = rawIngestionEventDao,
            sourceEventAnchorDao = sourceEventAnchorDao,
            sourceOriginalResolver = sourceOriginalResolver,
            moshi = Moshi.Builder()
                .addBecalmAdapters()
                .add(KotlinJsonAdapterFactory())
                .build(),
            logger = mockk<Logger>(relaxed = true),
            ioDispatcher = dispatcher,
        )
}

private class FakePersonActionDao : PersonActionDao {
    val cachedActions: MutableList<PersonActionItemCacheEntity> = mutableListOf()
    val mutations: MutableList<PersonActionMutationQueueEntity> = mutableListOf()
    val syncStates: MutableList<PersonActionSyncStateEntity> = mutableListOf()

    override fun observeActiveForSurface(
        userId: String,
        surface: String,
        limit: Int,
    ): Flow<List<PersonActionItemCacheEntity>> =
        flowOf(
            cachedActions
                .filter { it.userId == userId && it.status == "active" && it.surfacesCsv.split(",").contains(surface) }
                .take(limit),
        )

    override fun observeActiveForPerson(
        userId: String,
        personId: String,
        limit: Int,
    ): Flow<List<PersonActionItemCacheEntity>> =
        flowOf(cachedActions.filter { it.userId == userId && it.status == "active" && it.personId == personId }.take(limit))

    override fun observeActiveForCommitment(
        userId: String,
        commitmentId: String,
        limit: Int,
    ): Flow<List<PersonActionItemCacheEntity>> =
        flowOf(cachedActions.filter { it.userId == userId && it.status == "active" && it.commitmentId == commitmentId }.take(limit))

    override fun observeActiveForCalendarEvent(
        userId: String,
        calendarEventId: String,
        limit: Int,
    ): Flow<List<PersonActionItemCacheEntity>> =
        flowOf(cachedActions.filter { it.userId == userId && it.status == "active" && it.calendarEventId == calendarEventId }.take(limit))

    override suspend fun latestServerWatermark(userId: String, surfaceKey: String, status: String): Instant? =
        syncStates.firstOrNull { it.userId == userId && it.surfaceKey == surfaceKey && it.status == status }?.serverWatermark

    override fun observeSyncState(
        userId: String,
        surfaceKey: String,
        status: String,
    ): Flow<PersonActionSyncStateEntity?> =
        flowOf(syncStates.firstOrNull { it.userId == userId && it.surfaceKey == surfaceKey && it.status == status })

    override suspend fun upsertActionItems(rows: List<PersonActionItemCacheEntity>) {
        for (row in rows) {
            cachedActions.removeAll { it.id == row.id }
            cachedActions += row
        }
    }

    override suspend fun deleteActionItems(userId: String, ids: List<String>) {
        cachedActions.removeAll { it.userId == userId && it.id in ids }
    }

    override suspend fun deleteAllActionItemsForUser(userId: String): Int {
        val before = cachedActions.size
        cachedActions.removeAll { it.userId == userId }
        return before - cachedActions.size
    }

    override suspend fun deleteAllActionItemsForScope(userId: String, surface: String?): Int {
        val before = cachedActions.size
        cachedActions.removeAll { it.userId == userId && (surface == null || it.surfacesCsv.split(",").contains(surface)) }
        return before - cachedActions.size
    }

    override suspend fun deleteActionItemsNotInScope(userId: String, surface: String?, retainedIds: List<String>): Int {
        val before = cachedActions.size
        cachedActions.removeAll {
            it.userId == userId &&
                (surface == null || it.surfacesCsv.split(",").contains(surface)) &&
                it.id !in retainedIds
        }
        return before - cachedActions.size
    }

    override suspend fun upsertSyncState(row: PersonActionSyncStateEntity) {
        syncStates.removeAll { it.userId == row.userId && it.surfaceKey == row.surfaceKey && it.status == row.status }
        syncStates += row
    }

    override suspend fun upsertMutation(row: PersonActionMutationQueueEntity) {
        mutations.removeAll { it.clientMutationId == row.clientMutationId }
        mutations += row
    }

    override suspend fun findPendingMutations(
        userId: String,
        now: Instant,
        limit: Int,
    ): List<PersonActionMutationQueueEntity> =
        mutations
            .filter {
                it.userId == userId &&
                    (
                        it.syncStatus == "pending" ||
                            (it.syncStatus == "failed" && it.nextAttemptAt != null && it.nextAttemptAt <= now)
                    )
            }
            .take(limit)

    override fun observePendingMutationCount(userId: String): Flow<Int> =
        flowOf(
            mutations.count {
                it.userId == userId &&
                    (
                        it.syncStatus == "pending" ||
                            (it.syncStatus == "failed" && it.nextAttemptAt != null)
                    )
            },
        )

    override suspend fun markMutationSynced(userId: String, clientMutationId: String, updatedAt: Instant) {
        updateMutation(userId, clientMutationId) {
            it.copy(
                syncStatus = "synced",
                lastErrorCode = null,
                lastErrorClientAction = null,
                nextAttemptAt = null,
                updatedAt = updatedAt,
            )
        }
    }

    override suspend fun markMutationFailed(
        userId: String,
        clientMutationId: String,
        errorCode: String?,
        clientAction: String?,
        nextAttemptAt: Instant?,
        updatedAt: Instant,
    ) {
        updateMutation(userId, clientMutationId) {
            it.copy(
                syncStatus = "failed",
                attemptCount = it.attemptCount + 1,
                lastErrorCode = errorCode,
                lastErrorClientAction = clientAction,
                nextAttemptAt = nextAttemptAt,
                updatedAt = updatedAt,
            )
        }
    }

    private fun updateMutation(
        userId: String,
        clientMutationId: String,
        transform: (PersonActionMutationQueueEntity) -> PersonActionMutationQueueEntity,
    ) {
        val index = mutations.indexOfFirst { it.userId == userId && it.clientMutationId == clientMutationId }
        if (index >= 0) {
            mutations[index] = transform(mutations[index])
        }
    }
}

private fun actionEntity(
    id: String,
    personId: String? = "person-1",
    commitmentId: String? = "commitment-1",
    calendarEventId: String? = null,
    status: String = "active",
    surfacesCsv: String = "person,commitment,schedule",
): PersonActionItemCacheEntity =
    PersonActionItemCacheEntity(
        id = id,
        userId = "user-1",
        personId = personId,
        personDisplayName = "Jane Kim",
        personSortKey = "jane kim",
        surfacesCsv = surfacesCsv,
        actionKind = "reply",
        status = status,
        title = "Jane Kim proposal reply",
        primaryVerb = "답장",
        shortReason = "근거: proposal thread",
        commitmentId = commitmentId,
        calendarEventId = calendarEventId,
        sourceEventId = "source-event-1",
        sourceType = "gmail",
        sourceRef = "gmail-msg-1",
        dueAt = null,
        dueHint = null,
        dueIsApproximate = false,
        staleAfter = null,
        urgencyScore = 90.0,
        importanceScore = 70.0,
        confidence = 0.9,
        reasonCodesCsv = "source:gmail",
        inputWatermark = Instant.parse("2026-06-03T02:00:00Z"),
        serverWatermark = Instant.parse("2026-06-03T03:00:00Z"),
        computedAt = Instant.parse("2026-06-03T02:00:01Z"),
        updatedAt = Instant.parse("2026-06-03T02:00:02Z"),
        snoozedUntil = null,
        completedAt = null,
        dismissedAt = null,
    )

private fun syncStateEntity(
    surfaceKey: String,
    serverWatermark: Instant,
): PersonActionSyncStateEntity =
    PersonActionSyncStateEntity(
        userId = "user-1",
        surfaceKey = surfaceKey,
        status = "active",
        serverWatermark = serverWatermark,
        recomputeState = "caught_up",
        capacityState = "normal",
        lastSyncedAt = Instant.parse("2026-06-03T01:00:01Z"),
        updatedAt = Instant.parse("2026-06-03T01:00:02Z"),
    )

private fun actionDto(
    status: String,
    id: String = "pa-1",
    surfaces: List<String> = listOf("person", "commitment"),
    reasonCodes: List<String> = listOf("source:gmail"),
): PersonActionItemDto =
    PersonActionItemDto(
        id = id,
        userId = "user-1",
        personId = "person-1",
        personDisplayName = "Jane Kim",
        personSortKey = "jane kim",
        surfaces = surfaces,
        actionKind = "reply",
        status = status,
        title = "Jane Kim proposal reply",
        primaryVerb = "답장",
        shortReason = "근거: proposal thread",
        commitmentId = "commitment-1",
        sourceEventId = "source-event-1",
        sourceType = "gmail",
        sourceRef = "gmail-msg-1",
        dueIsApproximate = false,
        urgencyScore = 90.0,
        importanceScore = 70.0,
        confidence = 0.9,
        reasonCodes = reasonCodes,
        inputWatermark = Instant.parse("2026-06-03T02:00:00Z"),
        computedAt = Instant.parse("2026-06-03T02:00:01Z"),
        updatedAt = Instant.parse("2026-06-03T02:00:02Z"),
    )

private fun scheduleActionDto(
    status: String,
    providerWriteState: String = "ready",
    providerWriteJobId: String? = null,
): PersonActionItemDto =
    PersonActionItemDto(
        id = "pa-schedule-1",
        userId = "user-1",
        personId = "person-1",
        personDisplayName = "Jane Kim",
        personSortKey = "jane kim",
        surfaces = listOf("schedule", "person"),
        actionKind = "add_to_calendar",
        status = status,
        title = "Jane Kim calendar candidate",
        primaryVerb = "후보 확인",
        shortReason = "메일에는 있는데 캘린더에는 없습니다.",
        commitmentId = "commitment-1",
        sourceEventId = "source-event-1",
        sourceType = "gmail",
        sourceRef = "gmail-msg-1",
        dueIsApproximate = false,
        urgencyScore = 91.0,
        importanceScore = 82.0,
        confidence = 0.88,
        reasonCodes = listOf("calendar_missing"),
        evidenceRefs = listOf(
            PersonActionEvidenceRefDto(
                kind = "schedule_link",
                id = "schedule-link-1",
                label = "메일 일정 후보",
            ),
        ),
        inputWatermark = Instant.parse("2026-06-03T02:00:00Z"),
        computedAt = Instant.parse("2026-06-03T02:00:01Z"),
        updatedAt = Instant.parse("2026-06-03T02:00:02Z"),
        providerWrite = PersonActionProviderWriteDto(
            kind = "add_to_calendar",
            state = providerWriteState,
            jobId = providerWriteJobId,
            accepted = providerWriteJobId != null,
            provider = "google_calendar",
            sourceConnectionId = "conn-calendar-write",
            scheduleEventLinkId = "schedule-link-1",
        ),
    )

private fun evidenceOriginalResponseDto(
    actionItemId: String = "pa-1",
    evidenceKind: String = "source_event",
    evidenceId: String = "source-event-1",
    sourceEventId: String? = evidenceId,
    sourceRef: String? = "gmail-thread-1",
    metadata: Map<String, String?> = emptyMap(),
): PersonActionEvidenceOriginalResponseDto =
    PersonActionEvidenceOriginalResponseDto(
        data = PersonActionEvidenceOriginalDto(
            actionItemId = actionItemId,
            actionStatus = "active",
            evidence = PersonActionEvidenceRefDto(
                kind = evidenceKind,
                id = evidenceId,
                sourceRef = sourceRef,
                occurredAt = Instant.parse("2026-06-03T01:00:00Z"),
                label = "Gmail thread",
                quote = "Please send the proposal tomorrow.",
            ),
            original = PersonActionEvidenceOriginalDetailDto(
                kind = evidenceKind,
                id = evidenceId,
                originalAvailable = true,
                status = "metadata_resolved",
                sourceType = "gmail",
                sourceRef = sourceRef,
                metadata = metadata,
                title = "Proposal thread",
                snippet = "Please send the proposal tomorrow.",
                quote = "Please send the proposal tomorrow.",
                occurredAt = Instant.parse("2026-06-03T01:00:00Z"),
                rawBodyIncluded = false,
                sourceEventId = sourceEventId,
            ),
            resolvedAt = Instant.parse("2026-06-03T04:05:00Z"),
        ),
    )

private fun rawEvent(
    id: String,
    sourceRef: String?,
    sourceType: String = "gmail",
): RawIngestionEventEntity =
    RawIngestionEventEntity(
        id = id,
        userId = "user-1",
        clientEventId = "client-$id",
        sourceType = sourceType,
        sourceRef = sourceRef,
        eventTitle = "자료 확인 메일",
        eventSnippet = "Please send the proposal tomorrow.",
        conversationRef = "thread-1",
        folder = "INBOX",
        commitmentsExtractedCount = 1,
        timestamp = Instant.parse("2026-06-03T01:00:00Z"),
        syncStatus = "synced",
    )

private fun emailBody(
    rawEventId: String,
    subject: String?,
    bodyPlain: String?,
): EmailBodyEntity =
    EmailBodyEntity(
        id = "email-body-$rawEventId",
        rawEventId = rawEventId,
        providerMessageId = "gmail-msg-1",
        folder = "INBOX",
        subject = subject,
        fromAddress = "jane@example.com",
        toAddresses = """[{"email":"me@example.com"}]""",
        bodyPlain = bodyPlain,
        bodyHtml = null,
        receivedAt = Instant.parse("2026-06-03T01:00:00Z"),
    )

private fun sourceEventAnchor(
    sourceEventId: String?,
    localRawEventId: String?,
    sourceRef: String?,
): SourceEventAnchorEntity =
    SourceEventAnchorEntity(
        id = "anchor-${sourceEventId ?: localRawEventId ?: sourceRef}",
        userId = "user-1",
        sourceType = "gmail",
        sourceOrigin = SourceEventAnchorOrigin.BACKEND,
        sourceEventId = sourceEventId,
        localRawEventId = localRawEventId,
        sourceConnectionId = "conn-1",
        sourceAccountKey = "gmail:jane@example.com",
        providerEventId = sourceRef,
        conversationRef = "thread-1",
        sourceRef = sourceRef,
        title = "자료 확인 메일",
        snippet = "Please send the proposal tomorrow.",
        occurredAt = Instant.parse("2026-06-03T01:00:00Z"),
        createdAt = Instant.parse("2026-06-03T02:00:00Z"),
        updatedAt = Instant.parse("2026-06-03T02:00:00Z"),
    )

private fun draftResponseDto(): PersonActionDraftResponseDto =
    PersonActionDraftResponseDto(
        data = PersonActionDraftDto(
            draftId = "draft-1",
            actionItemId = "pa-1",
            draftKind = "reply",
            channel = "email",
            status = "ready",
            subject = "Jane Kim proposal reply",
            body = "Jane님, 제안서 확인했습니다.",
            provenance = listOf(
                PersonActionDraftProvenanceDto(
                    kind = "source_event",
                    evidenceId = "source-event-1",
                    label = "Gmail thread",
                ),
            ),
            safety = PersonActionDraftSafetyDto(
                containsSourceQuote = false,
                requiresUserReview = true,
            ),
            expiresAt = Instant.parse("2026-06-03T05:05:00Z"),
            generatedAt = Instant.parse("2026-06-03T04:05:00Z"),
        ),
    )

private fun sourceRepairActionDto(): PersonActionItemDto =
    PersonActionItemDto(
        id = "pa-source-repair-1",
        userId = "user-1",
        personId = null,
        personDisplayName = null,
        personSortKey = null,
        surfaces = listOf("onboarding", "source_repair"),
        actionKind = "reconnect_source",
        status = "active",
        title = "Work Gmail 재연결",
        primaryVerb = "재연결",
        shortReason = "gmail 연결 인증이 필요합니다",
        commitmentId = null,
        calendarEventId = null,
        sourceEventId = null,
        sourceType = "gmail",
        sourceRef = "connection-1",
        dueAt = null,
        dueHint = null,
        dueIsApproximate = false,
        staleAfter = Instant.parse("2026-06-04T02:00:00Z"),
        urgencyScore = 96.0,
        importanceScore = 95.0,
        confidence = 1.0,
        reasonCodes = listOf("source_health", "source:gmail", "source_status:needs_reauth", "retryable:user_action"),
        evidenceRefs = listOf(
            PersonActionEvidenceRefDto(
                kind = "source_status",
                id = "connection-1",
                sourceRef = "connection-1",
                occurredAt = Instant.parse("2026-06-03T02:00:00Z"),
                label = "Work Gmail",
                quote = "provider token expired",
            ),
        ),
        inputWatermark = Instant.parse("2026-06-03T02:00:00Z"),
        computedAt = Instant.parse("2026-06-03T02:00:01Z"),
        updatedAt = Instant.parse("2026-06-03T02:00:02Z"),
    )

private fun sourceQuotaRepairActionDto(): PersonActionItemDto =
    PersonActionItemDto(
        id = "pa-source-quota-repair-1",
        userId = "user-1",
        personId = null,
        personDisplayName = null,
        personSortKey = null,
        surfaces = listOf("onboarding", "source_repair"),
        actionKind = "reconnect_source",
        status = "active",
        title = "Work Gmail 대기",
        primaryVerb = "대기",
        shortReason = "gmail 처리 quota 때문에 다음 행동 갱신이 지연됩니다",
        commitmentId = null,
        calendarEventId = null,
        sourceEventId = null,
        sourceType = "gmail",
        sourceRef = "connection-quota-1",
        dueAt = null,
        dueHint = null,
        dueIsApproximate = false,
        staleAfter = Instant.parse("2026-06-04T02:00:00Z"),
        urgencyScore = 88.0,
        importanceScore = 95.0,
        confidence = 1.0,
        reasonCodes = listOf("source_health", "source:gmail", "source_status:failed", "source_repair:quota", "blocked:quota"),
        evidenceRefs = listOf(
            PersonActionEvidenceRefDto(
                kind = "source_status",
                id = "connection-quota-1",
                sourceRef = "connection-quota-1",
                occurredAt = Instant.parse("2026-06-03T02:00:00Z"),
                label = "Work Gmail",
                quote = "vertex_rate_limited quota exceeded 429",
            ),
        ),
        inputWatermark = Instant.parse("2026-06-03T02:00:00Z"),
        computedAt = Instant.parse("2026-06-03T02:00:01Z"),
        updatedAt = Instant.parse("2026-06-03T02:00:02Z"),
    )

private fun onboardingCommitmentActionDto(): PersonActionItemDto =
    PersonActionItemDto(
        id = "pa-onboarding-commitment-1",
        userId = "user-1",
        personId = "person-1",
        personDisplayName = "Jane Kim",
        personSortKey = "jane kim",
        surfaces = listOf("commitment", "onboarding", "person", "schedule"),
        actionKind = "follow_up",
        status = "active",
        title = "Jane Kim · Wait for redlines 팔로업",
        primaryVerb = "팔로업",
        shortReason = "기한: tomorrow",
        commitmentId = "commitment-waiting-1",
        calendarEventId = null,
        sourceEventId = "source-event-onboarding-1",
        sourceType = "gmail",
        sourceRef = "gmail-waiting-1",
        dueAt = Instant.parse("2026-06-04T03:00:00Z"),
        dueHint = "tomorrow",
        dueIsApproximate = false,
        staleAfter = Instant.parse("2026-06-11T03:00:00Z"),
        urgencyScore = 92.0,
        importanceScore = 70.0,
        confidence = 0.9,
        reasonCodes = listOf("source:gmail", "type:action", "direction:take", "waiting_on", "onboarding:high_confidence_7d"),
        evidenceRefs = listOf(
            PersonActionEvidenceRefDto(
                kind = "source_event",
                id = "source-event-onboarding-1",
                sourceRef = "gmail-waiting-1",
                occurredAt = Instant.parse("2026-06-03T01:00:00Z"),
                label = "Acme legal thread",
                quote = "We'll send redlines tomorrow",
            ),
        ),
        inputWatermark = Instant.parse("2026-06-03T02:00:00Z"),
        computedAt = Instant.parse("2026-06-03T02:00:01Z"),
        updatedAt = Instant.parse("2026-06-03T02:00:02Z"),
    )

private fun confirmOnboardingActionDto(): PersonActionItemDto =
    PersonActionItemDto(
        id = "pa-confirm-onboarding-1",
        userId = "user-1",
        personId = "person-1",
        personDisplayName = "Jane Kim",
        personSortKey = "jane kim",
        surfaces = listOf("commitment", "onboarding", "person"),
        actionKind = "confirm_onboarding",
        status = "active",
        title = "Jane Kim · Maybe send proposal 확인",
        primaryVerb = "확인",
        shortReason = "최근 source에서 추출한 다음 행동 후보입니다",
        commitmentId = "commitment-confirm-onboarding-1",
        calendarEventId = null,
        sourceEventId = "source-event-confirm-onboarding-1",
        sourceType = "gmail",
        sourceRef = "gmail-confirm-onboarding-1",
        dueAt = null,
        dueHint = null,
        dueIsApproximate = false,
        staleAfter = Instant.parse("2026-06-10T02:00:00Z"),
        urgencyScore = 55.0,
        importanceScore = 70.0,
        confidence = 0.5,
        reasonCodes = listOf("source:gmail", "type:action", "direction:give", "confidence:review", "onboarding:confirm_candidate"),
        evidenceRefs = listOf(
            PersonActionEvidenceRefDto(
                kind = "source_event",
                id = "source-event-confirm-onboarding-1",
                sourceRef = "gmail-confirm-onboarding-1",
                occurredAt = Instant.parse("2026-06-03T01:00:00Z"),
                label = "Acme proposal thread",
                quote = "I think you can send the revised proposal",
            ),
        ),
        inputWatermark = Instant.parse("2026-06-03T02:00:00Z"),
        computedAt = Instant.parse("2026-06-03T02:00:01Z"),
        updatedAt = Instant.parse("2026-06-03T02:00:02Z"),
    )

private fun reviewMatchActionDto(): PersonActionItemDto =
    PersonActionItemDto(
        id = "pa-review-match-1",
        userId = "user-1",
        personId = null,
        personDisplayName = "Jane Kim",
        personSortKey = "jane kim",
        surfaces = listOf("onboarding", "person"),
        actionKind = "review_match",
        status = "active",
        title = "Jane Kim 연결 확인",
        primaryVerb = "확인",
        shortReason = "상대방 연결 확인이 필요한 출처 참여자입니다",
        commitmentId = null,
        calendarEventId = null,
        sourceEventId = "source-event-review-1",
        sourceType = "gmail",
        sourceRef = "gmail-review-1",
        dueAt = null,
        dueHint = null,
        dueIsApproximate = false,
        staleAfter = Instant.parse("2026-06-10T02:00:00Z"),
        urgencyScore = 68.0,
        importanceScore = 76.0,
        confidence = 0.55,
        reasonCodes = listOf("review_match", "source:gmail", "resolution_status:unresolved", "participant_unresolved", "confidence:review"),
        evidenceRefs = listOf(
            PersonActionEvidenceRefDto(
                kind = "source_event",
                id = "source-event-review-1",
                sourceRef = "gmail-review-1",
                occurredAt = Instant.parse("2026-06-03T01:00:00Z"),
                label = "Acme renewal thread",
                quote = "From: Jane Kim",
            ),
        ),
        inputWatermark = Instant.parse("2026-06-03T02:00:00Z"),
        computedAt = Instant.parse("2026-06-03T02:00:01Z"),
        updatedAt = Instant.parse("2026-06-03T02:00:02Z"),
    )

private fun feedDto(
    data: List<PersonActionItemDto>,
    deletedIds: List<String> = emptyList(),
    cursor: String = "",
    hasMore: Boolean = false,
    snapshotId: String? = null,
    serverWatermark: Instant = Instant.parse("2026-06-03T03:00:00Z"),
    recomputeState: String = "caught_up",
    capacityState: PersonActionCapacityStateDto? = null,
    recoveryActions: List<PersonActionRecoveryActionDto> = emptyList(),
    emptyState: PersonActionEmptyStateDto? = null,
    serverTimingMs: Map<String, Double> = emptyMap(),
): PersonActionFeedResponseDto =
    PersonActionFeedResponseDto(
        data = data,
        deletedIds = deletedIds,
        cursor = cursor,
        hasMore = hasMore,
        snapshotId = snapshotId,
        snapshotExpiresAt = null,
        serverWatermark = serverWatermark,
        recomputeState = recomputeState,
        capacityState = capacityState,
        recoveryActions = recoveryActions,
        emptyState = emptyState,
        serverTimingMs = serverTimingMs,
    )

private fun queuedStateMutation(index: Int): PersonActionMutationQueueEntity =
    PersonActionMutationQueueEntity(
        id = "mutation-$index",
        userId = "user-1",
        actionItemId = "pa-$index",
        clientMutationId = "android:mutation-$index",
        mutationKind = "state_patch",
        payloadJson = """
            {
              "status": "completed",
              "snoozed_until": null,
              "reason": null,
              "expected_updated_at": null,
              "updated_at": "2026-06-03T02:00:00Z"
            }
        """.trimIndent(),
        syncStatus = "pending",
        attemptCount = 0,
        lastErrorCode = null,
        lastErrorClientAction = null,
        nextAttemptAt = null,
        createdAt = Instant.parse("2026-06-03T02:00:00Z"),
        updatedAt = Instant.parse("2026-06-03T02:00:00Z"),
    )
