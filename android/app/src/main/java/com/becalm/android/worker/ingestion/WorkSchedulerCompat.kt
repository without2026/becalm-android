package com.becalm.android.worker.ingestion

/**
 * Minimal scheduler capability required by SMS/content-observer triggers.
 *
 * SP-28 (ContentObserverBootstrap) provides the concrete implementation backed by
 * SP-32's [com.becalm.android.worker.WorkScheduler]. Using this narrow interface keeps
 * content-observer components decoupled from the full WorkScheduler API surface.
 */
public interface WorkSchedulerCompat {
    /**
     * Enqueues a one-shot [MediaStoreWorker] with EXPEDITED priority.
     *
     * WorkManager deduplicates concurrent enqueues; calling this method multiple times
     * before the worker starts is safe.
     */
    public fun enqueueMediaStoreOneShotNow(lookbackDays: Int? = null)
}
