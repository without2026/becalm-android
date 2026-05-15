package com.becalm.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

public class DebugCrashlyticsSmokeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(ACTION_RECORD_NON_FATAL, ACTION_FORCE_CRASH)) return

        FirebaseApp.initializeApp(context)
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCrashlyticsCollectionEnabled(true)
        crashlytics.setCustomKey("debug_smoke_source", intent.action.orEmpty())
        crashlytics.log("Crashlytics debug smoke action=${intent.action}")

        when (intent.action) {
            ACTION_RECORD_NON_FATAL -> {
                crashlytics.recordException(
                    IllegalStateException("Crashlytics debug non-fatal smoke test"),
                )
                Timber.i("Crashlytics debug non-fatal smoke event recorded")
            }
            ACTION_FORCE_CRASH -> {
                throw IllegalStateException("Crashlytics debug forced crash smoke test")
            }
        }
    }

    public companion object {
        public const val ACTION_RECORD_NON_FATAL: String =
            "com.becalm.android.DEBUG_CRASHLYTICS_NON_FATAL"
        public const val ACTION_FORCE_CRASH: String =
            "com.becalm.android.DEBUG_CRASHLYTICS_FORCE_CRASH"
    }
}
