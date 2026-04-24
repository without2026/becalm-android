package com.becalm.android.worker

import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@Singleton
public class ProcessingPauseGate @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    private val logger: Logger,
) {
    public suspend fun isPaused(): Boolean =
        userPrefsStore.observeProcessingPaused().first()

    public fun isPausedBlocking(): Boolean =
        runBlocking { isPaused() }

    public suspend fun shouldSkip(owner: String): Boolean {
        val paused = isPaused()
        if (paused) {
            logger.d(TAG, "$owner skipped because processing is paused")
        }
        return paused
    }

    private companion object {
        private const val TAG: String = "ProcessingPauseGate"
    }
}
