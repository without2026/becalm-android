package com.becalm.android.unit.domain.email

import com.becalm.android.domain.email.OutgoingEmailSalutationExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingEmailSalutationExtractorSpecTest {

    @Test
    fun `extracts Korean recipient name from sent opening salutation`() {
        val names = OutgoingEmailSalutationExtractor.extractNames(
            folder = "SENT",
            bodyText = """
                강지훈님, 안녕하세요.

                지난번 말씀 주신 내용 관련해 연락드립니다.
            """.trimIndent(),
        )

        assertEquals(listOf("강지훈"), names)
    }

    @Test
    fun `extracts name after hello in sent mail`() {
        val names = OutgoingEmailSalutationExtractor.extractNames(
            folder = "SENT",
            bodyText = """
                안녕하세요 강지훈 대표님,
                임규민입니다.
            """.trimIndent(),
        )

        assertEquals(listOf("강지훈"), names)
    }

    @Test
    fun `extracts mentor salutation name before hello`() {
        val names = OutgoingEmailSalutationExtractor.extractNames(
            folder = "SENT",
            bodyText = """
                김현수 멘토님, 안녕하세요.

                갑작스러운 연락 양해 부탁드립니다.
            """.trimIndent(),
        )

        assertEquals(listOf("김현수"), names)
    }

    @Test
    fun `extracts multiple salutation names as review signals`() {
        val names = OutgoingEmailSalutationExtractor.extractNames(
            folder = "SENT",
            bodyText = "강지훈님, 박민수님 안녕하세요.",
        )

        assertEquals(listOf("강지훈", "박민수"), names)
    }

    @Test
    fun `does not treat incoming greeting as counterparty name`() {
        val names = OutgoingEmailSalutationExtractor.extractNames(
            folder = "INBOX",
            bodyText = "강지훈님, 안녕하세요.",
        )

        assertTrue(names.isEmpty())
    }

    @Test
    fun `ignores title-only greetings`() {
        val names = OutgoingEmailSalutationExtractor.extractNames(
            folder = "SENT",
            bodyText = "대표님, 안녕하세요.",
        )

        assertTrue(names.isEmpty())
    }

    @Test
    fun `does not extract noisy words after the greeting sentence`() {
        val names = OutgoingEmailSalutationExtractor.extractNames(
            folder = "SENT",
            bodyText = "안녕하세요. 지난번 말씀 주신 대표님 관련 자료 전달드립니다.",
        )

        assertTrue(names.isEmpty())
    }
}
