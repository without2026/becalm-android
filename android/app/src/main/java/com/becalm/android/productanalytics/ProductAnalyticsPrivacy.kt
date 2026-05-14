package com.becalm.android.productanalytics

internal object ProductAnalyticsPrivacy {
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
    private val emailRegex = Regex("""\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""", RegexOption.IGNORE_CASE)
    private val phoneRegex = Regex("""(?:\+?\d[\s().-]?){8,}\d""")

    fun isSafeEventName(eventName: String): Boolean =
        eventName in ProductAnalyticsNames.ALLOWLIST

    fun sanitizeProperties(properties: Map<String, Any?>): Map<String, Any?>? =
        sanitizeMap(properties)

    private fun sanitizeMap(map: Map<String, Any?>): Map<String, Any?>? {
        val sanitized = linkedMapOf<String, Any?>()
        for ((key, value) in map) {
            val normalizedKey = key.lowercase()
            if (normalizedKey in piiKeys || normalizedKey.endsWith("_raw")) return null
            val sanitizedValue = sanitizeValue(value)
            if (sanitizedValue === Unsafe) return null
            sanitized[key] = sanitizedValue
        }
        return sanitized
    }

    private fun sanitizeValue(value: Any?): Any? {
        return when (value) {
            null -> null
            is String -> if (emailRegex.containsMatchIn(value) || phoneRegex.containsMatchIn(value)) Unsafe else value
            is Number, is Boolean -> value
            is List<*> -> value.map {
                val sanitized = sanitizeValue(it)
                if (sanitized === Unsafe) return Unsafe
                sanitized
            }
            is Array<*> -> value.map {
                val sanitized = sanitizeValue(it)
                if (sanitized === Unsafe) return Unsafe
                sanitized
            }
            is Map<*, *> -> {
                val stringMap = value.entries.associate { (k, v) -> k.toString() to v }
                sanitizeMap(stringMap) ?: return Unsafe
            }
            else -> value.toString().takeIf {
                !emailRegex.containsMatchIn(it) && !phoneRegex.containsMatchIn(it)
            } ?: Unsafe
        }
    }

    private data object Unsafe
}
