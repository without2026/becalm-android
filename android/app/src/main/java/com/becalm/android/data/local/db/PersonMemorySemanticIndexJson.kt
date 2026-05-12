package com.becalm.android.data.local.db

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

internal object PersonMemorySemanticIndexJson {
    private val listType = Types.newParameterizedType(List::class.java, String::class.java)
    private val adapter = Moshi.Builder().build().adapter<List<String>>(listType)

    fun encode(values: Set<String>): String =
        adapter.toJson(values.toList())

    fun decode(value: String): Set<String> =
        runCatching { adapter.fromJson(value).orEmpty() }
            .getOrDefault(emptyList())
            .filter { it.isNotBlank() }
            .toCollection(linkedSetOf())
}
