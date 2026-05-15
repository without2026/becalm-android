package com.becalm.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.amplitude.android.Amplitude
import com.amplitude.android.Configuration
import com.becalm.android.BuildConfig
import java.util.UUID
import timber.log.Timber

public class DebugAmplitudeSmokeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(ACTION_FAKE_CRASH_EVENT, ACTION_METRICS_SMOKE)) return

        Log.i(TAG, "Amplitude debug smoke received action=${intent.action}")
        val pending = goAsync()
        Thread {
            runCatching { sendSmokeEvents(context.applicationContext, intent.action.orEmpty()) }
                .onFailure {
                    Log.e(TAG, "Amplitude debug smoke failed", it)
                    Timber.e(it, "Amplitude debug smoke failed")
                }
            Thread.sleep(AMPLITUDE_FLUSH_WAIT_MILLIS)
            Log.i(TAG, "Amplitude debug smoke finished action=${intent.action}")
            pending.finish()
        }.start()
    }

    private fun sendSmokeEvents(context: Context, action: String) {
        val apiKey = BuildConfig.AMPLITUDE_API_KEY
        if (!BuildConfig.TELEMETRY_ENABLED || apiKey.isBlank()) {
            Log.w(TAG, "Amplitude debug smoke skipped because telemetry is disabled or API key is blank")
            Timber.w("Amplitude debug smoke skipped because telemetry is disabled or API key is blank")
            return
        }

        val amplitude = Amplitude(
            Configuration(apiKey, context).apply {
                flushQueueSize = 1
                flushIntervalMillis = 1_000
                useBatch = true
                callback = { event, code, message ->
                    Log.i(
                        TAG,
                        "Amplitude debug smoke callback eventType=${event.eventType} " +
                            "statusCode=$code message=$message",
                    )
                    Timber.i(
                        "Amplitude debug smoke callback eventType=${event.eventType} " +
                            "statusCode=$code message=$message",
                    )
                }
            },
        )
        val smokeRunId = UUID.randomUUID().toString()
        amplitude.track(
            EVENT_FAKE_CRASH_REPORTED,
            mapOf(
                "smoke_test" to true,
                "crash_provider" to "firebase_crashlytics",
                "trigger" to action,
                "smoke_run_id" to smokeRunId,
            ),
        )
        for (event in canonicalMetricEvents(smokeRunId)) {
            amplitude.track(event.name, event.properties)
        }
        for (metricName in METRIC_SMOKE_NAMES) {
            amplitude.track(
                EVENT_METRIC_OBSERVED,
                mapOf(
                    "smoke_test" to true,
                    "smoke_run_id" to smokeRunId,
                    "metric_name" to metricName,
                ),
            )
        }
        amplitude.flush()
        Log.i(TAG, "Amplitude debug smoke queued smokeRunId=$smokeRunId metricCount=${METRIC_SMOKE_NAMES.size}")
        Timber.i("Amplitude debug smoke queued smokeRunId=$smokeRunId metricCount=${METRIC_SMOKE_NAMES.size}")
    }

    private data class SmokeEvent(
        val name: String,
        val properties: Map<String, Any>,
    )

    private fun canonicalMetricEvents(smokeRunId: String): List<SmokeEvent> {
        val notificationInstanceId = UUID.randomUUID().toString()
        val sessionOrganic = UUID.randomUUID().toString()
        val sessionNotification = UUID.randomUUID().toString()
        val base = mapOf("smoke_test" to true, "smoke_run_id" to smokeRunId)
        return listOf(
            SmokeEvent("session_started", base + mapOf("session_id" to sessionOrganic, "entry_source" to "organic")),
            SmokeEvent("session_started", base + mapOf("session_id" to sessionNotification, "entry_source" to "notification_open")),
            SmokeEvent(
                "session_ended",
                base + mapOf("session_id" to sessionOrganic, "entry_source" to "organic", "duration_seconds" to 42),
            ),
            SmokeEvent(
                "commitment_notification_posted",
                base + mapOf("commitment_id" to "debug-commitment", "notification_instance_id" to notificationInstanceId),
            ),
            SmokeEvent(
                "commitment_notification_opened",
                base + mapOf(
                    "commitment_id" to "debug-commitment",
                    "notification_instance_id" to notificationInstanceId,
                    "entry_source" to "notification_open",
                    "commitment_state_at_open" to "pending",
                    "state_mix_at_open" to "pending",
                    "available_actions_at_open" to listOf("remind", "follow_up", "complete", "cancel", "edit"),
                    "eligible_action_count" to 5,
                ),
            ),
            SmokeEvent(
                "commitment_action_selected",
                base + mapOf(
                    "commitment_id" to "debug-commitment",
                    "notification_instance_id" to notificationInstanceId,
                    "action" to "remind",
                    "action_strength" to "weak",
                    "seconds_since_notification_open" to 3,
                    "core_active" to true,
                    "high_intent" to false,
                ),
            ),
            SmokeEvent(
                "commitment_action_selected",
                base + mapOf(
                    "commitment_id" to "debug-commitment",
                    "notification_instance_id" to notificationInstanceId,
                    "action" to "follow_up",
                    "action_strength" to "strong",
                    "seconds_since_notification_open" to 5,
                    "core_active" to true,
                    "high_intent" to true,
                ),
            ),
            SmokeEvent(
                "commitment_action_selected",
                base + mapOf(
                    "commitment_id" to "debug-commitment",
                    "notification_instance_id" to notificationInstanceId,
                    "action" to "cancel",
                    "action_strength" to "cleanup",
                    "seconds_since_notification_open" to 8,
                    "core_active" to true,
                    "high_intent" to false,
                ),
            ),
            SmokeEvent(
                "commitment_action_selected",
                base + mapOf(
                    "commitment_id" to "debug-commitment",
                    "action" to "edit",
                    "action_strength" to "strong",
                    "precision_signal" to "corrected_extraction",
                    "core_active" to true,
                    "high_intent" to true,
                ),
            ),
            SmokeEvent(
                "screen_exited",
                base + mapOf(
                    "route" to "persons/debug/events/debug",
                    "screen_group" to "detail",
                    "duration_seconds" to 30,
                    "meaningful_usage" to true,
                ),
            ),
            SmokeEvent("historical_item_viewed", base + mapOf("surface" to "raw_event_detail", "core_active" to true)),
            SmokeEvent("search_performed", base + mapOf("surface" to "persons", "query_length" to 4, "core_active" to true)),
            SmokeEvent("search_to_detail", base + mapOf("surface" to "persons", "query_length" to 4, "core_active" to true)),
            SmokeEvent("person_match_completed", base + mapOf("source_type" to "gmail", "core_active" to true)),
            SmokeEvent("evidence_import_completed", base + mapOf("source_type" to "meeting", "core_active" to true)),
        )
    }

    public companion object {
        public const val ACTION_FAKE_CRASH_EVENT: String =
            "com.becalm.android.DEBUG_AMPLITUDE_FAKE_CRASH_EVENT"
        public const val ACTION_METRICS_SMOKE: String =
            "com.becalm.android.DEBUG_AMPLITUDE_METRICS_SMOKE"
        public const val EVENT_FAKE_CRASH_REPORTED: String = "debug_fake_crash_reported"
        public const val EVENT_METRIC_OBSERVED: String = "debug_metric_observed"
        private const val TAG: String = "BecalmAmplitudeSmoke"
        private val METRIC_SMOKE_NAMES: List<String> = listOf(
            "notification_open_rate",
            "eligible_action_conversion",
            "any_reactive_action_rate",
            "strong_reactive_action_rate",
            "weak_reactive_action_rate",
            "cleanup_reactive_action_rate",
            "time_to_first_reactive_action",
            "state_mix_at_open",
            "action_item_precision_proxy",
            "organic_session_count",
            "notification_open_session_count",
            "pull_push_ratio",
            "app_session_duration_seconds",
            "total_app_usage_seconds",
            "organic_usage_seconds",
            "notification_open_usage_seconds",
            "meaningful_usage_seconds",
            "core_active_event_count",
            "core_active_events_per_user_per_day",
            "core_active_event_breakdown",
            "historical_view_count",
            "historical_view_rate",
            "search_to_detail_count",
            "search_to_detail_rate",
            "repeat_core_active_user_count",
            "high_intent_user_count",
        )
        private const val AMPLITUDE_FLUSH_WAIT_MILLIS: Long = 20_000
    }
}
