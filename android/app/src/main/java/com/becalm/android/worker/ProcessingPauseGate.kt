package com.becalm.android.worker

import com.becalm.android.core.di.ApplicationScope
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
public class ProcessingPauseGate @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    private val logger: Logger,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    @Volatile
    private var pausedSnapshot: Boolean = false

    init {
        applicationScope.launch {
            userPrefsStore.observeProcessingPaused().collect { paused ->
                pausedSnapshot = paused
            }
        }
    }

    public suspend fun isPaused(): Boolean =
        userPrefsStore.observeProcessingPaused().first()

    public fun isPausedBlocking(): Boolean =
        pausedSnapshot

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
