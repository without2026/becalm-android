package com.becalm.android.flow

import android.app.Application
import androidx.room.Room
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.datastore.UserPrefsStore
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Integration tests for the VOI-004 consent state machine:
 * awaiting_consent → pending transition driven by
 * [RawIngestionEventDao.releaseAwaitingConsentVoice].
 *
 * All DAO methods required here ([findVoiceAwaitingConsent], [releaseAwaitingConsentVoice])
 * exist in the current codebase — no @Ignore annotations needed.
 *
 * Spec refs: VOI-004, data-model.yml § sync_status awaiting_consent.
 *
 * NOTE: [UserPrefsStore.setThirdPartyProvisionConsent] exists but tests here call
 * the DAO release method directly (simulating what OnboardingViewModel/SettingsViewModel
 * would do in response to the DataStore toggle). Full ViewModel integration is covered
 * in the ViewModel test layer.
 *
 * NOTE ON DEPS: `androidx.test.core` is androidTestImplementation only.
 * Robolectric context via `RuntimeEnvironment.getApplication()` is used instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PipaConsentReleaseFlowTest {

    private lateinit var db: BeCalmDatabase
    private lateinit var dao: RawIngestionEventDao
    // UserPrefsStore mock kept for completeness; DataStore tests belong in a separate
    // UserPrefsStoreTest that uses a real in-memory DataStore.
    private lateinit var userPrefsStore: UserPrefsStore

    private val testUserId = "flow-test-user-uuid-001"

    @Before
    fun setUp() {
        val context: Application = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, BeCalmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.rawIngestionEventDao()
        userPrefsStore = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun makeVoiceEntity(
        id: String,
        syncStatus: String = "awaiting_consent",
    ): RawIngestionEventEntity = RawIngestionEventEntity(
        id = id,
        userId = testUserId,
        clientEventId = "client-$id",
        sourceType = "voice",
        syncStatus = syncStatus,
        timestamp = Clock.System.now(),
    )

    private fun makeEmailEntity(
        id: String,
        syncStatus: String = "pending",
    ): RawIngestionEventEntity = RawIngestionEventEntity(
        id = id,
        userId = testUserId,
        clientEventId = "client-$id",
        sourceType = "gmail",
        syncStatus = syncStatus,
        timestamp = Clock.System.now(),
    )

    // ---------------------------------------------------------------------------
    // FLOW-01: Three awaiting_consent voice rows become pending after release
    // ---------------------------------------------------------------------------

    @Test
    fun `FLOW-01 three awaiting_consent voice rows become pending after releaseAwaitingConsentVoice`() = runBlocking {
        // Step 1: Seed 3 voice rows with awaiting_consent
        dao.insert(makeVoiceEntity("voice-row-001"))
        dao.insert(makeVoiceEntity("voice-row-002"))
        dao.insert(makeVoiceEntity("voice-row-003"))

        // Verify seed
        val awaitingBefore = dao.findVoiceAwaitingConsent(testUserId)
        assertEquals(3, awaitingBefore.size)

        // Step 2: Simulate consent toggle ON → ViewModel calls releaseAwaitingConsentVoice
        val released = dao.releaseAwaitingConsentVoice(testUserId)
        assertEquals(3, released)

        // Step 3: All 3 rows now have syncStatus="pending"
        val nowPending = dao.findPendingForUpload(testUserId, 10)
        assertEquals(3, nowPending.size)
        assertTrue(nowPending.all { it.syncStatus == "pending" })
        assertTrue(nowPending.all { it.sourceType == "voice" })

        // Step 4: findVoiceAwaitingConsent returns empty
        val stillAwaiting = dao.findVoiceAwaitingConsent(testUserId)
        assertTrue("No rows should remain awaiting_consent", stillAwaiting.isEmpty())
    }

    // ---------------------------------------------------------------------------
    // FLOW-02: Without calling release, rows remain in awaiting_consent
    // ---------------------------------------------------------------------------

    @Test
    fun `FLOW-02 rows remain awaiting_consent when release is not called`() = runBlocking {
        dao.insert(makeVoiceEntity("voice-row-A"))
        dao.insert(makeVoiceEntity("voice-row-B"))

        // No release called (simulates consent=false or consent not toggled)
        val stillAwaiting = dao.findVoiceAwaitingConsent(testUserId)
        assertEquals(
            "Rows should stay awaiting_consent without calling releaseAwaitingConsentVoice",
            2,
            stillAwaiting.size,
        )
        assertTrue(stillAwaiting.all { it.syncStatus == "awaiting_consent" })
    }

    // ---------------------------------------------------------------------------
    // FLOW-03: Release only affects voice + awaiting_consent rows
    // ---------------------------------------------------------------------------

    @Test
    fun `FLOW-03 release does not touch non-voice or non-awaiting rows`() = runBlocking {
        // Seed: voice awaiting (should be released), gmail pending (untouched),
        // voice failed (untouched — already terminal), voice synced (untouched)
        dao.insert(makeVoiceEntity("voice-awaiting-001", syncStatus = "awaiting_consent"))
        dao.insert(makeVoiceEntity("voice-awaiting-002", syncStatus = "awaiting_consent"))
        dao.insert(makeEmailEntity("gmail-pending-001", syncStatus = "pending"))
        dao.insert(makeVoiceEntity("voice-failed-001", syncStatus = "failed"))
        dao.insert(makeVoiceEntity("voice-synced-001", syncStatus = "synced"))

        // Release only voice+awaiting_consent rows
        val released = dao.releaseAwaitingConsentVoice(testUserId)
        assertEquals(2, released)

        // voice-awaiting rows are now pending
        val voiceAwaiting = dao.findVoiceAwaitingConsent(testUserId)
        assertTrue("No voice rows should remain awaiting_consent", voiceAwaiting.isEmpty())

        // Gmail row must be untouched — still pending (source_type != voice so not released)
        val pending = dao.findPendingForUpload(testUserId, 10)
        assertTrue(
            "gmail-pending-001 must remain pending (source_type gmail must not be mutated by voice release)",
            pending.any { it.id == "gmail-pending-001" },
        )
        // voice-awaiting rows are now also pending
        assertTrue(
            "voice-awaiting-001 must now be pending",
            pending.any { it.id == "voice-awaiting-001" },
        )
        assertTrue(
            "voice-awaiting-002 must now be pending",
            pending.any { it.id == "voice-awaiting-002" },
        )

        // voice-failed-001 must remain failed (not transitioned)
        val failedEntity = dao.findById(id = "voice-failed-001", userId = testUserId)
        assertEquals("failed", failedEntity?.syncStatus)

        // voice-synced-001 must remain synced (not transitioned)
        val syncedEntity = dao.findById(id = "voice-synced-001", userId = testUserId)
        assertEquals("synced", syncedEntity?.syncStatus)
    }
}
