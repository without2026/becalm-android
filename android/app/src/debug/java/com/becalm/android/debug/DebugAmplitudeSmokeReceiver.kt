package com.becalm.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.amplitude.android.Amplitude
import com.amplitude.android.Configuration
import com.becalm.android.BuildConfig
import java.util.UUID
import timber.log.Timber

public class DebugAmplitudeSmokeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FAKE_CRASH_EVENT) return

        val pending = goAsync()
        Thread {
            runCatching { sendSmokeEvent(context.applicationContext) }
                .onFailure { Timber.e(it, "Amplitude debug smoke failed") }
            Thread.sleep(AMPLITUDE_FLUSH_WAIT_MILLIS)
            pending.finish()
        }.start()
    }

    private fun sendSmokeEvent(context: Context) {
        val apiKey = BuildConfig.AMPLITUDE_API_KEY
        if (!BuildConfig.TELEMETRY_ENABLED || apiKey.isBlank()) {
            Timber.w("Amplitude debug smoke skipped because telemetry is disabled or API key is blank")
            return
        }

        val amplitude = Amplitude(
            Configuration(apiKey, context).apply {
                flushQueueSize = 1
                flushIntervalMillis = 1_000
                useBatch = true
                callback = { event, code, message ->
                    Timber.i(
                        "Amplitude debug smoke callback eventType=${event.eventType} " +
                            "statusCode=$code message=$message",
                    )
                }
            },
        )
        val eventId = UUID.randomUUID().toString()
        amplitude.track(
            EVENT_FAKE_CRASH_REPORTED,
            mapOf(
                "smoke_test" to true,
                "crash_provider" to "firebase_crashlytics",
                "trigger" to "adb_broadcast",
                "event_id" to eventId,
            ),
        )
        amplitude.flush()
        Timber.i("Amplitude debug smoke event queued eventName=$EVENT_FAKE_CRASH_REPORTED eventId=$eventId")
    }

    public companion object {
        public const val ACTION_FAKE_CRASH_EVENT: String =
            "com.becalm.android.DEBUG_AMPLITUDE_FAKE_CRASH_EVENT"
        public const val EVENT_FAKE_CRASH_REPORTED: String = "debug_fake_crash_reported"
        private const val AMPLITUDE_FLUSH_WAIT_MILLIS: Long = 20_000
    }
}
