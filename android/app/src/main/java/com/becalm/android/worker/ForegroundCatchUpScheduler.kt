package com.becalm.android.worker

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.becalm.android.core.di.ApplicationScope
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.worker.ingestion.WorkSchedulerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scheduler capability subset required by [ForegroundCatchUpScheduler].
 *
 * Extends [WorkSchedulerCompat] so that a single SP-32 binding satisfies both
 * [ContentObserverBootstrap] (which uses [WorkSchedulerCompat]) and this scheduler.
 */
public interface ForegroundWorkScheduler : WorkSchedulerCompat {

    /** Enqueues an expedited one-shot ImapNaverWorker. */
    public fun enqueueImapNaverOneShotNow(lookbackDays: Int? = null)

    /** Enqueues an expedited one-shot ImapDaumWorker. */
    public fun enqueueImapDaumOneShotNow(lookbackDays: Int? = null)

    /** Enqueues an expedited one-shot GCalWorker. */
    public fun enqueueGCalOneShotNow(lookbackDays: Int? = null)

    /** Enqueues an expedited one-shot OutlookCalWorker. */
    public fun enqueueOutlookCalOneShotNow(lookbackDays: Int? = null)
}

/**
 * Enqueues expedited catch-up sync work for every enabled source whenever the app
 * returns to the foreground.
 *
 * ## ING-011 — 100% arrival guarantee (foreground path)
 * When the app was backgrounded or killed, periodic workers may not have fired in time.
 * This scheduler fires after runtime startup so that any rows missed before process
 * observers are registered are captured. Automatic lifecycle catch-up waits briefly and
 * runs at most once per source per process; realtime observers and periodic workers own
 * ongoing arrivals. User-triggered pull-to-refresh remains immediate.
 *
 * ## Lifecycle registration
 * Call [start] only after an authenticated session is mirrored into
 * [UserPrefsStore.observeCurrentUserId]. Signed-in cold starts do this from
 * the authenticated runtime bootstrap. Internally this adds [this] as a
 * [DefaultLifecycleObserver] on [ProcessLifecycleOwner], which survives configuration changes.
 *
 * ## Source fan-out
 * The set of enabled sources is read from [UserPrefsStore.observeEnabledSources] and
 * mapped to the corresponding worker via [WorkScheduler.enqueueExpedited]. Each
 * source maps to exactly one worker class:
 *
 * | Source type     | Worker class            |
 * |-----------------|-------------------------|
 * | `naver_imap`    | `ImapNaverWorker`       |
 * | `daum_imap`     | `ImapDaumWorker`        |
 * | `google_calendar` | `GCalWorker`          |
 * | `outlook_calendar` | `OutlookCalWorker`   |
 * | `voice`         | `MediaStoreWorker`      |
 *
 * Unknown source-type strings are skipped with a WARN log.
 *
 * ## Coroutine scope
 * Runs on the application-scoped [CoroutineScope] provided by AppModule
 * (`@ApplicationScope`). Exceptions in a single enqueue do not cancel the scope.
 *
 * @param scope Application-scoped coroutine scope provided by AppModule with
 *   the `@ApplicationScope` qualifier.
 * @param workScheduler SP-32 scheduler. Treat as declared; do not define here.
 * @param userPrefsStore Preference store used to read enabled sources.
 * @param logger Structured log sink.
 */
