package com.becalm.android.ui.onboarding

import androidx.annotation.StringRes
import com.becalm.android.R
import com.becalm.android.domain.onboarding.FirstMemoryOrigin

public enum class FirstMemoryFollowUpAction(
    @StringRes public val labelRes: Int,
    public val testTag: String,
) {
    GMAIL(
        labelRes = R.string.person_detail_first_memory_email_gmail,
        testTag = "first-memory-action-gmail",
    ),
    NAVER_MAIL(
        labelRes = R.string.person_detail_first_memory_email_naver,
        testTag = "first-memory-action-naver",
    ),
    DAUM_MAIL(
        labelRes = R.string.person_detail_first_memory_email_daum,
        testTag = "first-memory-action-daum",
    ),
    CALL_RECORDING(
        labelRes = R.string.person_detail_first_memory_call,
        testTag = "first-memory-action-call",
    ),
    MEETING_AUDIO(
        labelRes = R.string.person_detail_first_memory_meeting_audio,
        testTag = "first-memory-action-meeting-audio",
    ),
    MEETING_CALENDAR(
        labelRes = R.string.person_detail_first_memory_meeting_calendar,
        testTag = "first-memory-action-meeting-calendar",
    ),
    MESSENGER_SCREENSHOT(
        labelRes = R.string.person_detail_first_memory_messenger,
        testTag = "first-memory-action-messenger",
    ),
}

internal fun firstMemoryFollowUpActionsFor(origin: FirstMemoryOrigin): List<FirstMemoryFollowUpAction> =
    when (origin) {
        FirstMemoryOrigin.EMAIL -> listOf(
            FirstMemoryFollowUpAction.GMAIL,
            FirstMemoryFollowUpAction.NAVER_MAIL,
            FirstMemoryFollowUpAction.DAUM_MAIL,
        )
        FirstMemoryOrigin.CALL -> listOf(FirstMemoryFollowUpAction.CALL_RECORDING)
        FirstMemoryOrigin.MEETING -> listOf(
            FirstMemoryFollowUpAction.MEETING_AUDIO,
            FirstMemoryFollowUpAction.MEETING_CALENDAR,
        )
        FirstMemoryOrigin.MESSENGER -> listOf(FirstMemoryFollowUpAction.MESSENGER_SCREENSHOT)
    }

internal fun firstMemoryFollowUpActionsFor(originKey: String): List<FirstMemoryFollowUpAction> =
    FirstMemoryOrigin.entries
        .firstOrNull { it.name.equals(originKey, ignoreCase = true) }
        ?.let(::firstMemoryFollowUpActionsFor)
        .orEmpty()

internal fun primaryFirstMemoryFollowUpAction(originKey: String?): FirstMemoryFollowUpAction? =
    originKey?.let(::firstMemoryFollowUpActionsFor)?.firstOrNull()
