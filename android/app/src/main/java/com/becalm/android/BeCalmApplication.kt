package com.becalm.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.becalm.android.notifications.NotificationChannelManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

// spec: ING-002, ING-006..ING-011 — Hilt-injected WorkManager initialization
// spec: CMT-008 — notification channels initialized on app start

@HiltAndroidApp
class BeCalmApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // spec: CMT-008 — ensure notification channels exist before any alarm fires
        NotificationChannelManager.ensureChannels(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
