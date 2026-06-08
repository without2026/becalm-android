package com.becalm.android.integration.local.data.repository

import androidx.datastore.preferences.core.edit
import app.cash.turbine.test
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.SyncCursorStoreImpl
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceStatusItemDto
import com.becalm.android.data.remote.dto.SourceStatusResponseDto
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatusPrefsKeys
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
import org.junit.Assert.assertNull
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
    private val userPrefs = LocalIntegrationSupport.prefsDataStore("source-status-user-prefs")
    private val userPrefsStore = UserPrefsStoreImpl(userPrefs)
    private val repository = SourceStatusRepositoryImpl(
        cursorStore = cursorStore,
        userPrefs = userPrefs,
        api = api,
        ioDispatcher = UnconfinedTestDispatcher(),
        logger = logger,
    )

    @Test
    fun `SMG-001 and TDY-003 observeAll emits product sources including split recording folders`() = runTest {
        repository.observeAll().test {
            val initial = awaitItem()

            assertEquals(SourceType.PRODUCT_SOURCES, initial.map { it.sourceType }.toSet())
            assertEquals(10, initial.size)
            assertTrue(initial.any { it.sourceType == SourceType.VOICE })
            assertTrue(initial.any { it.sourceType == SourceType.CALL_RECORDING })
            assertTrue(initial.any { it.sourceType == SourceType.MEETING })
            assertTrue(initial.any { it.sourceType == SourceType.MESSAGE_SCREENSHOT })
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

    @Test
    fun `recordSyncStart clears stale error while source is syncing`() = runTest {
        val failedAt = Instant.parse("2026-04-23T02:30:00Z")
        assertTrue(repository.recordSyncError(SourceType.GMAIL, "Vertex AI returned 403", failedAt) is BecalmResult.Success)
        assertTrue(repository.recordSyncStart(SourceType.GMAIL) is BecalmResult.Success)

        repository.observeFor(SourceType.GMAIL).test {
            val status = awaitItem()

            assertEquals(SourceConnectionStatus.SYNCING, status.status)
            assertNull(status.errorMessage)
            assertEquals(failedAt, status.lastSyncedAt)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `legacy in progress without started at does not stay syncing forever`() = runTest {
        userPrefsStore.setCurrentUserId("user-1")
        userPrefsStore.setEmailSourceConnected(EmailPipaProvider.GMAIL, connected = true)
        userPrefsStore.setEmailSourceManagedByBackend(EmailPipaProvider.GMAIL, managed = true)
        userPrefs.edit { prefs ->
            prefs[SourceStatusPrefsKeys.inProgress(SourceType.GMAIL)] = true
        }

        repository.observeFor(SourceType.GMAIL).test {
            val status = awaitItem()

            assertEquals(SourceConnectionStatus.CONNECTED, status.status)
            assertNull(status.errorMessage)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshFromServer clears stale sync timestamp when server reports idle without last sync`() = runTest {
        val staleSyncedAt = Instant.parse("2026-04-23T01:20:00Z")
        assertTrue(repository.recordSyncSuccess(SourceType.GMAIL, staleSyncedAt) is BecalmResult.Success)
        coEvery { api.getSourceStatus() } returns Response.success(
            SourceStatusResponseDto(
                sources = listOf(
                    SourceStatusItemDto(
                        sourceType = SourceType.GMAIL,
                        state = "idle",
                        lastSyncAt = null,
                    ),
                ),
            ),
        )

        assertTrue(repository.refreshFromServer() is BecalmResult.Success)

        repository.observeSources().test {
            var snapshot = awaitItem()
            while (snapshot[SourceType.GMAIL]?.status == SourceConnectionStatus.CONNECTED) {
                snapshot = awaitItem()
            }

            assertEquals(SourceConnectionStatus.NEVER_CONNECTED, snapshot[SourceType.GMAIL]?.status)
            assertNull(snapshot[SourceType.GMAIL]?.lastSyncedAt)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshFromServer preserves idle source as connected when server includes last sync`() = runTest {
        val syncedAt = Instant.parse("2026-04-23T01:20:00Z")
        coEvery { api.getSourceStatus() } returns Response.success(
            SourceStatusResponseDto(
                sources = listOf(
                    SourceStatusItemDto(
                        sourceType = SourceType.GMAIL,
                        state = "idle",
                        lastSyncAt = syncedAt,
                    ),
                ),
            ),
        )

        assertTrue(repository.refreshFromServer() is BecalmResult.Success)

        repository.observeSources().test {
            var snapshot = awaitItem()
            while (snapshot[SourceType.GMAIL]?.lastSyncedAt != syncedAt) {
                snapshot = awaitItem()
            }

            assertEquals(SourceConnectionStatus.CONNECTED, snapshot[SourceType.GMAIL]?.status)
            assertEquals(syncedAt, snapshot[SourceType.GMAIL]?.lastSyncedAt)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onboarding connected source remains connected when server sync status is idle without last sync`() = runTest {
        userPrefsStore.setCurrentUserId("user-1")
        userPrefsStore.setEmailSourceConnected(EmailPipaProvider.GMAIL, connected = true)
        userPrefsStore.setEmailSourceManagedByBackend(EmailPipaProvider.GMAIL, managed = true)
        coEvery { api.getSourceStatus() } returns Response.success(
            SourceStatusResponseDto(
                sources = listOf(
                    SourceStatusItemDto(
                        sourceType = SourceType.GMAIL,
                        state = "idle",
                        lastSyncAt = null,
                    ),
                ),
            ),
        )

        assertTrue(repository.refreshFromServer() is BecalmResult.Success)

        repository.observeFor(SourceType.GMAIL).test {
            var status = awaitItem()
            while (status.status != SourceConnectionStatus.CONNECTED) {
                status = awaitItem()
            }

            assertEquals(SourceConnectionStatus.CONNECTED, status.status)
            assertNull(status.lastSyncedAt)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `server connected connection state restores provider source without local prefs`() = runTest {
        coEvery { api.getSourceStatus() } returns Response.success(
            SourceStatusResponseDto(
                sources = listOf(
                    SourceStatusItemDto(
                        sourceType = SourceType.GMAIL,
                        state = "idle",
                        syncState = "idle",
                        connectionState = "connected",
                        lastSyncAt = null,
                    ),
                ),
            ),
        )

        assertTrue(repository.refreshFromServer() is BecalmResult.Success)

        repository.observeFor(SourceType.GMAIL).test {
            var status = awaitItem()
            while (status.status != SourceConnectionStatus.CONNECTED) {
                status = awaitItem()
            }

            assertEquals(SourceConnectionStatus.CONNECTED, status.status)
            assertNull(status.lastSyncedAt)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `server never connected overrides stale provider local overlay`() = runTest {
        userPrefsStore.setCurrentUserId("user-1")
        userPrefsStore.setEmailSourceConnected(EmailPipaProvider.GMAIL, connected = true)
        userPrefsStore.setEmailSourceManagedByBackend(EmailPipaProvider.GMAIL, managed = true)
        assertTrue(
            repository.recordSyncSuccess(
                SourceType.GMAIL,
                Instant.parse("2026-04-23T01:20:00Z"),
            ) is BecalmResult.Success,
        )
        coEvery { api.getSourceStatus() } returns Response.success(
            SourceStatusResponseDto(
                sources = listOf(
                    SourceStatusItemDto(
                        sourceType = SourceType.GMAIL,
                        state = "idle",
                        syncState = "idle",
                        connectionState = "never_connected",
                        lastSyncAt = null,
                    ),
                ),
            ),
        )

        assertTrue(repository.refreshFromServer() is BecalmResult.Success)

        repository.observeFor(SourceType.GMAIL).test {
            var status = awaitItem()
            while (status.lastSyncedAt != null) {
                status = awaitItem()
            }

            assertEquals(SourceConnectionStatus.NEVER_CONNECTED, status.status)
            assertNull(status.lastSyncedAt)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `server client managed keeps local source overlay`() = runTest {
        userPrefsStore.setCurrentUserId("user-1")
        userPrefsStore.setSourceEnabled(SourceType.VOICE, enabled = true)
        coEvery { api.getSourceStatus() } returns Response.success(
            SourceStatusResponseDto(
                sources = listOf(
                    SourceStatusItemDto(
                        sourceType = SourceType.VOICE,
                        state = "idle",
                        syncState = "idle",
                        connectionState = "client_managed",
                        lastSyncAt = null,
                    ),
                ),
            ),
        )

        assertTrue(repository.refreshFromServer() is BecalmResult.Success)

        repository.observeFor(SourceType.VOICE).test {
            var status = awaitItem()
            while (status.status != SourceConnectionStatus.CONNECTED) {
                status = awaitItem()
            }

            assertEquals(SourceConnectionStatus.CONNECTED, status.status)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `server needs reauth connection state renders as action required error`() = runTest {
        coEvery { api.getSourceStatus() } returns Response.success(
            SourceStatusResponseDto(
                sources = listOf(
                    SourceStatusItemDto(
                        sourceType = SourceType.GMAIL,
                        state = "error",
                        syncState = "error",
                        connectionState = "needs_reauth",
                        lastError = "reauth required",
                    ),
                ),
            ),
        )

        assertTrue(repository.refreshFromServer() is BecalmResult.Success)

        repository.observeFor(SourceType.GMAIL).test {
            var status = awaitItem()
            while (status.status != SourceConnectionStatus.ERROR) {
                status = awaitItem()
            }

            assertEquals(SourceConnectionStatus.ERROR, status.status)
            assertEquals("reauth required", status.errorMessage)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `connected local flags overlay every connectable source family before first sync`() = runTest {
        userPrefsStore.setCurrentUserId("user-1")
        userPrefsStore.setSourceEnabled(SourceType.VOICE, enabled = true)
        userPrefsStore.setSourceEnabled(SourceType.MEETING, enabled = true)
        userPrefsStore.setEmailSourceConnected(EmailPipaProvider.NAVER_IMAP, connected = true)
        userPrefsStore.setSourceEnabled(SourceType.GOOGLE_CALENDAR, enabled = true)

        repository.observeSources().test {
            var snapshot = awaitItem()
            while (snapshot[SourceType.GOOGLE_CALENDAR]?.status != SourceConnectionStatus.CONNECTED) {
                snapshot = awaitItem()
            }

            assertEquals(SourceConnectionStatus.CONNECTED, snapshot[SourceType.VOICE]?.status)
            assertEquals(SourceConnectionStatus.CONNECTED, snapshot[SourceType.MEETING]?.status)
            assertEquals(SourceConnectionStatus.CONNECTED, snapshot[SourceType.NAVER_IMAP]?.status)
            assertEquals(SourceConnectionStatus.CONNECTED, snapshot[SourceType.GOOGLE_CALENDAR]?.status)
            assertEquals(SourceConnectionStatus.NEVER_CONNECTED, snapshot[SourceType.OUTLOOK_CALENDAR]?.status)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
