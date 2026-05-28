package com.becalm.android.ui.onboarding

import androidx.navigation.NavHostController
import com.becalm.android.ui.navigation.BecalmRoute

internal fun NavHostController.dispatchFirstMemoryFollowUpAction(
    action: FirstMemoryFollowUpAction,
    onMeetingAudio: () -> Unit = {},
    onMessageScreenshot: () -> Unit = {},
) {
    when (action) {
        FirstMemoryFollowUpAction.GMAIL -> navigate(BecalmRoute.SettingsSourceConnection("gmail").path)
        FirstMemoryFollowUpAction.NAVER_MAIL -> navigate(BecalmRoute.OnboardingEmailPipa("imap_naver").path)
        FirstMemoryFollowUpAction.DAUM_MAIL -> navigate(BecalmRoute.OnboardingEmailPipa("imap_daum").path)
        FirstMemoryFollowUpAction.CALL_RECORDING -> navigate(BecalmRoute.OnboardingRecordingFolder.path)
        FirstMemoryFollowUpAction.MEETING_AUDIO -> onMeetingAudio()
        FirstMemoryFollowUpAction.MEETING_CALENDAR -> navigate(BecalmRoute.SettingsSourceConnection("google_calendar").path)
        FirstMemoryFollowUpAction.MESSENGER_SCREENSHOT -> onMessageScreenshot()
    }
}
