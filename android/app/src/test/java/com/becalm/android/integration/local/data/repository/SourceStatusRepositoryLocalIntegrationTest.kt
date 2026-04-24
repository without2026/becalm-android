package com.becalm.android.integration.local.data.repository

import app.cash.turbine.test
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.SyncCursorStoreImpl
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceStatusItemDto
import com.becalm.android.data.remote.dto.SourceStatusResponseDto
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatusRepositoryImpl
import com.becalm.android.integration.local.LocalIntegrationSupport
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SourceStatusRepositoryLocalIntegrationTest {

    private val logger = RecordingLogger()
    private val api = mockk<RailwayApi>()
    private val cursorStore = SyncCursorStoreImpl(
        LocalIntegrationSupport.prefsDataStore("source-status-cursors"),
    )
    private val repository = SourceStatusRepositoryImpl(
        cursorStore = cursorStore,
        userPrefs = LocalIntegrationSupport.prefsDataStore("source-status-user-prefs"),
        api = api,
        ioDispatcher = UnconfinedTestDispatcher(),
        logger = logger,
    )

    @Test
    fun `SMG-001 and TDY-003 observeAll emits seven product sources including voice and excluding call recording`() = runTest {
        repository.observeAll().test {
            val initial = awaitItem()

            assertEquals(SourceType.PRODUCT_SOURCES, initial.map { it.sourceType }.toSet())
            assertEquals(7, initial.size)
            assertFalse(initial.any { it.sourceType == SourceType.CALL_RECORDING })
            assertTrue(initial.all { it.status == SourceConnectionStatus.NEVER_CONNECTED })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `TDY-008 local record methods plus server merge update derived statuses`() = runTest {
        val gmailSyncedAt = Instant.parse("2026-04-23T01:20:00Z")
        val outlookErroredAt = Instant.parse("2026-04-23T02:30:00Z")
        coEvery { api.getSourceStatus() } returns Response.success(
            SourceStatusResponseDto(
                sources = listOf(
                    SourceStatusItemDto(
                        sourceType = SourceType.GMAIL,
                        state = "synced",
                        lastSyncAt = gmailSyncedAt,
                    ),
                    SourceStatusItemDto(
                        sourceType = SourceType.OUTLOOK_MAIL,
                        state = "error",
                        lastError = "token expired",
                    ),
                ),
            ),
        )

        val result = repository.recordSyncStart(SourceType.VOICE)
        assertTrue(result is BecalmResult.Success)
        assertTrue(repository.refreshFromServer() is BecalmResult.Success)

        repository.observeSources().test {
            var snapshot = awaitItem()
            while (
                snapshot[SourceType.GMAIL]?.lastSyncedAt != gmailSyncedAt ||
                    snapshot[SourceType.OUTLOOK_MAIL]?.errorMessage != "token expired"
            ) {
                snapshot = awaitItem()
            }

            assertEquals(SourceConnectionStatus.SYNCING, snapshot[SourceType.VOICE]?.status)
            assertEquals(SourceConnectionStatus.CONNECTED, snapshot[SourceType.GMAIL]?.status)
            assertEquals(gmailSyncedAt, snapshot[SourceType.GMAIL]?.lastSyncedAt)
            assertEquals(SourceConnectionStatus.ERROR, snapshot[SourceType.OUTLOOK_MAIL]?.status)
            assertEquals("token expired", snapshot[SourceType.OUTLOOK_MAIL]?.errorMessage)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
