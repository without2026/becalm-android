package com.becalm.android.unit.source

import com.becalm.android.data.remote.dto.SourceType
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTypeContractTest {
    @Test
    fun `manual import sources are raw and product sources`() {
        assertTrue(SourceType.MESSAGE_SCREENSHOT in SourceType.ALL)
        assertTrue(SourceType.MESSAGE_SCREENSHOT in SourceType.PRODUCT_SOURCES)
        assertTrue(SourceType.MANUAL_TEXT in SourceType.ALL)
        assertTrue(SourceType.MANUAL_TEXT in SourceType.PRODUCT_SOURCES)
    }
}
