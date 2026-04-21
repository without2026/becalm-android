package com.becalm.android.data.remote.imap

/**
 * Per-provider mailbox name denylist for IMAP folder discovery.
 *
 * ING-008 (`.spec/data-ingestion.spec.yml:78-85`) restricts Naver IMAP indexing to
 * `INBOX` + `보낸메일함` and explicitly excludes `임시보관함`, `스팸메일함`, `휴지통`,
 * `전체메일`. Daum has the equivalent set — the names below cover the common
 * locale, but some tenant configurations surface variants (e.g. `스팸함` vs
 * `스팸메일`, `발송함` vs `보낸 메일`).
 *
 * ## TODO — real-device verification
 * These sets are the plan's initial best-guess. A follow-up session must connect to
 * real Naver and Daum accounts (`telnet imap.naver.com 993` → `A1 LIST "" "*"`) and
 * fold any missing folder names into the appropriate set. The intent is strict —
 * folders not in this list but also lacking `\Inbox` / `\Sent` SPECIAL-USE flags
 * will simply be ignored by the worker discovery phase, so a missed denylist entry
 * does not leak contents; however, a user-created folder that a denylist entry
 * matches by coincidence would be silently dropped — hence the verification ask.
 *
 * Spec refs: ING-008 (`.spec/data-ingestion.spec.yml:78-85`).
 */
public object ImapProviderDenylist {

    /**
     * Naver Mail folders that MUST NOT be indexed. Precedence: any folder whose raw
     * `name` equals one of these strings is filtered out in the worker's folder
     * discovery pass regardless of its SPECIAL-USE flag (if any).
     */
    public val NAVER: Set<String> = setOf(
        "임시보관함",
        "스팸메일함",
        "휴지통",
        "전체메일",
    )

    /**
     * Daum Mail folders that MUST NOT be indexed. See the object-level KDoc for the
     * verification caveat — these names are the plan's initial best-guess and need
     * real-device confirmation before a production-grade guarantee.
     */
    public val DAUM: Set<String> = setOf(
        "임시보관함",
        "스팸함",
        "휴지통",
        "모든메일",
    )
}
