package com.becalm.android.unit.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiKoreanCopyInvariantSpecTest {

    @Test
    fun `korean locale keeps core app-authored copy in Korean`() {
        val strings = koreanStrings()
        val coreKeys = listOf(
            "terms_title",
            "login_title",
            "login_framing",
            "onb_setup_title",
            "onb_sources_title",
            "onb_cold_sync_headline",
            "persons_title",
            "today_title",
            "commitments_title",
            "settings_title",
            "privacy_management_title",
            "sources_title",
        )

        coreKeys.forEach { key ->
            val value = requireNotNull(strings[key]) { "Missing Korean string for $key" }
            assertTrue(
                "Expected Korean copy for $key, but was: $value",
                value.containsHangul(),
            )
        }
    }

    @Test
    fun `korean source labels expose product names but never raw ids`() {
        val strings = koreanStrings()
        val sourceLabels = mapOf(
            "raw_event_source_badge_gmail" to "gmail",
            "raw_event_source_badge_outlook_mail" to "outlook_mail",
            "raw_event_source_badge_naver_imap" to "naver_imap",
            "raw_event_source_badge_daum_imap" to "daum_imap",
            "raw_event_source_badge_google_calendar" to "google_calendar",
            "raw_event_source_badge_outlook_calendar" to "outlook_calendar",
            "raw_event_source_badge_voice" to "voice",
            "raw_event_source_badge_call_recording" to "call_recording",
            "raw_event_source_badge_meeting" to "meeting",
        )

        sourceLabels.forEach { (key, rawId) ->
            val value = requireNotNull(strings[key]) { "Missing Korean string for $key" }
            assertNotEquals("Display label must not expose raw source id for $key", rawId, value)
            assertTrue("Display label must not expose underscores for $key: $value", "_" !in value)
        }
    }

    private fun koreanStrings(): Map<String, String> {
        val file = listOf(
            File("app/src/main/res/values-ko/strings.xml"),
            File("src/main/res/values-ko/strings.xml"),
        ).firstOrNull(File::exists)
            ?: error("Cannot find values-ko/strings.xml from ${File(".").absolutePath}")
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        return buildMap {
            val nodes = document.getElementsByTagName("string")
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
                put(name, node.textContent.orEmpty())
            }
        }
    }

    private fun String.containsHangul(): Boolean =
        any { it in '\uAC00'..'\uD7A3' }
}
