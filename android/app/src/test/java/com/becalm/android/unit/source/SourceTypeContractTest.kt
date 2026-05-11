package com.becalm.android.unit.source

import com.becalm.android.data.remote.dto.SourceType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTypeContractTest {
    @Test
    fun `manual screenshot source is raw and product but text source is blocked`() {
        assertTrue(SourceType.MESSAGE_SCREENSHOT in SourceType.ALL)
        assertTrue(SourceType.MESSAGE_SCREENSHOT in SourceType.PRODUCT_SOURCES)
        assertFalse("manual_text" in SourceType.ALL)
        assertFalse("manual_text" in SourceType.PRODUCT_SOURCES)
    }
}
