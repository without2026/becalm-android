package com.becalm.android.ui.onboarding

import androidx.annotation.StringRes
import com.becalm.android.R
import com.becalm.android.data.remote.dto.SourceType

/**
 * Preset IMAP endpoint recognised by the onboarding provider selector (S6-H).
 *
 * The sealed hierarchy is the single source of truth for host/port pairs the UI
 * offers — worker-side code reads the runtime value saved in
 * [com.becalm.android.data.local.secure.ImapCredentialStore] and never re-reads the
 * preset. Adding a new provider means adding an entry here plus matching strings;
 * widening the surface to a freeform "direct entry" option is out of scope for
 * alpha and is tracked as a follow-up.
 */
public sealed class ImapProvider(
    public val sourceType: String,
    public val host: String,
    public val port: Int,
    @StringRes public val displayNameRes: Int,
    @StringRes public val usernamePlaceholderRes: Int,
) {

    /** Naver Mail (naver.com). */
    public data object Naver : ImapProvider(
        sourceType = SourceType.NAVER_IMAP,
        host = "imap.naver.com",
        port = PORT_IMAPS,
        displayNameRes = R.string.onb_imap_provider_naver,
        usernamePlaceholderRes = R.string.onb_imap_username_hint_naver,
    )

    /** Daum Mail (daum.net / hanmail.net). */
    public data object Daum : ImapProvider(
        sourceType = SourceType.DAUM_IMAP,
        host = "imap.daum.net",
        port = PORT_IMAPS,
        displayNameRes = R.string.onb_imap_provider_daum,
        usernamePlaceholderRes = R.string.onb_imap_username_hint_daum,
    )

    public companion object {
        /** Implicit-TLS IMAPS port used by every currently-enumerated Korean provider. */
        public const val PORT_IMAPS: Int = 993

        /** Ordered selector list — rendered left-to-right in the segmented control. */
        public val SELECTABLE: List<ImapProvider> = listOf(Naver, Daum)
    }
}
