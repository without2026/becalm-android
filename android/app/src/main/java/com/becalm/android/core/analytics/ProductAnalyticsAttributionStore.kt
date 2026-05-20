package com.becalm.android.core.analytics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Instant

public data class NotificationOpenAttribution(
    val notificationInstanceId: String,
    val commitmentId: String?,
    val openedAt: Instant,
)

public interface ProductAnalyticsAttributionStore {
    public fun saveNotificationOpen(attribution: NotificationOpenAttribution)
    public fun readNotificationOpen(): NotificationOpenAttribution?
    public fun clearNotificationOpen()
}

@Singleton
public class SharedPreferencesProductAnalyticsAttributionStore @Inject constructor(
    @ApplicationContext context: Context,
) : ProductAnalyticsAttributionStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun saveNotificationOpen(attribution: NotificationOpenAttribution) {
        prefs.edit()
            .putString(KEY_NOTIFICATION_INSTANCE_ID, attribution.notificationInstanceId)
            .putString(KEY_COMMITMENT_ID, attribution.commitmentId)
            .putString(KEY_OPENED_AT, attribution.openedAt.toString())
            .apply()
    }

    override fun readNotificationOpen(): NotificationOpenAttribution? {
        val notificationInstanceId = prefs.getString(KEY_NOTIFICATION_INSTANCE_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val openedAt = prefs.getString(KEY_OPENED_AT, null)
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return null
        return NotificationOpenAttribution(
            notificationInstanceId = notificationInstanceId,
            commitmentId = prefs.getString(KEY_COMMITMENT_ID, null)?.takeIf { it.isNotBlank() },
            openedAt = openedAt,
        )
    }

    override fun clearNotificationOpen() {
        prefs.edit()
            .remove(KEY_NOTIFICATION_INSTANCE_ID)
            .remove(KEY_COMMITMENT_ID)
            .remove(KEY_OPENED_AT)
            .apply()
    }

    private companion object {
        private const val PREFS_NAME = "becalm_product_analytics_attribution"
        private const val KEY_NOTIFICATION_INSTANCE_ID = "notification_instance_id"
        private const val KEY_COMMITMENT_ID = "commitment_id"
        private const val KEY_OPENED_AT = "opened_at"
    }
}

public class InMemoryProductAnalyticsAttributionStore : ProductAnalyticsAttributionStore {
    private var notificationOpen: NotificationOpenAttribution? = null

    override fun saveNotificationOpen(attribution: NotificationOpenAttribution) {
        notificationOpen = attribution
    }

    override fun readNotificationOpen(): NotificationOpenAttribution? = notificationOpen

    override fun clearNotificationOpen() {
        notificationOpen = null
    }
}
