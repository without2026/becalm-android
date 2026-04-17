package com.becalm.android.worker

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.worker.ingestion.WorkSchedulerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// ── ForegroundWorkScheduler ───────────────────────────────────────────────────
//
// Extends [WorkSchedulerCompat] (SP-22/SP-32 narrow interface) with the per-source
// one-shot enqueue methods required by [ForegroundCatchUpScheduler].
//
// SP-32 (WorkScheduler owner) must implement this interface (or expose a thin adapter)
// so that the Hilt binding satisfies both [WorkSchedulerCompat] and this interface.
//
// If SP-32 cannot implement this interface directly, provide an adapter:
//
//   class ForegroundWorkSchedulerAdapter(
//       private val delegate: WorkScheduler,
//   ) : ForegroundWorkScheduler {
//       override fun enqueueMediaStoreOneShotNow() = delegate.enqueueOneShot(MediaStoreWorker::class)
//       override fun enqueueGmailOneShotNow()      = delegate.enqueueOneShot(GmailWorker::class)
//       // …
//   }

/**
 * Scheduler capability subset required by [ForegroundCatchUpScheduler].
 *
 * Extends [WorkSchedulerCompat] so that a single SP-32 binding satisfies both
 * [ContentObserverBootstrap] (which uses [WorkSchedulerCompat]) and this scheduler.
 */
public interface ForegroundWorkScheduler : WorkSchedulerCompat {

    /** Enqueues an expedited one-shot GmailWorker. */
    public fun enqueueGmailOneShotNow()

    /** Enqueues an expedited one-shot ImapNaverWorker. */
    public fun enqueueImapNaverOneShotNow()

    /** Enqueues an expedited one-shot ImapDaumWorker. */
    public fun enqueueImapDaumOneShotNow()

    /** Enqueues an expedited one-shot OutlookMailWorker. */
    public fun enqueueOutlookMailOneShotNow()

    /** Enqueues an expedited one-shot GCalWorker. */
    public fun enqueueGCalOneShotNow()

    /** Enqueues an expedited one-shot OutlookCalWorker. */
    public fun enqueueOutlookCalOneShotNow()
}

/**
 * Enqueues expedited catch-up sync work for every enabled source whenever the app
 * returns to the foreground.
 *
 * ## ING-011 — 100% arrival guarantee (foreground path)
 * When the app was backgrounded or killed, periodic workers may not have fired in time.
 * This scheduler fires on `onStart` (i.e. each foreground entry) so that any rows that
 * arrived while the app was absent are captured before TodayScreen is visible.
 *
 * ## Lifecycle registration
 * Call [start] from `BeCalmApp.onCreate` (SP-69). Internally this adds [this] as a
 * [DefaultLifecycleObserver] on [ProcessLifecycleOwner], which survives configuration
 * changes.
 *
 * ## Source fan-out
 * The set of enabled sources is read from [UserPrefsStore.observeEnabledSources] and
 * mapped to the corresponding worker via [WorkScheduler.enqueueExpedited]. Each
 * source maps to exactly one worker class:
 *
 * | Source type     | Worker class            |
 * |-----------------|-------------------------|
 * | `gmail`         | `GmailWorker`           |
 * | `naver_imap`    | `ImapNaverWorker`       |
 * | `daum_imap`     | `ImapDaumWorker`        |
 * | `outlook_mail`  | `OutlookMailWorker`     |
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
    private val logger: Logger,
) : DefaultLifecycleObserver {

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Registers this scheduler as a [ProcessLifecycleOwner] observer.
     *
     * Safe to call more than once; [ProcessLifecycleOwner] deduplicates observer
     * registrations by reference. Call from `BeCalmApp.onCreate`.
     */
    public fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        logger.d(TAG, "registered on ProcessLifecycleOwner")
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
        scope.launch {
            try {
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
     * Maps each [sourceType] string to an expedited enqueue call on [workScheduler].
     *
     * Unknown source types are skipped with a WARN log rather than throwing, so that
     * a future source type added to [SourceType] but not yet handled here does not
     * crash the scheduler.
     */
    private fun enqueueForSources(sources: Set<String>) {
        for (sourceType in sources) {
            when (sourceType) {
                SourceType.GMAIL -> {
                    logger.d(TAG, "enqueueing GmailWorker catch-up")
                    workScheduler.enqueueGmailOneShotNow()
                }
                SourceType.NAVER_IMAP -> {
                    logger.d(TAG, "enqueueing ImapNaverWorker catch-up")
                    workScheduler.enqueueImapNaverOneShotNow()
                }
                SourceType.DAUM_IMAP -> {
                    logger.d(TAG, "enqueueing ImapDaumWorker catch-up")
                    workScheduler.enqueueImapDaumOneShotNow()
                }
                SourceType.OUTLOOK_MAIL -> {
                    logger.d(TAG, "enqueueing OutlookMailWorker catch-up")
                    workScheduler.enqueueOutlookMailOneShotNow()
                }
                SourceType.GOOGLE_CALENDAR -> {
                    logger.d(TAG, "enqueueing GCalWorker catch-up")
                    workScheduler.enqueueGCalOneShotNow()
                }
                SourceType.OUTLOOK_CALENDAR -> {
                    logger.d(TAG, "enqueueing OutlookCalWorker catch-up")
                    workScheduler.enqueueOutlookCalOneShotNow()
                }
                SourceType.VOICE -> {
                    logger.d(TAG, "enqueueing MediaStoreWorker catch-up (voice)")
                    workScheduler.enqueueMediaStoreOneShotNow()
                }
                else -> {
                    logger.w(TAG, "onStart: unknown source type='$sourceType' — skipped")
                }
            }
        }
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    private companion object {
        private const val TAG = "ForegroundCatchUpScheduler"
    }
}
