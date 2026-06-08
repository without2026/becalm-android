package com.becalm.android.unit.ui.main

import app.cash.turbine.test
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity
import com.becalm.android.data.repository.PersonActionRepository
import com.becalm.android.ui.main.MainTabNavViewModel
import com.becalm.android.ui.main.PERSON_ACTION_NAV_BADGE_QUERY_LIMIT
import com.becalm.android.ui.main.PERSON_ACTION_NAV_SURFACE
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainTabNavViewModelSpecTest {

    private val dispatcher = StandardTestDispatcher()
    private val userPrefsStore: UserPrefsStore = mockk()
    private val personActionRepository: PersonActionRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `person nav badge observes urgent active person action cache rows`() = runTest {
        val rows = MutableStateFlow(
            listOf(
                action(id = "urgent-decimal", urgencyScore = 0.92),
                action(id = "urgent-percent", urgencyScore = 64.0),
                action(id = "later", urgencyScore = 0.42),
            ),
        )
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every {
            personActionRepository.observeActiveForSurface(
                userId = "user-1",
                surface = PERSON_ACTION_NAV_SURFACE,
                limit = PERSON_ACTION_NAV_BADGE_QUERY_LIMIT,
            )
        } returns rows

        val viewModel = MainTabNavViewModel(
            userPrefsStore = userPrefsStore,
            personActionRepository = personActionRepository,
        )

        viewModel.state.test {
            assertEquals(0, awaitItem().personActionBadgeCount)
            assertEquals(2, awaitItem().personActionBadgeCount)
            rows.value = listOf(action(id = "not-urgent", urgencyScore = 0.59))
            assertEquals(0, awaitItem().personActionBadgeCount)
            cancelAndIgnoreRemainingEvents()
        }

        verify {
            personActionRepository.observeActiveForSurface(
                userId = "user-1",
                surface = PERSON_ACTION_NAV_SURFACE,
                limit = PERSON_ACTION_NAV_BADGE_QUERY_LIMIT,
            )
        }
    }

    private fun action(
        id: String,
        urgencyScore: Double,
    ): PersonActionItemCacheEntity {
        val now = Instant.parse("2026-06-04T01:00:00Z")
        return PersonActionItemCacheEntity(
            id = id,
            userId = "user-1",
            personId = "person-$id",
            personDisplayName = "김도현",
            personSortKey = "김도현",
            surfacesCsv = "person,commitment",
            actionKind = "reply",
            status = "active",
            title = "수정 계약서 회신",
            primaryVerb = "답장",
            shortReason = "오늘까지 보내기로 한 약속입니다.",
            commitmentId = null,
            calendarEventId = null,
            sourceEventId = null,
            sourceType = "gmail",
            sourceRef = "mail:$id",
            dueAt = null,
            dueHint = null,
            dueIsApproximate = false,
            staleAfter = null,
            urgencyScore = urgencyScore,
            importanceScore = 0.8,
            confidence = 0.9,
            reasonCodesCsv = "due_soon",
            inputWatermark = now,
            serverWatermark = now,
            computedAt = now,
            updatedAt = now,
            snoozedUntil = null,
            completedAt = null,
            dismissedAt = null,
        )
    }
}
