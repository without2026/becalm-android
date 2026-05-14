package com.becalm.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.becalm.android.receiver.ReminderBroadcastReceiver
import com.becalm.android.productanalytics.ProductAnalyticsFlushScheduler
import com.becalm.android.productanalytics.ProductSessionTracker
import com.becalm.android.worker.MatchingRequiredNotifier
import com.becalm.android.worker.VoiceFailureNotifier
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import timber.log.Timber

/**
 * Application class for BeCalm Android.
 *
 * Implements [Configuration.Provider] so that WorkManager uses [HiltWorkerFactory],
 * which is required because multiple workers are annotated with [@HiltWorker][androidx.hilt.work.HiltWorker].
 * Default WorkManager auto-initialization is disabled in AndroidManifest.xml.
 *
 * Plants the Timber tree in debug builds only. In release builds nothing is planted so
 * [com.becalm.android.core.util.TimberLogger] calls become no-ops — PIPA Article 29
 * (no secondary exposure via logcat).
 */
@HiltAndroidApp
public class BecalmApplication : Application(), Configuration.Provider {

    @Inject
    public lateinit var workerFactory: HiltWorkerFactory

    @Inject
    public lateinit var productSessionTracker: ProductSessionTracker

    @Inject
    public lateinit var productAnalyticsFlushScheduler: ProductAnalyticsFlushScheduler

    private val workExecutor by lazy {
        val threadIndex = AtomicInteger(1)
        Executors.newFixedThreadPool(WORK_MANAGER_THREAD_COUNT) { runnable ->
            Thread(runnable, "becalm-wm-${threadIndex.getAndIncrement()}").apply {
                isDaemon = false
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setExecutor(workExecutor)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            installDebugStrictMode()
        }

        // CMT-008: register the commitment_due_soon high-importance notification
        // channel at process start. createNotificationChannel is idempotent on API 26+,
        // so repeat cold starts are safe no-ops. ReminderBroadcastReceiver posts to
        // this channel after re-querying Room for the commitment's live state.
        registerCommitmentDueSoonChannel()
        VoiceFailureNotifier.ensureChannel(this)
        MatchingRequiredNotifier.ensureChannel(this)
        productSessionTracker.register(this)
        productAnalyticsFlushScheduler.schedulePeriodicFlush()
    }

    private fun installDebugStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .build(),
        )
    }

    /**
     * Registers the `commitment_due_soon` notification channel (CMT-008) with
     * `IMPORTANCE_HIGH`. The channel description and name are localized via
     * string resources so the system notification-settings screen can display
     * them in the user's locale.
     *
     * Idempotent on API 26+ — repeat calls from subsequent cold starts are no-ops.
     */
    private fun registerCommitmentDueSoonChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(ReminderBroadcastReceiver.CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            ReminderBroadcastReceiver.CHANNEL_ID,
            getString(R.string.commitment_channel_due_soon_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.commitment_channel_due_soon_desc)
        }
        manager.createNotificationChannel(channel)
    }

}

private const val WORK_MANAGER_THREAD_COUNT = 2
