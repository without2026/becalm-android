package com.becalm.android.ui.persons

private val GenericMailboxNames = setOf(
    "admin",
    "alert",
    "alerts",
    "contact",
    "hello",
    "help",
    "info",
    "mail",
    "mailer",
    "newsletter",
    "no-reply",
    "noreply",
    "notification",
    "notifications",
    "postmaster",
    "support",
)

internal fun personDisplayLabel(
    personRef: String,
    displayName: String? = null,
    nickname: String? = null,
): String = displayName.cleanDisplayPart()
    ?: nickname.cleanDisplayPart()
    ?: personRef.emailDisplayLabel()
    ?: personRef.trim().ifEmpty { personRef }

private fun String?.cleanDisplayPart(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)

private fun String.emailDisplayLabel(): String? {
    val trimmed = trim()
    val atIndex = trimmed.indexOf('@')
    if (atIndex <= 0 || atIndex == trimmed.lastIndex) return null

    val localPart = trimmed.substring(0, atIndex).lowercase()
    val domain = trimmed.substring(atIndex + 1).lowercase()
    val brand = domainBrandLabel(domain)
    if (brand != null) return brand

    val localLabel = localPart
        .replace('.', ' ')
        .replace('_', ' ')
        .replace('-', ' ')
        .splitToSequence(' ')
        .filter(String::isNotBlank)
        .joinToString(separator = " ") { token -> token.replaceFirstChar(Char::uppercaseChar) }

    if (localLabel.isNotBlank() && localPart !in GenericMailboxNames) return localLabel
    return domain.substringBefore('.').replaceFirstChar(Char::uppercaseChar)
}

private fun domainBrandLabel(domain: String): String? = when {
    domain == "navercorp.com" || domain.endsWith(".navercorp.com") -> "네이버"
    domain == "naver.com" || domain.endsWith(".naver.com") -> "네이버"
    domain == "daum.net" || domain.endsWith(".daum.net") -> "다음"
    domain == "kakao.com" || domain.endsWith(".kakao.com") -> "카카오"
    else -> null
}
