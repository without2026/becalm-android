package com.becalm.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

public class DebugCrashlyticsSmokeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(ACTION_RECORD_NON_FATAL, ACTION_FORCE_CRASH)) return

        Log.i(TAG, "Crashlytics debug smoke received action=${intent.action}")
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
                Log.i(TAG, "Crashlytics debug non-fatal smoke event recorded")
                Timber.i("Crashlytics debug non-fatal smoke event recorded")
            }
            ACTION_FORCE_CRASH -> {
                Log.i(TAG, "Crashlytics debug forced crash throwing now")
                throw IllegalStateException("Crashlytics debug forced crash smoke test")
            }
        }
    }

    public companion object {
        public const val ACTION_RECORD_NON_FATAL: String =
            "com.becalm.android.DEBUG_CRASHLYTICS_NON_FATAL"
        public const val ACTION_FORCE_CRASH: String =
            "com.becalm.android.DEBUG_CRASHLYTICS_FORCE_CRASH"
        private const val TAG: String = "BecalmCrashlyticsSmoke"
    }
}
