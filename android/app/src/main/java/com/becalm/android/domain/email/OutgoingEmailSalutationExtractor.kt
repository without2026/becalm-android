package com.becalm.android.domain.email

/**
 * Extracts recipient names from the opening salutation of an outgoing email.
 *
 * This is intentionally conservative: only SENT-folder messages are considered, and
 * only the latest body opening is inspected. The extracted names are review signals,
 * not verified identities.
 */
public object OutgoingEmailSalutationExtractor {
    private const val SENT_FOLDER: String = "SENT"
    private const val MAX_OPENING_LINES: Int = 5
    private const val MAX_NAMES: Int = 4

    private val quoteMarkers = listOf(
        "-----original message-----",
        "-----forwarded message-----",
        "보낸 사람:",
        "from:",
        "on ",
    )

    private val genericNames = setOf(
        "감사",
        "고객",
        "귀하",
        "담당자",
        "대표",
        "멘토",
        "교수",
        "선생",
        "팀장",
        "박사",
        "변호사",
        "원장",
        "이사",
        "저희",
        "주신",
        "지난",
        "당시",
        "메일",
        "내용",
    )

    private val nameWithHonorific = Regex(
        "([가-힣][가-힣 ]{0,8}?[가-힣])\\s*(?:대표님|멘토님|교수님|선생님|팀장님|박사님|변호사님|담당자님|원장님|이사님|님께|님|께)",
    )
    private val startsWithNameSalutation = Regex(
        "^\\s*([가-힣][가-힣 ]{0,8}?[가-힣])\\s*(?:대표님|멘토님|교수님|선생님|팀장님|박사님|변호사님|담당자님|원장님|이사님|님께|님|께)",
    )

    public fun extractNames(
        folder: String?,
        bodyText: String?,
    ): List<String> {
        if (!folder.equals(SENT_FOLDER, ignoreCase = true)) return emptyList()
        val openingLines = bodyText
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.takeWhile { !it.isQuoteMarker() }
            ?.take(MAX_OPENING_LINES)
            ?.toList()
            .orEmpty()
        if (openingLines.isEmpty()) return emptyList()

        return openingLines
            .mapNotNull(::salutationNameSegment)
            .flatMap { segment ->
                nameWithHonorific.findAll(segment)
                    .map { it.groupValues[1].trim() }
                    .map { it.replace(Regex("\\s+"), " ").trim() }
                    .filter { it.isUsableName() }
                    .toList()
            }
            .distinct()
            .take(MAX_NAMES)
    }

    private fun salutationNameSegment(line: String): String? {
        val helloIndex = listOf("안녕하세요", "안녕하십니까")
            .map { marker -> line.indexOf(marker) }
            .filter { it >= 0 }
            .minOrNull()
            ?: -1
        if (helloIndex >= 0) {
            return if (helloIndex == 0) {
                line.substringAfter("안녕하세요", line.substringAfter("안녕하십니까", line))
                    .takeBeforeSentenceBreak()
            } else {
                line.substring(0, helloIndex)
            }.takeIf { startsWithNameSalutation.containsMatchIn(it) || nameWithHonorific.containsMatchIn(it) }
        }
        return line
            .takeIf { startsWithNameSalutation.containsMatchIn(it) }
            ?.takeBeforeSentenceBreak()
    }

    private fun String.isUsableName(): Boolean =
        length in 2..10 && this !in genericNames

    private fun String.isQuoteMarker(): Boolean {
        val normalized = lowercase()
        return quoteMarkers.any { marker -> normalized.startsWith(marker) }
    }

    private fun String.takeBeforeSentenceBreak(): String =
        split('.', '。', '\n').firstOrNull().orEmpty().take(80)
}
