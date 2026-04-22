package com.becalm.android.core.observability

import javax.inject.Inject
import javax.inject.Singleton

/**
 * No-op [ObservabilityClient] for scenarios where **no** reporting is desired — tests
 * that do not care about events, or a future disable-by-flag kill-switch.
 *
 * Kept in the production tree so a Hilt test module can `@BindValue` it without
 * pulling in `androidTest` scaffolding. The production Hilt binding is
 * [LoggerObservabilityClient]; this class exists as an escape hatch, not the default.
 */
@Singleton
public class NoopObservabilityClient @Inject constructor() : ObservabilityClient {
    override fun captureMessage(message: String, tags: ObservabilityTags): Unit = Unit
    override fun captureException(throwable: Throwable, tags: ObservabilityTags): Unit = Unit
    override fun addBreadcrumb(category: String, message: String, data: ObservabilityTags): Unit = Unit
    override fun setUserScope(userId: String?): Unit = Unit
}