@Singleton
public class ForegroundCatchUpScheduler @Inject constructor(
    @ApplicationScope
    private val scope: CoroutineScope,
    // WorkScheduler is owned by SP-32. ForegroundWorkScheduler (declared above) extends
    // WorkSchedulerCompat with per-source expedited enqueue methods. SP-32's binding must
    // expose a ForegroundWorkScheduler implementation (or adapter) to satisfy this injection.
    private val workScheduler: ForegroundWorkScheduler,
    private val userPrefsStore: UserPrefsStore,
    private val runtimeSyncSourceResolver: RuntimeSyncSourceResolver,
    private val processingPauseGate: ProcessingPauseGate,
    private val logger: Logger,
) : DefaultLifecycleObserver {

    private var onStartCatchUpJob: Job? = null
    private val automaticCatchUpEnqueuedSources: MutableSet<String> = linkedSetOf()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Registers this scheduler as a [ProcessLifecycleOwner] observer.
     *
     * Safe to call more than once; [ProcessLifecycleOwner] deduplicates observer
     * registrations by reference. Must only be called after auth activation.
     */
    public fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        logger.d(TAG, "registered on ProcessLifecycleOwner")
    }

    /**
     * Triggers foreground catch-up fan-out for every enabled source without waiting for
     * the next lifecycle `onStart` event.
     *
     * Invoked by [com.becalm.android.ui.today.TodayViewModel.onPullRefresh] when the user
     * pulls to refresh the timeline (TDY-009). Reuses the same per-source dispatch table as
     * [onStart], so there is exactly one code path that maps source types to expedited
     * workers.
     */
    public fun triggerCatchUp() {
        scope.launch {
            try {
                if (!hasSignedInUser()) {
                    logger.d(TAG, "triggerCatchUp: no authenticated user — skipping")
                    return@launch
                }
                if (processingPauseGate.isPaused()) {
                    logger.d(TAG, "triggerCatchUp: processing paused — skipping")
                    return@launch
                }
                val enabledSources: Set<String> = runtimeSyncSourceResolver.foregroundSources()

                if (enabledSources.isEmpty()) {
                    logger.d(TAG, "triggerCatchUp: no enabled sources — skipping")
                    return@launch
                }

                logger.d(TAG, "triggerCatchUp: enqueueing for sources=$enabledSources")
                enqueueForSources(enabledSources)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.e(TAG, "triggerCatchUp: failed to enqueue catch-up work", e)
            }
        }
    }

    // ── DefaultLifecycleObserver ──────────────────────────────────────────────

    /**
     * Fires when the app process enters foreground after this observer is registered.
     *
     * Launches a coroutine that reads the current enabled-source set and enqueues an
     * expedited one-shot worker for each source that has not already had an automatic
     * catch-up in this process. This avoids waking WorkManager's DB on every resume; missed
     * rows after startup are handled by realtime observers, periodic workers, or explicit
     * pull-to-refresh.
     */
    override fun onStart(owner: LifecycleOwner) {
        if (onStartCatchUpJob?.isActive == true) {
            logger.d(TAG, "onStart: catch-up already pending — skipping duplicate enqueue")
            return
        }
        onStartCatchUpJob = scope.launch {
            try {
                delay(ON_START_CATCH_UP_DELAY_MS)
                if (!hasSignedInUser()) {
                    logger.d(TAG, "onStart: no authenticated user — skipping catch-up enqueue")
                    return@launch
                }
                if (processingPauseGate.isPaused()) {
                    logger.d(TAG, "onStart: processing paused — skipping catch-up enqueue")
                    return@launch
                }
                val enabledSources: Set<String> = runtimeSyncSourceResolver.foregroundSources()

                if (enabledSources.isEmpty()) {
                    logger.d(TAG, "onStart: no enabled sources — skipping catch-up enqueue")
                    return@launch
                }

                val pendingSources = enabledSources - automaticCatchUpEnqueuedSources
                if (pendingSources.isEmpty()) {
                    logger.d(TAG, "onStart: catch-up already enqueued for sources=$enabledSources — skipping")
                    return@launch
                }

                automaticCatchUpEnqueuedSources += pendingSources
                logger.d(TAG, "onStart: enqueueing catch-up for sources=$pendingSources")
                enqueueForSources(pendingSources)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.e(TAG, "onStart: failed to enqueue catch-up work", e)
            }
        }
    }

    // ── Source fan-out ────────────────────────────────────────────────────────

    /**
     * Per-source dispatch table: source-type wire string ->
     *   (full debug log message, expedited enqueue lambda).
     *
     * The log message is stored as-is so the original byte-identical strings are
     * preserved (e.g. VOICE logs "enqueueing MediaStoreWorker catch-up (voice)"
     * without a trailing suffix).
     *
     * Iterated by [enqueueForSources]. Adding a new source type here is the only
     * change required to extend foreground catch-up fan-out.
     */
    private val dispatchTable: Map<String, Pair<String, () -> Unit>> = mapOf(
        SourceType.NAVER_IMAP to
            ("enqueueing ImapNaverWorker catch-up" to { workScheduler.enqueueImapNaverOneShotNow() }),
        SourceType.DAUM_IMAP to
            ("enqueueing ImapDaumWorker catch-up" to { workScheduler.enqueueImapDaumOneShotNow() }),
        SourceType.GOOGLE_CALENDAR to
            ("enqueueing GCalWorker catch-up" to { workScheduler.enqueueGCalOneShotNow() }),
        SourceType.OUTLOOK_CALENDAR to
            ("enqueueing OutlookCalWorker catch-up" to { workScheduler.enqueueOutlookCalOneShotNow() }),
        SourceType.VOICE to
            ("enqueueing MediaStoreWorker catch-up (voice)" to { workScheduler.enqueueMediaStoreOneShotNow() }),
    )

    /**
     * Maps each [sourceType] string to an expedited enqueue call on [workScheduler].
     *
     * Unknown source types are skipped with a WARN log rather than throwing, so that
     * a future source type added to [SourceType] but not yet handled here does not
     * crash the scheduler.
     */
    private fun enqueueForSources(sources: Set<String>) {
        for (sourceType in sources) {
            val entry = dispatchTable[sourceType]
            if (entry == null) {
                logger.w(TAG, "onStart: unknown source type='$sourceType' — skipped")
                continue
            }
            val (logMessage, enqueue) = entry
            try {
                logger.d(TAG, logMessage)
                enqueue()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.w(TAG, "onStart: failed to enqueue catch-up for source='$sourceType'", e)
            }
        }
    }

    private suspend fun hasSignedInUser(): Boolean =
        !userPrefsStore.observeCurrentUserId().first().isNullOrBlank()

    // ── Companion ─────────────────────────────────────────────────────────────

    private companion object {
        private const val TAG = "ForegroundCatchUpScheduler"
        private const val ON_START_CATCH_UP_DELAY_MS: Long = 8_000L
    }
}
