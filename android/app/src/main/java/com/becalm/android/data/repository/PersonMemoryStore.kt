package com.becalm.android.data.repository

import android.content.Context
import com.becalm.android.domain.person.PersonMemoryHash
import com.becalm.android.domain.person.PersonMemoryPathResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

public data class PersonMemoryWrite(
    val relativePath: String,
    val contentHash: String,
    val byteSize: Long,
)

@Singleton
public class PersonMemoryStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    public fun write(
        userId: String,
        personId: String,
        markdown: String,
    ): PersonMemoryWrite {
        val relativePath = PersonMemoryPathResolver.localRelativePath(userId, personId)
        val file = resolve(relativePath)
        val bytes = markdown.toByteArray(Charsets.UTF_8)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        return PersonMemoryWrite(
            relativePath = relativePath,
            contentHash = PersonMemoryHash.sha256(markdown),
            byteSize = bytes.size.toLong(),
        )
    }

    public fun readText(relativePath: String): String? {
        val file = resolve(relativePath)
        if (!file.exists() || !file.isFile) return null
        return file.readText(Charsets.UTF_8)
    }

    public fun delete(userId: String, personId: String): Boolean {
        val file = resolve(PersonMemoryPathResolver.localRelativePath(userId, personId))
        return !file.exists() || file.delete()
    }

    private fun resolve(relativePath: String): File {
        val cleanPath = relativePath.split('/').filter { it.isNotBlank() && it != ".." }
        return cleanPath.fold(context.filesDir) { parent, child -> File(parent, child) }
    }
}
