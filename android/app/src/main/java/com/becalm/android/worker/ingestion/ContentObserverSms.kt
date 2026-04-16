package com.becalm.android.worker.ingestion

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import com.becalm.android.core.util.Logger

// ── WorkScheduler stub ────────────────────────────────────────────────────────
// SP-32 owns the WorkScheduler interface. The symbol is imported here as a type
// reference; its full definition is provided by SP-32's module binding at runtime.
// import com.becalm.android.worker.WorkScheduler

/**
 * [ContentObserver] that watches `Telephony.Sms.CONTENT_URI` and triggers a one-shot
 * [MediaStoreWorker] run whenever the SMS content provider emits a change notification.
 *
 * ## Registration
 * Registered by SP-28 (ContentObserverBootstrap) via [register]. The observer is attached
 * to the application [Context] so it outlives any individual Activity or Fragment.
 *
 * ## Triggering
 * [onChange] delegates to [WorkScheduler.enqueueOneShot] (SP-32 interface), passing
 * [MediaStoreWorker]'s class token. WorkManager deduplicates concurrent one-shot
 * enqueues, so rapid SMS bursts do not spawn parallel worker instances.
 *
 * ## PII
 * Neither the URI nor any content is logged at a level that exposes raw SMS data.
 * The changed [Uri] parameter is only used to confirm it is SMS-derived; it is not
 * stored, transmitted, or logged with identifying detail.
 *
 * @param context Application context. Must outlive the observer's registration lifetime.
 * @param workScheduler SP-32 WorkScheduler instance used to enqueue the one-shot worker.
 * @param logger Structured log sink.
 */
public class ContentObserverSms(
    private val context: Context,
    // WorkScheduler is owned by SP-32; injected by ContentObserverBootstrap (SP-28).
    // Declared as Any here to avoid a compile-time dependency on the SP-32 module.
    // SP-28 must cast to WorkScheduler before passing it to this constructor.
    // See comment-stub interface below.
    private val workScheduler: WorkSchedulerCompat,
    private val logger: Logger,
) : ContentObserver(Handler(Looper.getMainLooper())) {

    /**
     * Called by the OS when any row in `Telephony.Sms.CONTENT_URI` changes.
     *
     * The changed [uri] is a courtesy signal; we do not use it to filter rows because
     * [MediaStoreWorker] re-queries with its own watermark cursor. We only verify the
     * URI namespace to avoid spurious triggers from unrelated observers sharing the same
     * handler.
     */
    public override fun onChange(selfChange: Boolean, uri: Uri?) {
        val isSmsUri = uri == null ||
            uri.toString().startsWith(Telephony.Sms.CONTENT_URI.toString())

        if (!isSmsUri) {
            logger.d(TAG, "onChange skipped — non-SMS URI")
            return
        }

        logger.d(TAG, "onChange selfChange=$selfChange — enqueueing MediaStoreWorker one-shot")
        workScheduler.enqueueMediaStoreOneShotNow()
    }

    /**
     * Registers this observer against the application [ContentResolver].
     *
     * Call from SP-28 (ContentObserverBootstrap) during application startup, after
     * the WorkManager graph is initialised.
     */
    public fun register() {
        context.contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            /* notifyForDescendants = */ true,
            this,
        )
        logger.d(TAG, "registered on ${Telephony.Sms.CONTENT_URI}")
    }

    /**
     * Unregisters this observer. Call from ContentObserverBootstrap when the application
     * is terminating or the user signs out, to prevent ghost callbacks.
     */
    public fun unregister() {
        context.contentResolver.unregisterContentObserver(this)
        logger.d(TAG, "unregistered")
    }

    private companion object {
        private const val TAG = "ContentObserverSms"
    }
}

// ── WorkSchedulerCompat — comment-stub interface ──────────────────────────────
//
// SP-32 owns:
//   package com.becalm.android.worker
//   interface WorkScheduler {
//       /** Enqueues a one-shot MediaStoreWorker with EXPEDITED priority. */
//       fun enqueueMediaStoreOneShotNow()
//
//       /** Schedules the periodic MediaStoreWorker (periodic interval from config). */
//       fun scheduleMediaStorePeriodic()
//   }
//
// ContentObserverSms depends on this narrow capability. Rather than coupling directly to
// SP-32's full WorkScheduler interface (not yet compiled in this parallel SP pass),
// we declare a minimal local alias [WorkSchedulerCompat] that SP-28 (ContentObserverBootstrap)
// satisfies by implementing a thin adapter wrapping SP-32's WorkScheduler at injection time.
//
// SP-28 bootstrap adapter example:
//   class WorkSchedulerCompatAdapter(private val delegate: WorkScheduler) : WorkSchedulerCompat {
//       override fun enqueueMediaStoreOneShotNow() = delegate.enqueueMediaStoreOneShotNow()
//   }

/**
 * Minimal scheduler capability required by [ContentObserverSms].
 *
 * SP-28 (ContentObserverBootstrap) provides the concrete implementation backed by
 * SP-32's [com.becalm.android.worker.WorkScheduler]. Using this narrow interface keeps
 * [ContentObserverSms] decoupled from the full WorkScheduler API surface.
 */
public interface WorkSchedulerCompat {
    /**
     * Enqueues a one-shot [MediaStoreWorker] with EXPEDITED priority.
     *
     * WorkManager deduplicates concurrent enqueues; calling this method multiple times
     * before the worker starts is safe.
     */
    public fun enqueueMediaStoreOneShotNow()
}
