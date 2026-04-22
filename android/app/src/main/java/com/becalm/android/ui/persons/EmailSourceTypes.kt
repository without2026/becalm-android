package com.becalm.android.ui.persons

import com.becalm.android.data.remote.dto.SourceType

/**
 * Set of `source_type` values that are rendered via the email-specific branch of
 * [RawEventDetailSheet] / [RawEventDetailViewModel].
 *
 * Sharing this single constant between the ViewModel (which decides whether to JOIN
 * `email_body`) and the Composable (which decides which detail layout to inflate)
 * keeps both sides from drifting when a new email provider is added — a class of
 * bug where the VM would load an email body that the UI would never render (or
 * vice versa).
 *
 * Spec refs: SRC-004 (`.spec/source-viewer.spec.yml:37-45`), EMAIL-001..007.
 */
internal val EMAIL_SOURCE_TYPES: Set<String> = setOf(
    SourceType.GMAIL,
    SourceType.OUTLOOK_MAIL,
    SourceType.NAVER_IMAP,
    SourceType.DAUM_IMAP,
)
