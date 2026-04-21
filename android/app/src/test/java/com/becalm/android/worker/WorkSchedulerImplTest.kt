package com.becalm.android.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.worker.ingestion.GoogleCalendarWorker
import com.becalm.android.worker.ingestion.ImapDaumWorker
import com.becalm.android.worker.ingestion.OutlookCalendarWorker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [WorkSchedulerImpl] — the Round 6A.5b wire-up that connects the SP-27b
 * workers ([ImapDaumWorker], [OutlookCalendarWorker]) to the [WorkScheduler] dispatch table.
 *
 * Strategy: `mockkStatic(WorkManager::class)` intercepts [WorkManager.getInstance] so the
 * scheduler's `workManager` getter returns a MockK spy. We then assert:
 *   * enqueueImapDaumOneShotNow / enqueueOutlookCalOneShotNow build a [OneTimeWorkRequest]
 *     for the correct worker class and call [WorkManager.enqueueUniqueWork] with the
 *     [UniqueWorkKeys] constant + [ExistingWorkPolicy.REPLACE].
 *   * resolveSource routes [SourceType.DAUM_IMAP] and [SourceType.OUTLOOK_CALENDAR] to
 *     their respective UniqueWorkKeys via enqueueExpedited / enqueuePeriodic.
 *   * cancelAll cancels every entry in ALL_KEYS (including DAUM_IMAP — the 6A.5b addition).
 *
 * Spec refs: SYNC-005, SP-27b, round6-plan § 6A.5.
 */
@RunWith(RobolectricTestRunner::class)
class WorkSchedulerImplTest {

    private val context: Context = mockk(relaxed = true)
    private val workManager: WorkManager = mockk(relaxed = true)
    private val logger = RecordingLogger()

    private lateinit var scheduler: WorkSchedulerImpl

    @Before
    fun setUp() {
        // The scheduler's `workManager` getter re-resolves on every call. Intercept the
        // static factory so every call yields the same MockK spy we can verify against.
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(context) } returns workManager
        every { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } returns
            mockk(relaxed = true)

