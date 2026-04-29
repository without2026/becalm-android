package com.becalm.android.domain.person

import java.util.Locale
import java.util.UUID

public data class PersonIdentityResolution(
    val personId: String,
    val identityKey: String,
    val identityType: String,
    val rawValue: String,
    val displayNameHint: String?,
    val confidence: Double,
    val verified: Boolean,
)

public object PersonIdentityResolver {
    private val EMAIL_REGEX = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    private val PHONE_CHARS = Regex("[^0-9+]")
    private val AUTOMATED_EMAIL_LOCALS = setOf(
        "noreply",
        "no-reply",
        "do-not-reply",
        "donotreply",
        "mailer-daemon",
        "postmaster",
        "notification",
        "notifications",
        "calendar-notification",
        "automated",
        "bot",
    )

    public fun resolve(userId: String, raw: String?): PersonIdentityResolution? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val email = EMAIL_REGEX.find(value)?.value?.lowercase(Locale.ROOT)
        if (email != null) {
            return resolution(userId, identityKey = "email:$email", identityType = "email", rawValue = email)
        }

        val phone = value.replace(PHONE_CHARS, "")
            .takeIf { it.length >= 7 && it.any(Char::isDigit) }
        if (phone != null) {
            return resolution(userId, identityKey = "phone:$phone", identityType = "phone", rawValue = phone)
        }

        val normalizedName = value
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.length >= 2 }
            ?: return null
        val keyName = normalizedName.replace(Regex("[^a-z0-9가-힣 ]"), "").replace(" ", "-")
        if (keyName.isBlank()) return null
        return resolution(
            userId = userId,
            identityKey = "name:$keyName",
            identityType = "name",
            rawValue = value,
            displayNameHint = value,
            confidence = 0.45,
            verified = false,
        )
    }

    public fun resolveIdentityKey(
        userId: String,
        identityKey: String?,
        rawValue: String?,
        displayNameHint: String?,
        confidence: Double = 1.0,
        verified: Boolean = true,
    ): PersonIdentityResolution? {
        val key = identityKey?.trim()?.takeIf { it.contains(':') } ?: return null
        val type = key.substringBefore(':').takeIf { it.isNotBlank() } ?: return null
        val value = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: key.substringAfter(':')
        return resolution(
            userId = userId,
            identityKey = key,
            identityType = type,
            rawValue = value,
            displayNameHint = displayNameHint?.trim()?.takeIf { it.isNotEmpty() } ?: value,
            confidence = confidence,
            verified = verified,
        )
    }

    public fun normalizeAlias(value: String?): String? =
        value
            ?.lowercase(Locale.ROOT)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.length >= 2 }

    public fun isLikelyAutomated(raw: String?): Boolean {
        val value = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return false
        val email = EMAIL_REGEX.find(value)?.value?.lowercase(Locale.ROOT)
        val local = email?.substringBefore('@')
            ?.replace(".", "-")
            ?.replace("_", "-")
        return local in AUTOMATED_EMAIL_LOCALS || AUTOMATED_EMAIL_LOCALS.any { marker ->
            local?.contains(marker) == true
        }
    }

    private fun resolution(
        userId: String,
        identityKey: String,
        identityType: String,
        rawValue: String,
        displayNameHint: String? = rawValue,
        confidence: Double = 0.95,
        verified: Boolean = true,
    ): PersonIdentityResolution {
        val personId = UUID.nameUUIDFromBytes("person:$userId:$identityKey".toByteArray(Charsets.UTF_8)).toString()
        return PersonIdentityResolution(
            personId = personId,
            identityKey = identityKey,
            identityType = identityType,
            rawValue = rawValue,
            displayNameHint = displayNameHint,
            confidence = confidence,
            verified = verified,
        )
    }
}
