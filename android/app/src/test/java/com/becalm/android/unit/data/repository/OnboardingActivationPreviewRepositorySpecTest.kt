package com.becalm.android.unit.data.repository

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.OnboardingActivationPreviewRow
import com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.CalendarSyncResponse
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.OnboardingActivationPreviewRepositoryImpl
import com.becalm.android.data.repository.OnboardingActivationPreviewResult
import com.becalm.android.data.repository.PersonActionMutationSyncStats
import com.becalm.android.data.repository.PersonActionRefreshStats
import com.becalm.android.data.repository.PersonActionRepository
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.repository.UserCorrectionRepository
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class OnboardingActivationPreviewRepositorySpecTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun syncGmailAndLoadPreviewUsesOnboardingActionProjectionOverLegacyCommitmentPreview() = runTest(dispatcher) {
        val api = mockk<RailwayApi>(relaxed = true)
        val commitmentDao = mockk<CommitmentDao>()
        val personActionRepository = mockk<PersonActionRepository>()
        var actionRows = emptyList<PersonActionItemCacheEntity>()
        every {
            personActionRepository.observeActiveForSurface(any(), "onboarding", 3)
        } answers {
            flowOf(actionRows)
        }
        coEvery {
            personActionRepository.refresh(userId = "user-1", surface = "onboarding")
        } coAnswers {
            actionRows = listOf(onboardingActionEntity())
            BecalmResult.Success(
                PersonActionRefreshStats(
                    fetched = 1,
                    deleted = 0,
                    serverWatermark = Instant.parse("2026-06-03T03:00:00Z"),
                    recomputeState = "caught_up",
                ),
            )
        }
        coEvery {
            commitmentDao.findOnboardingActivationPreview(any(), any(), any(), any())
        } returns listOf(legacyPreviewRow())
        val repository = repository(
            api = api,
            commitmentDao = commitmentDao,
            personActionRepository = personActionRepository,
        )

        val result = repository.syncGmailAndLoadPreview(userId = "user-1")

        check(result is OnboardingActivationPreviewResult.Ready)
        val preview = result.previews.single()
        assertEquals("Jane Kim · Wait for redlines 팔로업", preview.title)
        assertEquals("commitment-waiting-1", preview.commitmentId)
        assertEquals("person-1", preview.personId)
        assertEquals("pa-onboarding-commitment-1", preview.actionItemId)
        assertEquals("follow_up", preview.actionKind)
        assertEquals(
            listOf("source:gmail", "type:action", "direction:take", "waiting_on", "onboarding:high_confidence_7d"),
            preview.reasonCodes,
        )
        assertEquals("take", preview.direction)
        assertEquals("tomorrow", preview.dueHint)
        assertEquals("Acme legal thread", preview.sourceTitle)
        coVerify(exactly = 1) {
            personActionRepository.refresh(userId = "user-1", surface = "onboarding")
        }
    }

    @Test
    fun syncConnectedSourcesAndLoadPreviewRunsCalendarActivationPreviewAndRefreshesOnboardingActionProjection() =
        runTest(dispatcher) {
            val api = mockk<RailwayApi>(relaxed = true)
            coEvery {
                api.syncCalendarEvents(
                    provider = SourceType.GOOGLE_CALENDAR,
                    mode = "activation_preview",
                )
            } returns Response.success(
                CalendarSyncResponse(
                    synced = 2,
                    status = "succeeded",
                    syncMode = "activation_preview",
                ),
            )
            val commitmentDao = mockk<CommitmentDao>()
            coEvery {
                commitmentDao.findOnboardingActivationPreview(any(), any(), any(), any())
            } returns emptyList()
            val personActionRepository = mockk<PersonActionRepository>()
            var actionRows = emptyList<PersonActionItemCacheEntity>()
            every {
                personActionRepository.observeActiveForSurface(any(), "onboarding", 3)
            } answers {
                flowOf(actionRows)
            }
            coEvery {
                personActionRepository.refresh(userId = "user-1", surface = "onboarding")
            } coAnswers {
                actionRows = listOf(
                    onboardingActionEntity(
                        sourceType = SourceType.GOOGLE_CALENDAR,
                        reasonCodesCsv = "source:google_calendar,type:schedule,onboarding:calendar_overlap_gap",
                    ),
                )
                BecalmResult.Success(
                    PersonActionRefreshStats(
                        fetched = 1,
                        deleted = 0,
                        serverWatermark = Instant.parse("2026-06-03T03:00:00Z"),
                        recomputeState = "caught_up",
                    ),
                )
            }
            val repository = repository(
                api = api,
                commitmentDao = commitmentDao,
                personActionRepository = personActionRepository,
            )

            val result = repository.syncConnectedSourcesAndLoadPreview(
                userId = "user-1",
                includeGmail = false,
                includeGoogleCalendar = true,
            )

            check(result is OnboardingActivationPreviewResult.Ready)
            assertEquals(SourceType.GOOGLE_CALENDAR, result.previews.single().sourceType)
            assertEquals("pa-onboarding-commitment-1", result.previews.single().actionItemId)
            coVerify(exactly = 1) {
                api.syncCalendarEvents(
                    provider = SourceType.GOOGLE_CALENDAR,
                    mode = "activation_preview",
                )
            }
            coVerify(exactly = 1) {
                personActionRepository.refresh(userId = "user-1", surface = "onboarding")
            }
        }

    @Test
    fun acceptPreviewActionRecordsUsefulFeedbackWithoutCompletingAction() = runTest(dispatcher) {
        val api = mockk<RailwayApi>(relaxed = true)
        val commitmentDao = mockk<CommitmentDao>(relaxed = true)
        val personActionRepository = mockk<PersonActionRepository>()
        coEvery {
            personActionRepository.submitActionFeedback(
                userId = "user-1",
                actionItemId = "pa-confirm-1",
                feedbackType = "useful",
                reason = "onboarding_user_accepted",
                correctedPersonId = null,
                correctedDueAt = null,
            )
        } returns BecalmResult.Success(personActionMutationStats())
        val repository = repository(
            api = api,
            commitmentDao = commitmentDao,
            personActionRepository = personActionRepository,
        )

        val result = repository.acceptPreviewAction(userId = "user-1", actionItemId = "pa-confirm-1")

        check(result is BecalmResult.Success)
        coVerify(exactly = 1) {
            personActionRepository.submitActionFeedback(
                userId = "user-1",
                actionItemId = "pa-confirm-1",
                feedbackType = "useful",
                reason = "onboarding_user_accepted",
                correctedPersonId = null,
                correctedDueAt = null,
            )
        }
    }

    @Test
    fun dismissPreviewActionDismissesOnboardingAction() = runTest(dispatcher) {
        val api = mockk<RailwayApi>(relaxed = true)
        val commitmentDao = mockk<CommitmentDao>(relaxed = true)
        val personActionRepository = mockk<PersonActionRepository>()
        coEvery {
            personActionRepository.dismissAction(
                userId = "user-1",
                actionItemId = "pa-dismiss-1",
                reason = "onboarding_user_dismissed",
                expectedUpdatedAt = null,
            )
        } returns BecalmResult.Success(personActionMutationStats())
        val repository = repository(
            api = api,
            commitmentDao = commitmentDao,
            personActionRepository = personActionRepository,
        )

        val result = repository.dismissPreviewAction(userId = "user-1", actionItemId = "pa-dismiss-1")

        check(result is BecalmResult.Success)
        coVerify(exactly = 1) {
            personActionRepository.dismissAction(
                userId = "user-1",
                actionItemId = "pa-dismiss-1",
                reason = "onboarding_user_dismissed",
                expectedUpdatedAt = null,
            )
        }
    }

    private fun repository(
        api: RailwayApi,
        commitmentDao: CommitmentDao,
        personActionRepository: PersonActionRepository,
    ): OnboardingActivationPreviewRepositoryImpl =
        OnboardingActivationPreviewRepositoryImpl(
            apiProvider = Provider { api },
            rawIngestionRepository = mockk<RawIngestionRepository>(relaxed = true),
            commitmentRepository = mockk<CommitmentRepository>(relaxed = true),
            sourceEventParticipantRepository = mockk<SourceEventParticipantRepository>(relaxed = true),
            commitmentParticipantRepository = mockk<CommitmentParticipantRepository>(relaxed = true),
            personActionRepository = personActionRepository,
            sourceStatusRepository = mockk<SourceStatusRepository>(relaxed = true),
            processingStatusRepository = mockk<ProcessingStatusRepository>(relaxed = true),
            commitmentDao = commitmentDao,
            userCorrectionRepository = mockk<UserCorrectionRepository>(relaxed = true),
            workScheduler = mockk<WorkScheduler>(relaxed = true),
            logger = mockk<Logger>(relaxed = true),
            ioDispatcher = dispatcher,
        )
}

