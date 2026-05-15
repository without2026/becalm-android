package com.becalm.android.core.analytics

internal object ProductAnalyticsValidation {
    private val piiKeys = setOf(
        "raw_title",
        "title",
        "quote",
        "snippet",
        "raw_snippet",
        "person_name",
        "display_name",
        "email",
        "phone",
        "phone_number",
        "search_query",
        "query",
        "body",
        "body_plain",
        "body_html",
    )
    private val piiValuePatterns = listOf(
        Regex("""\b[\w.+-]+@[\w-]+\.[\w.-]+\b"""),
        Regex("""(?:\+?\d[\s().-]?){8,}\d"""),
    )

    fun isValid(event: ProductAnalyticsEvent): Boolean =
        event.eventId.isNotBlank() &&
            event.eventId.length <= 128 &&
            event.eventName in ProductAnalyticsEvents.Allowed &&
            (event.sessionId == null || event.sessionId.length <= 128) &&
            depth(event.properties) <= 6 &&
            !containsPii(event.properties)

    fun sanitizedProperties(properties: Map<String, Any?>): Map<String, Any> =
        properties.mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val safeValue = normalizedValue(value) ?: return@mapNotNull null
            normalizedKey to safeValue
        }.toMap()

    private fun normalizedValue(value: Any?): Any? =
        when (value) {
            null -> null
            is String -> value
            is Boolean -> value
            is Int -> value
            is Long -> value
            is Float -> value.toDouble()
            is Double -> value
            is Number -> value.toDouble()
            is List<*> -> value.mapNotNull(::normalizedValue)
            is Map<*, *> -> value.entries.mapNotNull { (key, nested) ->
                val keyText = key?.toString()?.trim().orEmpty()
                val safeNested = normalizedValue(nested) ?: return@mapNotNull null
                keyText to safeNested
            }.toMap()
            else -> value.toString()
        }

    private fun containsPii(value: Any?): Boolean =
        when (value) {
            is Map<*, *> -> value.any { (key, nested) ->
                val keyText = key?.toString().orEmpty().lowercase()
                keyText in piiKeys || keyText.endsWith("_raw") || containsPii(nested)
            }
            is List<*> -> value.any(::containsPii)
            is String -> piiValuePatterns.any { it.containsMatchIn(value) }
            else -> false
        }

    private fun depth(value: Any?): Int =
        when (value) {
            is Map<*, *> -> 1 + (value.values.maxOfOrNull(::depth) ?: 0)
            is List<*> -> 1 + (value.maxOfOrNull(::depth) ?: 0)
            else -> 1
        }
}
