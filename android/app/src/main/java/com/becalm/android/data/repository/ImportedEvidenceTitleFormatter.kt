package com.becalm.android.data.repository

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.datetime.Instant

internal object ImportedEvidenceTitleFormatter {
    fun messageScreenshot(importedAt: Instant): String =
        "캡처 이미지 · ${importedAt.toKoreanMonthDayTime()}"

    fun meetingRecording(recordedAt: Instant): String =
        "회의 녹음 · ${recordedAt.toKoreanMonthDayTime()}"

    fun originalFileSnippet(displayName: String): String =
        "원본 파일: ${displayName.trim().ifEmpty { "알 수 없음" }}"

    private fun Instant.toKoreanMonthDayTime(): String =
        DateTimeFormatter
            .ofPattern("M월 d일 HH:mm", Locale.KOREA)
            .format(java.time.Instant.ofEpochMilli(toEpochMilliseconds()).atZone(ZoneId.systemDefault()))
}