private fun onboardingActionEntity(
    sourceType: String = SourceType.GMAIL,
    reasonCodesCsv: String = "source:gmail,type:action,direction:take,waiting_on,onboarding:high_confidence_7d",
): PersonActionItemCacheEntity =
    PersonActionItemCacheEntity(
        id = "pa-onboarding-commitment-1",
        userId = "user-1",
        personId = "person-1",
        personDisplayName = "Jane Kim",
        personSortKey = "jane kim",
        surfacesCsv = "commitment,onboarding,person,schedule",
        actionKind = "follow_up",
        status = "active",
        title = "Jane Kim · Wait for redlines 팔로업",
        primaryVerb = "팔로업",
        shortReason = "기한: tomorrow",
        commitmentId = "commitment-waiting-1",
        calendarEventId = null,
        sourceEventId = "source-event-onboarding-1",
        sourceType = sourceType,
        sourceRef = "gmail-waiting-1",
        dueAt = Instant.parse("2026-06-04T03:00:00Z"),
        dueHint = "tomorrow",
        dueIsApproximate = false,
        staleAfter = Instant.parse("2026-06-11T03:00:00Z"),
        urgencyScore = 92.0,
        importanceScore = 70.0,
        confidence = 0.9,
        reasonCodesCsv = reasonCodesCsv,
        inputWatermark = Instant.parse("2026-06-03T02:00:00Z"),
        serverWatermark = Instant.parse("2026-06-03T03:00:00Z"),
        computedAt = Instant.parse("2026-06-03T02:00:01Z"),
        updatedAt = Instant.parse("2026-06-03T02:00:02Z"),
        snoozedUntil = null,
        completedAt = null,
        dismissedAt = null,
        primaryEvidenceKind = "source_event",
        primaryEvidenceId = "source-event-onboarding-1",
        primaryEvidenceSourceRef = "gmail-waiting-1",
        primaryEvidenceOccurredAt = Instant.parse("2026-06-03T01:00:00Z"),
        primaryEvidenceLabel = "Acme legal thread",
        primaryEvidenceQuote = "We'll send redlines tomorrow",
    )

private fun legacyPreviewRow(): OnboardingActivationPreviewRow =
    OnboardingActivationPreviewRow(
        commitmentId = "commitment-legacy-1",
        personId = "person-legacy",
        personName = "Legacy Person",
        participantId = null,
        participantName = null,
        participantEmail = null,
        participantPhone = null,
        contactMatched = true,
        title = "Legacy commitment preview",
        itemType = "action",
        direction = "give",
        scheduleStatus = null,
        decisionStatus = null,
        dueAt = null,
        dueHint = null,
        sourceType = SourceType.GMAIL,
        sourceTitle = "Legacy thread",
        sourceEventOccurredAt = Instant.parse("2026-06-02T01:00:00Z"),
        confidence = 0.9,
    )

private fun personActionMutationStats(): PersonActionMutationSyncStats =
    PersonActionMutationSyncStats(
        queued = 1,
        synced = 1,
        retryable = 0,
        failed = 0,
    )
