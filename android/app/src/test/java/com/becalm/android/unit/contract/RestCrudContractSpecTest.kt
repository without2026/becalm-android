package com.becalm.android.unit.contract

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RestCrudContractSpecTest {

    @Test
    fun `API contract pins Android local-first REST CRUD and command boundary`() {
        val text = specFile(".spec/contracts/api-contract.yml").readText()

        assertTrue(text.contains("REST + CRUD ownership pattern"))
        assertTrue(text.contains("Android Room/local files are the canonical client-side source of truth"))
        assertTrue(text.contains("marks rows\n#     sync_status='pending'"))
        assertTrue(text.contains("Resource endpoints are CRUD mirrors/coordinators"))
        assertTrue(text.contains("Command endpoints are reserved for non-resource work"))
        assertTrue(text.contains("POST /v1/{resource}:{action}"))
        assertTrue(text.contains("Raw/private bytes, OCR text, full transcript, and raw LLM output are never REST resources"))
    }

    private fun specFile(relativePath: String): File {
        val start = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        return requireNotNull(
            generateSequence(start) { it.parentFile }
                .map { File(it, relativePath) }
                .firstOrNull { it.isFile },
        ) {
            "Could not find $relativePath from $start"
        }
    }
}
