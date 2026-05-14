package com.becalm.android.integration.local.worker

import androidx.lifecycle.LifecycleOwner
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.worker.ForegroundCatchUpScheduler
import com.becalm.android.worker.ForegroundWorkScheduler
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.RuntimeSyncSourceResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundCatchUpSchedulerLocalIntegrationTest {

    @Test
    // spec: ING-006
    // spec: ING-011
    fun `enabled source preferences fan out to matching foreground workers only`() = runTest {
        val userPrefsStore = UserPrefsStoreImpl(
            dataStore = LocalIntegrationSupport.prefsDataStore("foreground-catchup"),
        )
        val workScheduler = FakeForegroundWorkScheduler()
        val scheduler = ForegroundCatchUpScheduler(
            scope = this,
            workScheduler = workScheduler,
            userPrefsStore = userPrefsStore,
            runtimeSyncSourceResolver = FakeRuntimeSyncSourceResolver(userPrefsStore),
            processingPauseGate = ProcessingPauseGate(userPrefsStore, RecordingLogger(), backgroundScope),
            logger = RecordingLogger(),
        )

        userPrefsStore.setCurrentUserId("user-1")
        userPrefsStore.setSourceEnabled(SourceType.VOICE, true)
        userPrefsStore.setEmailSourceConnected(EmailPipaProvider.GMAIL, true)
        userPrefsStore.setEmailSourceManagedByBackend(EmailPipaProvider.GMAIL, true)
        userPrefsStore.setEmailSourceConnected(EmailPipaProvider.DAUM_IMAP, true)
        userPrefsStore.setSourceEnabled(SourceType.GOOGLE_CALENDAR, true)
        assertEquals(
            setOf(SourceType.VOICE, SourceType.DAUM_IMAP, SourceType.GOOGLE_CALENDAR),
            userPrefsStore.observeEnabledSources().first(),
        )

        scheduler.triggerCatchUp()
        advanceUntilIdle()

        assertEquals(1, workScheduler.mediaStoreCount)
        assertEquals(1, workScheduler.imapDaumCount)
        assertEquals(1, workScheduler.gCalCount)
        assertEquals(0, workScheduler.imapNaverCount)
        assertEquals(0, workScheduler.outlookCalCount)
    }

    @Test
    // spec: ING-001B
    // spec: ING-011
    fun `meeting source preference is user scoped and fans out to media worker`() = runTest {
        val userPrefsStore = UserPrefsStoreImpl(
            dataStore = LocalIntegrationSupport.prefsDataStore("foreground-meeting"),
        )
        val workScheduler = FakeForegroundWorkScheduler()
        val scheduler = ForegroundCatchUpScheduler(
            scope = this,
            workScheduler = workScheduler,
            userPrefsStore = userPrefsStore,
            runtimeSyncSourceResolver = FakeRuntimeSyncSourceResolver(userPrefsStore),
            processingPauseGate = ProcessingPauseGate(userPrefsStore, RecordingLogger(), backgroundScope),
            logger = RecordingLogger(),
        )

        userPrefsStore.setCurrentUserId("user-1")
        userPrefsStore.setSourceEnabled(SourceType.MEETING, true)

        assertEquals(true, userPrefsStore.observeSourceEnabled(SourceType.MEETING).first())
        assertEquals(setOf(SourceType.MEETING), userPrefsStore.observeEnabledSources().first())

        scheduler.triggerCatchUp()
        advanceUntilIdle()

        assertEquals(1, workScheduler.mediaStoreCount)
    }

    @Test
    // spec: ING-007
    // spec: ING-010
    // spec: ING-011
    fun `onStart reads user scoped enabled sources from prefs before scheduling`() = runTest {
        val userPrefsStore = UserPrefsStoreImpl(
            dataStore = LocalIntegrationSupport.prefsDataStore("foreground-onstart"),
        )
        val workScheduler = FakeForegroundWorkScheduler()
        val scheduler = ForegroundCatchUpScheduler(
            scope = this,
            workScheduler = workScheduler,
            userPrefsStore = userPrefsStore,
            runtimeSyncSourceResolver = FakeRuntimeSyncSourceResolver(userPrefsStore),
            processingPauseGate = ProcessingPauseGate(userPrefsStore, RecordingLogger(), backgroundScope),
            logger = RecordingLogger(),
        )

        userPrefsStore.setCurrentUserId("user-1")
        userPrefsStore.setSourceEnabled(SourceType.OUTLOOK_CALENDAR, true)
        userPrefsStore.setEmailSourceConnected(EmailPipaProvider.OUTLOOK_MAIL, true)
        userPrefsStore.setEmailSourceManagedByBackend(EmailPipaProvider.OUTLOOK_MAIL, true)
        assertEquals(
            setOf(SourceType.OUTLOOK_CALENDAR),
            userPrefsStore.observeEnabledSources().first(),
        )

        scheduler.onStart(FakeLifecycleOwner)
        advanceUntilIdle()

        assertEquals(1, workScheduler.outlookCalCount)
        assertTrue(workScheduler.enqueuedSources.containsAll(listOf("outlook_calendar")))
    }

    @Test
    fun `onStart catch-up runs once per source per process`() = runTest {
        val userPrefsStore = UserPrefsStoreImpl(
            dataStore = LocalIntegrationSupport.prefsDataStore("foreground-onstart-once"),
        )
        val workScheduler = FakeForegroundWorkScheduler()
        val scheduler = ForegroundCatchUpScheduler(
            scope = this,
            workScheduler = workScheduler,
            userPrefsStore = userPrefsStore,
            runtimeSyncSourceResolver = FakeRuntimeSyncSourceResolver(userPrefsStore),
            processingPauseGate = ProcessingPauseGate(userPrefsStore, RecordingLogger(), backgroundScope),
            logger = RecordingLogger(),
        )

        userPrefsStore.setCurrentUserId("user-1")
        userPrefsStore.setSourceEnabled(SourceType.VOICE, true)

        scheduler.onStart(FakeLifecycleOwner)
        advanceUntilIdle()
        scheduler.onStart(FakeLifecycleOwner)
        advanceUntilIdle()

        assertEquals(1, workScheduler.mediaStoreCount)
        assertEquals(listOf(SourceType.VOICE), workScheduler.enqueuedSources)
    }

    @Test
    fun `pre-auth catch-up paths do not enqueue any workers`() = runTest {
        val userPrefsStore = UserPrefsStoreImpl(
            dataStore = LocalIntegrationSupport.prefsDataStore("foreground-preauth"),
        )
        val workScheduler = FakeForegroundWorkScheduler()
        val scheduler = ForegroundCatchUpScheduler(
            scope = this,
            workScheduler = workScheduler,
            userPrefsStore = userPrefsStore,
            runtimeSyncSourceResolver = FakeRuntimeSyncSourceResolver(userPrefsStore),
            processingPauseGate = ProcessingPauseGate(userPrefsStore, RecordingLogger(), backgroundScope),
            logger = RecordingLogger(),
        )

        userPrefsStore.setSourceEnabled(SourceType.VOICE, true)
        userPrefsStore.setSourceEnabled(SourceType.GOOGLE_CALENDAR, true)

        scheduler.triggerCatchUp()
        scheduler.onStart(FakeLifecycleOwner)
        advanceUntilIdle()

        assertTrue(workScheduler.enqueuedSources.isEmpty())
        assertEquals(0, workScheduler.mediaStoreCount)
        assertEquals(0, workScheduler.gCalCount)
        assertEquals(0, workScheduler.outlookCalCount)
        assertEquals(0, workScheduler.imapNaverCount)
        assertEquals(0, workScheduler.imapDaumCount)
    }

    private object FakeLifecycleOwner : LifecycleOwner {
        override val lifecycle = androidx.lifecycle.LifecycleRegistry(this)
    }

    private class FakeRuntimeSyncSourceResolver(
        private val userPrefsStore: UserPrefsStoreImpl,
    ) : RuntimeSyncSourceResolver {
        override suspend fun foregroundSources(): Set<String> =
            userPrefsStore.observeEnabledSources().first()

        override suspend fun periodicSources(): Set<String> = emptySet()

        override suspend fun hasBackendMailSource(): Boolean = false
    }

    private class FakeForegroundWorkScheduler : ForegroundWorkScheduler {
        val enqueuedSources: MutableList<String> = mutableListOf()
        var mediaStoreCount: Int = 0
        var imapNaverCount: Int = 0
        var imapDaumCount: Int = 0
        var gCalCount: Int = 0
        var outlookCalCount: Int = 0

        override fun enqueueMediaStoreOneShotNow(lookbackDays: Int?) {
            mediaStoreCount += 1
            enqueuedSources += SourceType.VOICE
        }

        override fun enqueueImapNaverOneShotNow(lookbackDays: Int?) {
            imapNaverCount += 1
            enqueuedSources += SourceType.NAVER_IMAP
        }

        override fun enqueueImapDaumOneShotNow(lookbackDays: Int?) {
            imapDaumCount += 1
            enqueuedSources += SourceType.DAUM_IMAP
        }

        override fun enqueueGCalOneShotNow(lookbackDays: Int?) {
            gCalCount += 1
            enqueuedSources += SourceType.GOOGLE_CALENDAR
        }

        override fun enqueueOutlookCalOneShotNow(lookbackDays: Int?) {
            outlookCalCount += 1
            enqueuedSources += SourceType.OUTLOOK_CALENDAR
        }
    }
}
