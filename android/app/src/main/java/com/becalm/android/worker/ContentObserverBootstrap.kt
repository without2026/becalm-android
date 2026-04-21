package com.becalm.android.worker

import android.content.Context
import com.becalm.android.core.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers content observers for ingestion URIs at application process start.
 *
 * Currently a no-op shell: SMS/call-log observers were removed as dead code
 * (spec forbids SMS/CallLog access — `.spec/person-enrichment.spec.yml:87`,
 * `.spec/contracts/data-model.yml:28-32`). A voice SAF tree URI observer will
 * land in `refactor/worker/voice/ingestion-realign`. The class is retained
 * because [com.becalm.android.data.repository.AuthRepository] wires [start] /
 * [stop] into the sign-in / sign-out lifecycle.
 *
 * @param context Application context, used to obtain the [android.content.ContentResolver]
 *                once a real observer is re-introduced.
 * @param workScheduler Scheduler used to enqueue one-shot sync work on change.
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

    /**
     * No-op until the voice SAF observer is wired in `refactor/worker/voice/ingestion-realign`.
     * Safe to call repeatedly.
     */
    public fun start() {
        logger.d(TAG, "start() no-op — voice SAF observer pending refactor/worker/voice/ingestion-realign")
    }

    /**
     * No-op until the voice SAF observer is wired. Safe to call even when no
     * observers are currently registered.
     */
    public fun stop() {
        // No observers to unregister while the class is a shell.
    }

    private companion object {
        private const val TAG = "ContentObserverBootstrap"
    }
}
