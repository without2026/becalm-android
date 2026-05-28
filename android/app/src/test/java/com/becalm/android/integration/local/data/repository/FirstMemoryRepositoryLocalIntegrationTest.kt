package com.becalm.android.integration.local.data.repository

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.BeCalmDatabaseProvider
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.ManualMemoryCreateResponseDto
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.FirstMemoryRepositoryImpl
import com.becalm.android.domain.onboarding.FirstMemoryInput
import com.becalm.android.domain.onboarding.FirstMemoryKind
import com.becalm.android.domain.onboarding.FirstMemoryOrigin
import com.becalm.android.domain.person.SourceInteractionKind
import com.becalm.android.integration.local.LocalIntegrationSupport
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
class FirstMemoryRepositoryLocalIntegrationTest {
    private val db: BeCalmDatabase = LocalIntegrationSupport.inMemoryDatabase()
    private val userPrefsStore: UserPrefsStore = mockk {
        every { observeCurrentUserId() } returns flowOf(USER_ID)
    }
    private val databaseProvider: BeCalmDatabaseProvider = mockk {
        every { current() } returns db
    }
    private val api: RailwayApi = mockk()
    private val logger: Logger = mockk(relaxed = true)
    private val repository = FirstMemoryRepositoryImpl(
        userPrefsStore = userPrefsStore,
        databaseProvider = databaseProvider,
        commitmentDao = db.commitmentDao(),
        personIndexDao = db.personIndexDao(),
        api = api,
        logger = logger,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `save creates person commitment participant and timeline interactions idempotently`() = runTest {
        stubRemoteSuccess()
        val input = FirstMemoryInput(
            clientMemoryId = "client-1",
            origin = FirstMemoryOrigin.EMAIL,
            personName = "민지",
            promiseText = "금요일까지 제안서 초안 보내기",
            kind = FirstMemoryKind.MY_ACTION,
            dueHint = null,
        )

        val first = repository.save(input)
        val second = repository.save(input)

        assertTrue(first is BecalmResult.Success)
        assertTrue(second is BecalmResult.Success)
        val result = (first as BecalmResult.Success).value
        val person = db.personIndexDao().findPersonForMemory(USER_ID, result.personId)
        val commitment = db.commitmentDao().findByIdForUser(USER_ID, result.commitmentId)
        val participants = db.personIndexDao().findCommitmentParticipantsForMemory(USER_ID, result.personId, limit = 10)
        val interactions = db.personIndexDao().findInteractionsForMemory(USER_ID, result.personId, limit = 10)

        assertEquals("민지", person?.displayName)
        assertEquals(SourceType.MANUAL, commitment?.sourceType)
        assertEquals("synced", commitment?.syncStatus)
        assertEquals("give", commitment?.direction)
        assertEquals(1, participants.size)
        assertEquals(2, interactions.size)
        assertEquals(1, interactions.count { it.interactionKind == "commitment" })
        assertEquals(1, interactions.count { it.interactionKind == SourceInteractionKind.forSourceType(SourceType.MANUAL) })
    }

    @Test
    fun `shared schedule stores due hint without due at`() = runTest {
        stubRemoteSuccess()
        val input = FirstMemoryInput(
            clientMemoryId = "client-schedule",
            origin = FirstMemoryOrigin.MEETING,
            personName = "지훈",
            promiseText = "다음 주 화요일에 예산 리뷰하기",
            kind = FirstMemoryKind.SHARED_SCHEDULE,
            dueHint = "다음 주 화요일 오후",
        )

        val result = repository.save(input)

        assertTrue(result is BecalmResult.Success)
        val commitmentId = (result as BecalmResult.Success).value.commitmentId
        val commitment = db.commitmentDao().findByIdForUser(USER_ID, commitmentId)
        assertEquals(CommitmentItemType.SCHEDULE, commitment?.itemType)
        assertEquals("confirmed", commitment?.scheduleStatus)
        assertEquals(null, commitment?.dueAt)
        assertEquals("다음 주 화요일 오후", commitment?.dueHint)
        assertEquals(true, commitment?.dueIsApproximate)
    }

    @Test
    fun `backend failure leaves local first memory for later retry`() = runTest {
        coEvery { api.createManualMemory(any(), any()) } throws java.io.IOException("offline")
        val input = FirstMemoryInput(
            clientMemoryId = "client-offline",
            origin = FirstMemoryOrigin.MESSENGER,
            personName = "수진",
            promiseText = "다음 회의 전에 계약서 검토하기",
            kind = FirstMemoryKind.THEIR_ACTION,
            dueHint = null,
        )

        val result = repository.save(input)

        assertTrue(result is BecalmResult.Success)
        val success = result as BecalmResult.Success
        val commitmentId = success.value.commitmentId
        val commitment = db.commitmentDao().findByIdForUser(USER_ID, commitmentId)
        assertEquals("manual_pending", commitment?.syncStatus)
        assertEquals("수진", db.personIndexDao().findPersonForMemory(USER_ID, success.value.personId)?.displayName)
    }

    private fun stubRemoteSuccess() {
        coEvery { api.createManualMemory(any(), any()) } coAnswers {
            val request = secondArg<com.becalm.android.data.remote.dto.ManualMemoryCreateRequestDto>()
            Response.success(
                ManualMemoryCreateResponseDto(
                    personId = request.personId,
                    commitmentId = request.commitmentId,
                    sourceRef = "manual_memory:${request.clientMemoryId}",
                    created = true,
                ),
            )
        }
    }

    private companion object {
        private const val USER_ID = "user-first-memory"
    }
}
