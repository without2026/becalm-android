package com.becalm.android.integration.local.worker

import com.becalm.android.core.util.FakeClock
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CommitmentRepositoryImpl
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.worker.OverdueSweepWorker
import com.becalm.android.worker.ProcessingPauseGate
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OverdueSweepWorkerLocalIntegrationTest {

    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val logger = RecordingLogger()
    private val authRepository = mockk<AuthRepository>()
    private val api = mockk<RailwayApi>(relaxed = true)
    private val cursorStore = mockk<com.becalm.android.data.local.datastore.SyncCursorStore>(relaxed = true)
    private val userPrefsStore = mockk<com.becalm.android.data.local.datastore.UserPrefsStore>(relaxed = true)
    private val clock = FakeClock(Instant.parse("2026-04-23T00:00:00Z"))
    private val session = LocalIntegrationSupport.authenticatedSession()
    private val processingPauseGate = ProcessingPauseGate(
        userPrefsStore = UserPrefsStoreImpl(LocalIntegrationSupport.prefsDataStore("overdue-sweep-pause")),
        logger = logger,
        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    private val repository = CommitmentRepositoryImpl(
        dao = db.commitmentDao(),
        api = api,
        cursorStore = cursorStore,
        userPrefsStore = userPrefsStore,
        database = db,
        logger = logger,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    @Before
    fun setUp() {
        coEvery { authRepository.currentSession() } returns session
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `CMT-011 local worker marks only eligible rows overdue`() = runTest {
        val now = clock.nowInstant
        db.commitmentDao().insertAll(
            listOf(
                commitment("pending-old", "pending", Instant.parse("2026-04-21T00:00:00Z")),
                commitment("reminded-old", "reminded", Instant.parse("2026-04-21T01:00:00Z")),
                commitment("followed-up-old", "followed_up", Instant.parse("2026-04-21T02:00:00Z")),
                commitment("future", "pending", Instant.parse("2026-04-23T12:00:00Z")),
                commitment("completed", "completed", Instant.parse("2026-04-21T00:00:00Z")),
                commitment("no-due", "pending", null),
                commitment("deleted", "pending", Instant.parse("2026-04-21T00:00:00Z")).copy(
                    deletedAt = Instant.parse("2026-04-22T00:00:00Z"),
                ),
            ),
        )

        val result = OverdueSweepWorker(
            appContext = LocalIntegrationSupport.appContext(),
            workerParams = LocalIntegrationSupport.workerParams(),
            authRepository = authRepository,
            commitmentRepository = repository,
            clock = clock,
            processingPauseGate = processingPauseGate,
            logger = logger,
        ).doWork()

        val rows = db.commitmentDao().observeAllForUser(session.userId).first()
        val byId = rows.associateBy { it.id }
        assertEquals(androidx.work.ListenableWorker.Result.success().javaClass, result.javaClass)
        assertEquals(3, result.outputData.getInt(OverdueSweepWorker.KEY_CANDIDATE_COUNT, -1))
        assertEquals(3, result.outputData.getInt(OverdueSweepWorker.KEY_MARKED_COUNT, -1))
        assertEquals("overdue", byId.getValue("pending-old").actionState)
        assertEquals("overdue", byId.getValue("reminded-old").actionState)
        assertEquals("overdue", byId.getValue("followed-up-old").actionState)
        assertEquals("pending", byId.getValue("future").actionState)
        assertEquals("completed", byId.getValue("completed").actionState)
        assertEquals("pending", byId.getValue("no-due").actionState)
        assertTrue(byId["deleted"] == null)
        assertEquals("pending", byId.getValue("pending-old").syncStatus)
        assertEquals(now, byId.getValue("pending-old").updatedAt)
    }

    private fun commitment(
        id: String,
        actionState: String,
        dueAt: Instant?,
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = session.userId,
        direction = "give",
        counterpartyRaw = "counterparty",
        personRef = "counterparty@example.com",
        title = id,
        description = null,
        quote = "quote-$id",
        sourceEventTitle = "source-$id",
        sourceEventOccurredAt = Instant.parse("2026-04-20T00:00:00Z"),
        dueAt = dueAt,
        dueHint = null,
        actionState = actionState,
        sourceType = SourceType.GMAIL,
        sourceRef = "source-ref-$id",
        createdAt = Instant.parse("2026-04-20T00:00:00Z"),
        updatedAt = Instant.parse("2026-04-20T00:00:00Z"),
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "synced",
    )
}
