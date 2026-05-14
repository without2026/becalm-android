package com.becalm.android.unit.privacy

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLoggingPrivacySpecTest {

    @Test
    fun `production kotlin source does not call raw android Log APIs`() {
        // spec: REL-003
        val offenders = sourceFiles()
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val trimmed = line.trimStart()
                    val isComment = trimmed.startsWith("//") || trimmed.startsWith("*")
                    val importsAndroidLog = trimmed == "import android.util.Log"
                    val callsRawLog = RAW_LOG_CALL.containsMatchIn(line)
                    if (!isComment && (importsAndroidLog || callsRawLog)) {
                        "${file.relativeTo(repoRoot()).path}:${index + 1}:$line"
                    } else {
                        null
                    }
                }
            }

        assertTrue(
            "Production source must use Logger/Timber abstraction, not raw android.util.Log:\n" +
                offenders.joinToString(separator = "\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `okhttp logger redacts sensitive auth and cookie headers`() {
        // spec: REL-003
        val source = repoFile("android/app/src/main/java/com/becalm/android/data/remote/api/ApiFactory.kt")
            .readText()

        assertTrue(source.contains("redactHeader(\"Authorization\")"))
        assertTrue(source.contains("redactHeader(\"Proxy-Authorization\")"))
        assertTrue(source.contains("redactHeader(\"Cookie\")"))
        assertTrue(source.contains("redactHeader(\"Set-Cookie\")"))
        assertFalse(source.contains("HttpLoggingInterceptor.Level.BODY"))
    }

    private fun sourceFiles(): List<File> =
        repoFile("android/app/src/main/java/com/becalm/android")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun repoFile(path: String): File = File(repoRoot(), path)

    private fun repoRoot(): File {
        val userDir = checkNotNull(System.getProperty("user.dir")) { "user.dir system property missing" }
        generateSequence(File(userDir).absoluteFile) { it.parentFile }
            .forEach { dir ->
                if (File(dir, ".git").exists() && File(dir, "android").exists()) return dir
            }
        error("Repository root not found from $userDir")
    }

    private companion object {
        val RAW_LOG_CALL: Regex = Regex("""\bLog\.(v|d|i|w|e|wtf)\(""")
    }
}
