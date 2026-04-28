package com.becalm.android.worker

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
 * This scheduler fires on `onStart` (i.e. each foreground entry) so that any rows that
 * arrived while the app was absent are captured. Automatic lifecycle catch-up waits briefly
 * so it does not compete with the first rendered frame; user-triggered pull-to-refresh remains
 * immediate.
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
 * @param scope Application-scoped coroutine scope. Provided by AppModule with
 *   `@ApplicationScope` qualifier. If the qualifier binding is absent, leave a
 *   TODO and inject a plain `CoroutineScope` until AppModule is updated.
 * @param workScheduler SP-32 scheduler. Treat as declared; do not define here.
 * @param userPrefsStore Preference store used to read enabled sources.
 * @param logger Structured log sink.
 */
@Singleton
public class ForegroundCatchUpScheduler @Inject constructor(
    // TODO(AppModule): ensure AppModule provides @ApplicationScope CoroutineScope.
    // If the @ApplicationScope qualifier is not yet wired, a plain CoroutineScope
    // binding will be substituted by Hilt until AppModule is updated.
    private val scope: CoroutineScope,
    // WorkScheduler is owned by SP-32. ForegroundWorkScheduler (declared above) extends
    // WorkSchedulerCompat with per-source expedited enqueue methods. SP-32's binding must
    // expose a ForegroundWorkScheduler implementation (or adapter) to satisfy this injection.
    private val workScheduler: ForegroundWorkScheduler,
    private val userPrefsStore: UserPrefsStore,
    private val processingPauseGate: ProcessingPauseGate,
    private val logger: Logger,
) : DefaultLifecycleObserver {

    private var onStartCatchUpJob: Job? = null

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
                val enabledSources: Set<String> = userPrefsStore.observeEnabledSources().first()

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
     * Fires each time the app process moves from background to foreground.
     *
     * Launches a coroutine that reads the current enabled-source set and enqueues an
     * expedited one-shot worker for each. WorkManager deduplicates concurrent enqueues
     * per unique work name, so rapid foreground transitions are safe.
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
                val enabledSources: Set<String> = userPrefsStore.observeEnabledSources().first()

                if (enabledSources.isEmpty()) {
                    logger.d(TAG, "onStart: no enabled sources — skipping catch-up enqueue")
                    return@launch
                }

                logger.d(TAG, "onStart: enqueueing catch-up for sources=$enabledSources")
                enqueueForSources(enabledSources)
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
