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
    private val WHITESPACE_RUN: Regex = Regex("\\s+")
    private val helloMarkers = listOf("안녕하세요", "안녕하십니까")

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
        if (!isSentFolder(folder)) return emptyList()
        val openingLines = openingLines(bodyText)
        if (openingLines.isEmpty()) return emptyList()

        return openingLines
            .asSequence()
            .mapNotNull(::salutationNameSegment)
            .flatMap(::namesFromSegment)
            .distinct()
            .take(MAX_NAMES)
            .toList()
    }

    private fun salutationNameSegment(line: String): String? {
        val helloIndex = firstHelloMarkerIndex(line)
        if (helloIndex >= 0) {
            return segmentAroundHello(line, helloIndex)
                .takeIf { it.containsHonorificName() }
        }
        return line
            .takeIf { startsWithNameSalutation.containsMatchIn(it) }
            ?.takeBeforeSentenceBreak()
    }

    private fun isSentFolder(folder: String?): Boolean =
        folder.equals(SENT_FOLDER, ignoreCase = true)

    private fun openingLines(bodyText: String?): List<String> =
        bodyText
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.takeWhile { !it.isQuoteMarker() }
            ?.take(MAX_OPENING_LINES)
            ?.toList()
            .orEmpty()

    private fun namesFromSegment(segment: String): Sequence<String> =
        nameWithHonorific.findAll(segment)
            .map { match -> match.groupValues[1].normalizeName() }
            .filter { it.isUsableName() }

    private fun firstHelloMarkerIndex(line: String): Int =
        helloMarkers
            .map { marker -> line.indexOf(marker) }
            .filter { it >= 0 }
            .minOrNull()
            ?: -1

    private fun segmentAroundHello(
        line: String,
        helloIndex: Int,
    ): String =
        if (helloIndex == 0) {
            line.substringAfter("안녕하세요", line.substringAfter("안녕하십니까", line))
                .takeBeforeSentenceBreak()
        } else {
            line.substring(0, helloIndex)
        }

    private fun String.containsHonorificName(): Boolean =
        startsWithNameSalutation.containsMatchIn(this) || nameWithHonorific.containsMatchIn(this)

    private fun String.normalizeName(): String =
        trim().replace(WHITESPACE_RUN, " ").trim()

    private fun String.isUsableName(): Boolean =
        length in 2..10 && this !in genericNames

    private fun String.isQuoteMarker(): Boolean {
        val normalized = lowercase()
        return quoteMarkers.any { marker -> normalized.startsWith(marker) }
    }

    private fun String.takeBeforeSentenceBreak(): String =
        split('.', '。', '\n').firstOrNull().orEmpty().take(80)
}