        scheduler = WorkSchedulerImpl(context = context, logger = logger)
    }

    @After
    fun tearDown() {
        unmockkStatic(WorkManager::class)
    }

    // ── T1: enqueueImapDaumOneShotNow enqueues ImapDaumWorker with DAUM_IMAP key ─

    @Test
    fun `enqueueImapDaumOneShotNow schedules ImapDaumWorker under DAUM_IMAP key with REPLACE policy`() {
        val keySlot = slot<String>()
        val policySlot = slot<ExistingWorkPolicy>()
        val requestSlot = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(capture(keySlot), capture(policySlot), capture(requestSlot))
        } returns mockk(relaxed = true)

        scheduler.enqueueImapDaumOneShotNow()

        assertEquals(UniqueWorkKeys.DAUM_IMAP, keySlot.captured)
        assertEquals(ExistingWorkPolicy.REPLACE, policySlot.captured)
        // WorkRequest carries the target worker-class name — assert on the stringified form
        // because ListenableWorker.Class<*> metadata is opaque at this level.
        assertEquals(
            ImapDaumWorker::class.java.name,
            requestSlot.captured.workSpec.workerClassName,
        )
    }

    // ── T2: enqueueOutlookCalOneShotNow enqueues OutlookCalendarWorker with OUTLOOK_CAL key ─

    @Test
    fun `enqueueOutlookCalOneShotNow schedules OutlookCalendarWorker under OUTLOOK_CAL key`() {
        val keySlot = slot<String>()
        val requestSlot = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(capture(keySlot), any(), capture(requestSlot))
        } returns mockk(relaxed = true)

        scheduler.enqueueOutlookCalOneShotNow()

        assertEquals(UniqueWorkKeys.OUTLOOK_CAL, keySlot.captured)
        assertEquals(
            OutlookCalendarWorker::class.java.name,
            requestSlot.captured.workSpec.workerClassName,
        )
    }

    // ── T3: resolveSource dispatches SourceType.DAUM_IMAP → ImapDaumWorker ───

    @Test
    fun `enqueueExpedited with DAUM_IMAP routes to ImapDaumWorker under DAUM_IMAP key`() {
        val keySlot = slot<String>()
        val requestSlot = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(capture(keySlot), any(), capture(requestSlot))
        } returns mockk(relaxed = true)

        scheduler.enqueueExpedited(SourceType.DAUM_IMAP)

        assertEquals(UniqueWorkKeys.DAUM_IMAP, keySlot.captured)
        assertEquals(
            ImapDaumWorker::class.java.name,
            requestSlot.captured.workSpec.workerClassName,
        )
    }

    // ── T4: resolveSource dispatches SourceType.OUTLOOK_CALENDAR correctly ───

    @Test
    fun `enqueueExpedited with OUTLOOK_CALENDAR routes to OutlookCalendarWorker under OUTLOOK_CAL key`() {
        val keySlot = slot<String>()
        val requestSlot = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(capture(keySlot), any(), capture(requestSlot))
        } returns mockk(relaxed = true)

        scheduler.enqueueExpedited(SourceType.OUTLOOK_CALENDAR)

        assertEquals(UniqueWorkKeys.OUTLOOK_CAL, keySlot.captured)
        assertEquals(
            OutlookCalendarWorker::class.java.name,
            requestSlot.captured.workSpec.workerClassName,
        )
    }

    // ── T5: GoogleCalendarWorker & GCAL — sanity route ───────────────────────

    @Test
    fun `enqueueGCalOneShotNow schedules GoogleCalendarWorker under GCAL key`() {
        val keySlot = slot<String>()
        val requestSlot = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(capture(keySlot), any(), capture(requestSlot))
        } returns mockk(relaxed = true)

        scheduler.enqueueGCalOneShotNow()

        assertEquals(UniqueWorkKeys.GCAL, keySlot.captured)
        assertEquals(
            GoogleCalendarWorker::class.java.name,
            requestSlot.captured.workSpec.workerClassName,
        )
    }

    // ── T5b: every one-shot ingestion WorkRequest attaches NetworkType.CONNECTED ──

    @Test
    fun `one-shot ingestion WorkRequests require a connected network`() {
        val requestSlot = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(any(), any(), capture(requestSlot))
        } returns mockk(relaxed = true)

        // Every enqueue*OneShotNow / enqueueUpload call must yield a WorkRequest whose
        // Constraints declare at least NetworkType.CONNECTED. The ingestion workers hit
        // network-bound APIs (Gmail / IMAP / Graph) — without this, an offline device
        // fires the worker immediately, consumes its first attempt, and pays the full
        // exponential backoff regardless.
        val connectedCallers = listOf<() -> Unit>(
            scheduler::enqueueMediaStoreOneShotNow,
            scheduler::enqueueGmailOneShotNow,
            scheduler::enqueueImapNaverOneShotNow,
            scheduler::enqueueImapDaumOneShotNow,
            scheduler::enqueueOutlookMailOneShotNow,
            scheduler::enqueueGCalOneShotNow,
            scheduler::enqueueOutlookCalOneShotNow,
            scheduler::enqueueEnrichment,
            { scheduler.enqueueUpload(attempt = 0) },
        )

        for (enqueue in connectedCallers) {
            enqueue()
            val required = requestSlot.captured.workSpec.constraints.requiredNetworkType
            assertTrue(
                "expected NetworkType.CONNECTED or stricter but was $required",
                required == NetworkType.CONNECTED ||
                    required == NetworkType.UNMETERED ||
                    required == NetworkType.METERED,
            )
        }
    }

    // ── T6: cancelAll sweeps every entry in ALL_KEYS including DAUM_IMAP ────

    @Test
    fun `cancelAll cancels unique work for every key including DAUM_IMAP and OUTLOOK_CAL`() {
        scheduler.cancelAll()

        // Exercise keys added/kept in 6A.5b. Verifying each one individually catches a
        // regression where DAUM_IMAP is missing from the private ALL_KEYS list — the bug
        // we would have shipped before wiring SP-27b.
        verify { workManager.cancelUniqueWork(UniqueWorkKeys.MEDIA_STORE) }
        verify { workManager.cancelUniqueWork(UniqueWorkKeys.GMAIL) }
        verify { workManager.cancelUniqueWork(UniqueWorkKeys.NAVER_IMAP) }
        verify { workManager.cancelUniqueWork(UniqueWorkKeys.DAUM_IMAP) }
        verify { workManager.cancelUniqueWork(UniqueWorkKeys.OUTLOOK_MAIL) }
        verify { workManager.cancelUniqueWork(UniqueWorkKeys.GCAL) }
        verify { workManager.cancelUniqueWork(UniqueWorkKeys.OUTLOOK_CAL) }
        verify { workManager.cancelUniqueWork(UniqueWorkKeys.UPLOAD) }
        verify { workManager.cancelUniqueWork(UniqueWorkKeys.ENRICHMENT) }

        // Voice uploads use dynamic keys — they're reached via tag-based cancellation.
        verify { workManager.cancelAllWorkByTag(any()) }

        // Structural guarantee: the scheduler actually logged the call — proves the path
        // reached the summary log and didn't short-circuit somewhere inside a loop.
        val cancelLog = logger.entries.firstOrNull { it.message.startsWith("cancelAll") }
        assertNotNull("cancelAll must log a summary line on completion", cancelLog)
    }

    // ── T7: cleanupLegacyWorkNames cancels the pre-#13 ingest.sms_call unique-work ──

    @Test
    fun `cleanupLegacyWorkNames cancels legacy ingest_sms_call key`() {
        scheduler.cleanupLegacyWorkNames()

        // Contract: devices upgrading from the pre-#13 build have WorkManager unique-work
        // enqueued under `ingest.sms_call`. Round-2 codex review caught that `cancelAll()`
        // (which only runs on sign-out) is insufficient because the rename happens at app
        // start, not sign-out. This cold-start sweep prevents duplicate MediaStore
        // scheduling (old legacy + new `ingest.media_store`).
        val legacyKey = UniqueWorkKeys.LEGACY_MEDIA_STORE_KEY
        assertEquals("legacy constant must match the pre-#13 wire name", "ingest.sms_call", legacyKey)
        verify { workManager.cancelUniqueWork("ingest.sms_call") }

        // Intentionally NOT in the live ALL_KEYS sweep — this method is the sole caller.
        // If a future refactor adds it to ALL_KEYS we'd silently widen the hot path.
        val cleanupLog = logger.entries.firstOrNull { it.message.startsWith("cleanupLegacyWorkNames") }
        assertNotNull("cleanupLegacyWorkNames must log a summary line on completion", cleanupLog)
    }
}
