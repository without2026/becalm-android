package com.becalm.android.unit.data.repository

import com.becalm.android.data.repository.ProcessingPhase
import com.becalm.android.data.repository.isActive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingStatusRepositorySpecTest {

    @Test
    fun `new items is not rendered as an active spinner phase`() {
        assertFalse(ProcessingPhase.NEW_ITEMS.isActive)
    }

    @Test
    fun `active processing phases are limited to real ongoing work`() {
        assertTrue(ProcessingPhase.SCANNING.isActive)
        assertTrue(ProcessingPhase.GEMINI.isActive)
        assertTrue(ProcessingPhase.UPLOADING.isActive)
    }
}
