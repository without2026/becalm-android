package com.becalm.android.unit.privacy

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSafPrivacyRegressionSpecTest {

    @Test
    fun `production code does not request broad SAF folder grants for audio ingestion`() {
        // spec: MTG-001
        val forbiddenPatterns = listOf(
            "ACTION_OPEN_DOCUMENT_TREE",
            "OpenDocumentTree",
            "android.intent.action.OPEN_DOCUMENT_TREE",
            "FLAG_GRANT_PERSISTABLE_URI_PERMISSION",
            "takePersistableUriPermission",
            "DocumentsContract.createDocument",
        )
        val offenders = productionKotlinFiles()
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val forbidden = forbiddenPatterns.firstOrNull { it in line }
                    if (forbidden != null) {
                        "${file.relativeTo(repoRoot()).path}:${index + 1}: $forbidden"
                    } else {
                        null
                    }
                }
            }

        assertTrue(
            "Audio ingestion must use MediaStore or single-file ACTION_OPEN_DOCUMENT only; " +
                "broad SAF folder grants regress on newer Android privacy rules:\n" +
                offenders.joinToString(separator = "\n"),
            offenders.isEmpty(),
        )
    }

    private fun productionKotlinFiles(): List<File> =
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
}
