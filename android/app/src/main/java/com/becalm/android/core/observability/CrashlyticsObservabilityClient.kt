package com.becalm.android.core.observability

import com.becalm.android.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
public class CrashlyticsObservabilityClient @Inject constructor(
    private val crashlytics: FirebaseCrashlyticsPort,
) : ObservabilityClient {

    override fun captureMessage(message: String, tags: ObservabilityTags) {
        if (!BuildConfig.TELEMETRY_ENABLED) return
        setKeys(tags)
        crashlytics.log("event:${sanitizeLabel(message)}")
    }

    override fun captureException(throwable: Throwable, tags: ObservabilityTags) {
        if (!BuildConfig.TELEMETRY_ENABLED) return
        setKeys(tags)
        crashlytics.recordException(sanitizedThrowable(throwable))
    }

    override fun addBreadcrumb(category: String, message: String, data: ObservabilityTags) {
        if (!BuildConfig.TELEMETRY_ENABLED) return
        setKeys(data)
        crashlytics.log("breadcrumb:${sanitizeLabel(category)}:${sanitizeLabel(message)}")
    }

    override fun setUserScope(userId: String?) {
        crashlytics.setUserId(userId.orEmpty())
    }

    private fun setKeys(tags: ObservabilityTags) {
        for ((key, value) in tags) {
            val safeKey = sanitizeKey(key).take(MAX_KEY_LENGTH)
            val safeValue = sanitizeValue(value).take(MAX_VALUE_LENGTH)
            if (safeKey.isNotBlank()) {
                crashlytics.setCustomKey(safeKey, safeValue)
            }
        }
    }

    private fun sanitizedThrowable(throwable: Throwable): Throwable =
        RuntimeException("sanitized:${throwable.javaClass.name}").also {
            it.stackTrace = throwable.stackTrace.copyOf()
        }

    private fun sanitizeLabel(value: String): String =
        if (PII_VALUE_PATTERNS.any { it.containsMatchIn(value) }) "[redacted]" else value.take(MAX_VALUE_LENGTH)

    private fun sanitizeKey(value: String): String {
        val normalized = value.lowercase()
        if (normalized in PII_KEYS || normalized.endsWith("_raw")) return "redacted"
        return normalized.replace(Regex("[^a-z0-9_.-]"), "_")
    }

    private fun sanitizeValue(value: String): String =
        if (PII_VALUE_PATTERNS.any { it.containsMatchIn(value) }) "[redacted]" else value

    private companion object {
        private const val MAX_KEY_LENGTH = 40
        private const val MAX_VALUE_LENGTH = 100
        private val PII_KEYS = setOf(
            "email",
            "phone",
            "phone_number",
            "token",
            "access_token",
            "refresh_token",
            "title",
            "quote",
            "snippet",
            "body",
            "search_query",
            "query",
            "person_name",
            "display_name",
        )
        private val PII_VALUE_PATTERNS = listOf(
            Regex("""\b[\w.+-]+@[\w-]+\.[\w.-]+\b"""),
            Regex("""\bey[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"""),
            Regex("""(?i)\b(Bearer|access_token|refresh_token)[=:\s]+[A-Za-z0-9._+/=-]{16,}"""),
            Regex("""(?:\+?\d[\s().-]?){8,}\d"""),
        )
    }
}
