package com.becalm.android.data.repository

import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.RawIngestionEventDto
import com.becalm.android.data.remote.dto.SourceEventParticipantInputDto
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Source-neutral adapter from local ingestion rows to the backend extraction contract.
 *
 * Source workers own source-specific fetching and storage. Before body interpretation or
 * give/take extraction, every source must be represented as this normalized shape:
 * event metadata, optional transient body, and participant hints.
 */
internal class SourceExtractionInputAdapter(
    private val emailBodyRepository: EmailBodyRepository? = null,
) {
    suspend fun toUploadDto(event: RawIngestionEventEntity): RawIngestionEventDto {
        return toNormalizedEvent(event).toDto()
    }

    suspend fun toRequestParts(
        event: RawIngestionEventEntity,
        rawEventId: String,
    ): SourceExtractionRequestParts {
        return toNormalizedEvent(event).toRequestParts(
            rawEventId = rawEventId,
        )
    }

    private suspend fun RawIngestionEventEntity.emailBodyForExtraction(): EmailBodyEntity? =
        emailBodyRepository?.getByRawEventId(id)

    private suspend fun toNormalizedEvent(event: RawIngestionEventEntity): NormalizedSourceEvent =
        NormalizedSourceEvent.from(
            rawEvent = event,
            emailBody = event.emailBodyForExtraction(),
        )
}

internal data class SourceExtractionRequestParts(
    val sourceType: RequestBody,
    val clientEventId: RequestBody,
    val rawEventId: RequestBody,
    val durationSeconds: RequestBody?,
    val timestamp: RequestBody,
    val counterpartyRef: RequestBody?,
    val eventTitle: RequestBody?,
    val folder: RequestBody?,
    val conversationRef: RequestBody?,
)

internal fun String.toPlainRequestBody(): RequestBody =
    toRequestBody("text/plain".toMediaTypeOrNull())

internal fun NormalizedSourceEvent.toRequestParts(
    rawEventId: String,
): SourceExtractionRequestParts {
    val dto = toDto()
    return SourceExtractionRequestParts(
        sourceType = dto.sourceType.toPlainRequestBody(),
        clientEventId = dto.clientEventId.toPlainRequestBody(),
        rawEventId = rawEventId.toPlainRequestBody(),
        durationSeconds = dto.durationSeconds?.toString()?.toPlainRequestBody(),
        timestamp = dto.timestamp.toString().toPlainRequestBody(),
        counterpartyRef = dto.counterpartyRef?.toPlainRequestBody(),
        eventTitle = dto.eventTitle?.toPlainRequestBody(),
        folder = dto.folder?.toPlainRequestBody(),
        conversationRef = dto.conversationRef?.toPlainRequestBody(),
    )
}

