package com.becalm.android.unit.ui.sources

import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.sources.sourceConnectionTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceConnectionDisplaySpecTest {

    @Test
    fun `imap source connection titles use fixed email labels`() {
        assertEquals("Naver Email", sourceConnectionTitle(SourceType.NAVER_IMAP, "mail"))
        assertEquals("Daum Email", sourceConnectionTitle(SourceType.DAUM_IMAP, "mail"))
    }
}
