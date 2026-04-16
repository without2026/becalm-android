package com.becalm.android.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.becalm.android.core.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers content observers for SMS/MMS and call-log URIs at application process start.
 *
 * ## ING-011 — 100% arrival guarantee
 * Content observers fire inside the app process even when the app is in the background.
 * Each `onChange` triggers an expedited one-shot [WorkScheduler] enqueue so that new
 * rows are captured before the user ever reaches TodayScreen.
 *
 * ## Registration lifecycle
 * Call [start] from `BeCalmApp.onCreate` (SP-69). Call [stop] during sign-out to
 * unregister all observers and prevent ghost callbacks against a stale session.
 *
 * ## Idempotency
 * [start] tracks internal registration state; repeated calls without an intervening
 * [stop] are no-ops.
 *
 * ## Permissions
 * If [Manifest.permission.READ_SMS] or [Manifest.permission.READ_CALL_LOG] are not
 * granted at call time, the corresponding observer is skipped and a DEBUG message is
 * logged. Registration is retried on the next [start] invocation (e.g. after the
 * onboarding flow grants the permission via SP-53).
 *
 * ## PII
 * No SMS content, phone numbers, or addresses are read or logged in this class.
 * The changed [Uri] values are only used to confirm they originate from the expected
 * content authority before triggering the worker.
 *
 * @param context Application context, used to obtain the [android.content.ContentResolver].
 * @param workScheduler SP-32 scheduler used to enqueue one-shot sync work on change.
 * @param logger Structured log sink.
 */
@Singleton
public class ContentObserverBootstrap @Inject constructor(
    @ApplicationContext private val context: Context,
    // WorkScheduler is owned by SP-32. ForegroundWorkScheduler (declared in
    // ForegroundCatchUpScheduler.kt) extends WorkSchedulerCompat; both components share
    // one Hilt binding so SP-32 only needs to provide a single implementation.
    private val workScheduler: ForegroundWorkScheduler,
    private val logger: Logger,
) {

    // ── State ─────────────────────────────────────────────────────────────────

    private var smsObserver: SmsInboxObserver? = null
    private var callLogObserver: CallLogObserver? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Registers SMS and call-log content observers against the application
     * [android.content.ContentResolver].
     *
     * Each observer is only registered when the corresponding permission is held.
     * Already-registered observers are not re-registered (idempotent).
     */
    public fun start() {
        registerSmsObserver()
        registerCallLogObserver()
    }

    /**
     * Unregisters all content observers registered by this bootstrap.
     *
     * Safe to call even when no observers are currently registered.
     */
    public fun stop() {
        smsObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            logger.d(TAG, "SMS observer unregistered")
            smsObserver = null
        }
        callLogObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            logger.d(TAG, "call-log observer unregistered")
            callLogObserver = null
        }
    }

    // ── Registration helpers ──────────────────────────────────────────────────

    private fun registerSmsObserver() {
        if (smsObserver != null) {
            logger.d(TAG, "SMS observer already registered — skipping")
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS,
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            logger.d(TAG, "READ_SMS not granted — skipping SMS observer registration")
            return
        }

        val observer = SmsInboxObserver()
        context.contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            /* notifyForDescendants = */ true,
            observer,
        )
        smsObserver = observer
        logger.d(TAG, "SMS observer registered on ${Telephony.Sms.CONTENT_URI}")
    }

    private fun registerCallLogObserver() {
        if (callLogObserver != null) {
            logger.d(TAG, "call-log observer already registered — skipping")
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG,
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            logger.d(TAG, "READ_CALL_LOG not granted — skipping call-log observer registration")
            return
        }

        val observer = CallLogObserver()
        context.contentResolver.registerContentObserver(
            CallLog.Calls.CONTENT_URI,
            /* notifyForDescendants = */ true,
            observer,
        )
        callLogObserver = observer
        logger.d(TAG, "call-log observer registered on ${CallLog.Calls.CONTENT_URI}")
    }

    // ── Inner observers ───────────────────────────────────────────────────────

    /**
     * Watches `Telephony.Sms.CONTENT_URI` for new inbox/sent rows.
     *
     * On any non-self change, enqueues a one-shot [com.becalm.android.worker.ingestion.MediaStoreWorker]
     * via [workScheduler]. WorkManager deduplicates concurrent enqueues, so SMS bursts
     * do not spawn parallel worker instances.
     */
    private inner class SmsInboxObserver : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (selfChange) return

            val isSmsUri = uri == null ||
                uri.toString().startsWith(Telephony.Sms.CONTENT_URI.toString())
            if (!isSmsUri) {
                logger.d(TAG, "SmsInboxObserver: onChange skipped — non-SMS URI")
                return
            }

            logger.d(TAG, "SmsInboxObserver: onChange — enqueueing MediaStoreWorker one-shot")
            workScheduler.enqueueMediaStoreOneShotNow()
        }
    }

    /**
     * Watches `CallLog.Calls.CONTENT_URI` for new call-log entries.
     *
     * On any non-self change, enqueues a one-shot [com.becalm.android.worker.ingestion.MediaStoreWorker]
     * via [workScheduler]. The same deduplication guarantee applies.
     */
    private inner class CallLogObserver : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (selfChange) return

            val isCallLogUri = uri == null ||
                uri.toString().startsWith(CallLog.Calls.CONTENT_URI.toString())
            if (!isCallLogUri) {
                logger.d(TAG, "CallLogObserver: onChange skipped — non-call-log URI")
                return
            }

            logger.d(TAG, "CallLogObserver: onChange — enqueueing MediaStoreWorker one-shot")
            workScheduler.enqueueMediaStoreOneShotNow()
        }
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    private companion object {
        private const val TAG = "ContentObserverBootstrap"
    }
}
