package com.becalm.android.worker

internal class SourceGraphChangedNotifier(
    private val workScheduler: WorkScheduler,
) {
    fun notifyChanged(initialDelaySeconds: Long? = null) {
        if (initialDelaySeconds == null) {
            workScheduler.enqueuePersonInteractionIndex()
        } else {
            workScheduler.enqueuePersonInteractionIndex(initialDelaySeconds = initialDelaySeconds)
        }
    }
}
