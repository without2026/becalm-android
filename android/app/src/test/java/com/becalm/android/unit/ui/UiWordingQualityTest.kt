package com.becalm.android.unit.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

class UiWordingQualityTest {

    @Test
    fun `default Korean strings avoid developer-facing wording`() {
        val stringsFile = File("src/main/res/values/strings.xml")
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(stringsFile)
        val offenders = mutableListOf<String>()

        scanNodes(document.getElementsByTagName("string"), offenders)
        scanNodes(document.getElementsByTagName("item"), offenders)

        assertTrue(
            "User-facing strings still contain developer-facing wording:\n${offenders.joinToString("\n")}",
            offenders.isEmpty(),
        )
    }

    private companion object {
        fun scanNodes(
            nodes: org.w3c.dom.NodeList,
            offenders: MutableList<String>,
        ) {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val name = node.attributes?.getNamedItem("name")?.nodeValue
                    ?: node.parentNode?.attributes?.getNamedItem("name")?.nodeValue
                    ?: "unnamed"
                val value = node.textContent.orEmpty()
                val banned = bannedWords.filter { it in value }
                if (banned.isNotEmpty()) {
                    offenders += "$name=${banned.joinToString()}: $value"
                }
            }
        }

        val bannedWords = listOf(
            "데이터",
            "소스",
            "증거",
            "로컬",
            "동기화",
            "이벤트",
            "메타데이터",
            "자격 증명",
            "업로드",
            "백엔드",
            "OAuth",
            "PIPA",
            "IMAP",
            "HTML",
            "E.164",
            "인물 매칭",
            "타임라인",
            "미이행",
            "아카이브",
            "리마인드",
            "팔로업",
        )
    }
}
