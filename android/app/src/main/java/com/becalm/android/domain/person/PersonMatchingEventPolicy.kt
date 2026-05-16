package com.becalm.android.domain.person

import java.util.Locale

public object PersonMatchingEventPolicy {
    public fun isLikelyServiceAccountNotification(
        title: String?,
        snippet: String?,
        suggestedLabel: String? = null,
    ): Boolean {
        val text = listOfNotNull(title, snippet, suggestedLabel)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        if (text.isBlank()) return false

        val hasServiceContext = SERVICE_CONTEXT_MARKERS.any { it in text }
        val hasAccountAction = ACCOUNT_ACTION_MARKERS.any { it in text }
        val hasVerificationPhrase = VERIFICATION_MARKERS.any { it in text }
        return hasServiceContext && (hasAccountAction || hasVerificationPhrase)
    }

    private val SERVICE_CONTEXT_MARKERS = setOf(
        "slack",
        "workspace",
        "워크스페이스",
        "google",
        "notion",
        "perplexity",
        "airbnb",
        "flow",
        "플로우",
        "account",
        "계정",
        "service",
        "서비스",
    )

    private val ACCOUNT_ACTION_MARKERS = setOf(
        "verify",
        "verification",
        "confirm",
        "confirmation",
        "authenticate",
        "authentication",
        "sign in",
        "login",
        "log in",
        "join",
        "invite",
        "invited",
        "permission",
        "permissions",
        "settings",
        "이메일 주소를 확인",
        "이메일을 확인",
        "인증",
        "로그인",
        "가입",
        "초대",
        "권한",
        "설정",
    )

    private val VERIFICATION_MARKERS = setOf(
        "verify your email",
        "verify email",
        "confirm your email",
        "confirm email address",
        "email address verification",
        "verification code",
        "authentication code",
        "이메일 주소를 확인",
        "이메일 주소 확인",
        "인증 코드",
    )
}
