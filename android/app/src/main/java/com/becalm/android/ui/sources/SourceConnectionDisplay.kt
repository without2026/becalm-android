package com.becalm.android.ui.sources

import com.becalm.android.data.remote.dto.SourceType

internal fun sourceConnectionTitle(provider: String, capability: String): String =
    when {
        provider == "google" && capability == "mail" -> "Gmail"
        provider == "google" && capability == "calendar" -> "Google Calendar"
        provider == "outlook" && capability == "mail" -> "Outlook Mail"
        provider == "outlook" && capability == "calendar" -> "Outlook Calendar"
        provider == SourceType.NAVER_IMAP -> "Naver Mail"
        provider == SourceType.DAUM_IMAP -> "Daum Mail"
        else -> "$provider · $capability"
    }
