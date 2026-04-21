package com.becalm.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.becalm.android.worker.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
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

    /**
     * One-release upgrade compat shim for the #13 MediaStore unique-work rename.
     *
     * TODO(wave-N+2): Remove alongside [com.becalm.android.worker.UniqueWorkKeys.LEGACY_MEDIA_STORE_KEY]
     * and [WorkScheduler.cleanupLegacyWorkNames] once the pre-#13 install base has drained.
     */
    @Inject
    public lateinit var workScheduler: WorkScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // Fire-and-forget upgrade compat: cancel WorkManager unique-work under the pre-#13
        // `ingest.sms_call` name so devices upgrading from that build don't run duplicate
        // MediaStore scans alongside the new `ingest.media_store` key. Idempotent — cancelling
        // a non-existent unique-work name is a no-op on subsequent cold starts.
        workScheduler.cleanupLegacyWorkNames()
    }
}