internal data class NormalizedSourceEvent(
    val rawEvent: RawIngestionEventEntity,
    val bodyPlainForExtraction: String?,
    val participants: List<SourceEventParticipantInputDto>,
    val emailHeaders: NormalizedEmailHeaders?,
    val emailHeaderHints: NormalizedEmailHeaderHints,
) {
    fun toDto(): RawIngestionEventDto =
        RawIngestionEventDto(
            id = rawEvent.id,
            clientEventId = rawEvent.clientEventId,
            userId = rawEvent.userId,
            sourceType = rawEvent.sourceType,
            sourceRef = rawEvent.sourceRef,
            messageIdHeader = emailHeaders?.messageIdHeader,
            inReplyToHeader = emailHeaders?.inReplyToHeader,
            referencesHeader = emailHeaders?.referencesHeader,
            conversationRef = rawEvent.conversationRef,
            counterpartyRef = rawEvent.counterpartyRef,
            participants = participants.takeIf { it.isNotEmpty() },
            eventTitle = rawEvent.eventTitle,
            eventSnippet = rawEvent.eventSnippet,
            durationSeconds = rawEvent.durationSeconds,
            location = rawEvent.location,
            folder = rawEvent.folder,
            commitmentsExtractedCount = rawEvent.commitmentsExtractedCount,
            emailBodyPlain = bodyPlainForExtraction,
            hasListUnsubscribe = emailHeaderHints.hasListUnsubscribe,
            hasListId = emailHeaderHints.hasListId,
            autoSubmitted = emailHeaderHints.autoSubmitted,
            bulkPrecedence = emailHeaderHints.bulkPrecedence,
            timestamp = rawEvent.timestamp,
        )

    companion object {
        fun from(
            rawEvent: RawIngestionEventEntity,
            emailBody: EmailBodyEntity?,
        ): NormalizedSourceEvent {
            if (emailBody == null) {
                return NormalizedSourceEvent(
                    rawEvent = rawEvent,
                    bodyPlainForExtraction = null,
                    participants = rawEvent.counterpartyRef?.let {
                        listOf(counterpartyParticipant(it, evidenceSource = "metadata"))
                    }.orEmpty(),
                    emailHeaders = rawEvent.emailHeaderContext(),
                    emailHeaderHints = NormalizedEmailHeaderHints.EMPTY,
                )
            }
            val headerHints = emailBody.rawHeaders.emailHeaderHints()

            val participants = emailParticipants(
                folder = emailBody.folder,
                fromAddress = emailBody.fromAddress,
                toAddressesJson = emailBody.toAddresses,
            )
            return NormalizedSourceEvent(
                rawEvent = rawEvent,
                bodyPlainForExtraction = emailBody.bodyTextForExtraction(),
                participants = participants,
                emailHeaders = rawEvent.emailHeaderContext(),
                emailHeaderHints = headerHints,
            )
        }

        private fun EmailBodyEntity.bodyTextForExtraction(): String? {
            if (parseFailed || groupEmail) return null
            bodyPlain.normalizedExtractionBody()?.let { return it }
            return bodyHtml
                ?.takeIf { it.isNotBlank() }
                ?.let { html ->
                    runCatching { Jsoup.parse(html).text() }.getOrNull()
                }
                .normalizedExtractionBody()
        }

        private fun String?.normalizedExtractionBody(): String? =
            this
                ?.replace(WHITESPACE_RUN, " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.take(MAX_EMAIL_BODY_CHARS)

        private fun RawIngestionEventEntity.emailHeaderContext(): NormalizedEmailHeaders? {
            val raw = sourceRef?.takeIf { it.isNotBlank() } ?: return null
            val parsed = runCatching {
                val json = JSONObject(raw)
                NormalizedEmailHeaders(
                    messageIdHeader = json.optNonBlankString("message_id"),
                    inReplyToHeader = json.optNonBlankString("in_reply_to"),
                    referencesHeader = json.optNonBlankString("references"),
                )
            }.getOrNull()
            return parsed?.takeIf {
                it.messageIdHeader != null || it.inReplyToHeader != null || it.referencesHeader != null
            } ?: NormalizedEmailHeaders(
                messageIdHeader = raw.jsonStringValue("message_id"),
                inReplyToHeader = raw.jsonStringValue("in_reply_to"),
                referencesHeader = raw.jsonStringValue("references"),
            ).takeIf {
                it.messageIdHeader != null || it.inReplyToHeader != null || it.referencesHeader != null
            }
        }

        private fun emailParticipants(
            folder: String?,
            fromAddress: String?,
            toAddressesJson: String?,
        ): List<SourceEventParticipantInputDto> {
            val normalizedFolder = folder?.lowercase()
            return buildList {
                canonicalEmail(fromAddress)?.let { sender ->
                    add(
                        emailParticipant(
                            role = "sender",
                            relationToUser = if (normalizedFolder == "sent") "self" else "counterparty",
                            email = sender,
                        ),
                    )
                }
                parseEmailArray(toAddressesJson).forEach { recipient ->
                    add(
                        emailParticipant(
                            role = "recipient",
                            relationToUser = if (normalizedFolder == "sent") "counterparty" else "self",
                            email = recipient,
                        ),
                    )
                }
            }.distinctBy { "${it.role}:${it.relationToUser}:${it.email}" }
        }

        private fun parseEmailArray(value: String?): List<String> {
            if (value.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(value)
                buildList {
                    for (index in 0 until array.length()) {
                        val candidate = when (val item = array.opt(index)) {
                            is JSONObject -> item.optString("email")
                            is String -> item
                            else -> null
                        }
                        canonicalEmail(candidate)?.let(::add)
                    }
                }
            }.getOrElse {
                EMAIL_REGEX.findAll(value).map { match -> match.value.lowercase() }.toList()
            }
        }

        private fun JSONObject.optNonBlankString(name: String): String? =
            optString(name, "").takeIf { it.isNotBlank() }

        private fun String.jsonStringValue(name: String): String? =
            Regex(""""$name"\s*:\s*"([^"]*)"""")
                .find(this)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }

        private fun String?.emailHeaderHints(): NormalizedEmailHeaderHints {
            if (isNullOrBlank()) return NormalizedEmailHeaderHints.EMPTY
            val lowered = lowercase()
            val parsed = runCatching {
                val json = JSONObject(this)
                val precedence = json.optString("precedence", "").trim().lowercase()
                val autoSubmitted = json.optString("auto-submitted", "").trim().lowercase()
                NormalizedEmailHeaderHints(
                    hasListUnsubscribe = json.optNonBlankString("list-unsubscribe") != null,
                    hasListId = json.optNonBlankString("list-id") != null,
                    autoSubmitted = autoSubmitted.isNotBlank() && autoSubmitted != "no",
                    bulkPrecedence = precedence == "bulk" || precedence == "list" || precedence == "junk",
                )
            }.getOrNull()
            return NormalizedEmailHeaderHints(
                hasListUnsubscribe = parsed?.hasListUnsubscribe ?: lowered.contains("list-unsubscribe"),
                hasListId = parsed?.hasListId ?: lowered.contains("list-id"),
                autoSubmitted = parsed?.autoSubmitted
                    ?: (lowered.contains("auto-submitted") && !lowered.contains("\"auto-submitted\":\"no\"")),
                bulkPrecedence = parsed?.bulkPrecedence
                    ?: (lowered.contains("precedence") && (
                        lowered.contains("bulk") || lowered.contains("list") || lowered.contains("junk")
                    )),
            )
        }

        private fun emailParticipant(
            role: String,
            relationToUser: String,
            email: String,
        ): SourceEventParticipantInputDto =
            SourceEventParticipantInputDto(
                role = role,
                relationToUser = relationToUser,
                identityType = "email",
                rawValue = email,
                normalizedValue = email,
                email = email,
                evidence = email,
                confidence = 0.95,
                evidenceSource = "metadata",
            )

        private fun counterpartyParticipant(value: String, evidenceSource: String): SourceEventParticipantInputDto {
            val email = canonicalEmail(value)
            val phone = value.takeIf { it.startsWith("+") && it.drop(1).all(Char::isDigit) }
            return SourceEventParticipantInputDto(
                role = "counterparty",
                relationToUser = "counterparty",
                identityType = when {
                    email != null -> "email"
                    phone != null -> "phone"
                    else -> "name"
                },
                rawValue = value,
                normalizedValue = email ?: phone ?: value.trim().lowercase(),
                displayName = if (email == null && phone == null) value else null,
                email = email,
                phone = phone,
                evidence = value,
                confidence = if (email != null || phone != null) 0.85 else 0.5,
                evidenceSource = evidenceSource,
            )
        }

        private fun canonicalEmail(value: String?): String? {
            val text = value?.trim()?.lowercase() ?: return null
            if (!text.contains("@")) return null
            return text
                .substringAfter("<", text)
                .substringBefore(">")
                .takeIf { it.contains("@") && it.contains(".") }
        }

        private val EMAIL_REGEX = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
        private val WHITESPACE_RUN = Regex("\\s+")
        private const val MAX_EMAIL_BODY_CHARS = 16_000
    }
}

internal data class NormalizedEmailHeaders(
    val messageIdHeader: String?,
    val inReplyToHeader: String?,
    val referencesHeader: String?,
)

internal data class NormalizedEmailHeaderHints(
    val hasListUnsubscribe: Boolean = false,
    val hasListId: Boolean = false,
    val autoSubmitted: Boolean = false,
    val bulkPrecedence: Boolean = false,
) {
    companion object {
        val EMPTY = NormalizedEmailHeaderHints()
    }
}

internal fun RawIngestionEventDto.toRawIngestionEventEntity(userId: String): RawIngestionEventEntity =
    RawIngestionEventEntity(
        id = id ?: UUID.nameUUIDFromBytes("$userId:$sourceType:$clientEventId".toByteArray()).toString(),
        userId = userId,
        clientEventId = clientEventId,
        sourceType = sourceType,
        sourceRef = sourceRef,
        conversationRef = conversationRef,
        counterpartyRef = counterpartyRef,
        eventTitle = eventTitle,
        eventSnippet = eventSnippet,
        durationSeconds = durationSeconds,
        location = location,
        folder = folder,
        commitmentsExtractedCount = commitmentsExtractedCount ?: 0,
        timestamp = timestamp,
        syncStatus = "synced",
    )
