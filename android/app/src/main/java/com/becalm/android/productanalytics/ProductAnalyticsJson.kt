package com.becalm.android.productanalytics

import org.json.JSONArray
import org.json.JSONObject

internal object ProductAnalyticsJson {
    fun encode(properties: Map<String, Any?>): String =
        JSONObject().apply {
            for ((key, value) in properties) {
                put(key, encodeValue(value))
            }
        }.toString()

    fun decode(json: String): Map<String, Any?> {
        if (json.isBlank()) return emptyMap()
        return decodeObject(JSONObject(json))
    }

    private fun encodeValue(value: Any?): Any? =
        when (value) {
            null -> JSONObject.NULL
            is String, is Number, is Boolean -> value
            is Map<*, *> -> JSONObject().apply {
                for ((key, nested) in value) put(key.toString(), encodeValue(nested))
            }
            is Iterable<*> -> JSONArray().apply {
                value.forEach { put(encodeValue(it)) }
            }
            is Array<*> -> JSONArray().apply {
                value.forEach { put(encodeValue(it)) }
            }
            else -> value.toString()
        }

    private fun decodeObject(obj: JSONObject): Map<String, Any?> =
        buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, decodeValue(obj.get(key)))
            }
        }

    private fun decodeArray(array: JSONArray): List<Any?> =
        buildList {
            for (index in 0 until array.length()) {
                add(decodeValue(array.get(index)))
            }
        }

    private fun decodeValue(value: Any?): Any? =
        when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> decodeObject(value)
            is JSONArray -> decodeArray(value)
            else -> value
        }
}
