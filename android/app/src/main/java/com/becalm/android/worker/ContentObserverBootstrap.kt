package com.becalm.android.worker

import android.database.ContentObserver
import android.net.Uri
import android.provider.MediaStore
import android.content.Context
import com.becalm.android.core.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers content observers for ingestion URIs at application process start.
 *
 * Watches [MediaStore.Audio.Media.EXTERNAL_CONTENT_URI] plus the broad external files
 * table and nudges a one-shot
 * [com.becalm.android.worker.ingestion.MediaStoreWorker] whenever the media table changes.
 *
 * The worker remains the correctness owner for folder filtering, deduplication, and
 * permission / PIPA gating. The observer is intentionally broad: any media/files mutation
 * results in a catch-up enqueue, and the worker decides whether there is new work.
 *
 * @param context Application context, used to obtain the [android.content.ContentResolver]
 *                and register/unregister the observer.
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
    private val processingPauseGate: ProcessingPauseGate,
    private val logger: Logger,
) {
    private var audioObserver: ContentObserver? = null
    private var filesObserver: ContentObserver? = null

    /**
     * Registers a broad MediaStore audio observer. Safe to call repeatedly.
     */
    public fun start() {
        if (audioObserver != null || filesObserver != null) {
            logger.d(TAG, "start() ignored — observer already registered")
            return
        }

        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                handleObservedMediaStoreChange(null)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                handleObservedMediaStoreChange(uri)
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
        audioObserver = observer
        context.contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri("external"),
            true,
            observer,
        )
        filesObserver = observer
        logger.d(TAG, "registered MediaStore audio/files observer")
    }

    /**
     * Unregisters the observer if present. Safe to call even when nothing is registered.
     */
    public fun stop() {
        val observer = audioObserver ?: filesObserver ?: return
        context.contentResolver.unregisterContentObserver(observer)
        audioObserver = null
        filesObserver = null
        logger.d(TAG, "unregistered MediaStore audio/files observer")
    }

    /**
     * Test-visible seam for a MediaStore change callback. The worker owns folder-level
     * filtering, so every observed change simply schedules an expedited catch-up scan.
     */
    public fun handleObservedMediaStoreChange(uri: Uri?) {
        if (processingPauseGate.isPausedBlocking()) {
            logger.d(TAG, "MediaStore audio changed uri=$uri — ignored while processing is paused")
            return
        }
        logger.d(TAG, "MediaStore audio changed uri=$uri — enqueueing catch-up")
        workScheduler.enqueueMediaStoreOneShotNow()
    }

    private companion object {
        private const val TAG = "ContentObserverBootstrap"
    }
}
