package com.becalm.android.domain.meeting

import com.becalm.android.data.remote.dto.MeetingSpeakerPreviewDto
import com.squareup.moshi.JsonWriter
import okio.Buffer

public object MeetingSpeakerMappingsJson {
    public fun encode(
        speakers: List<MeetingSpeakerPreviewDto>,
        selfSpeakerId: String,
    ): String = encodeMeetingSelf(speakers, selfSpeakerId)

    public fun encodeMeetingSelf(
        speakers: List<MeetingSpeakerPreviewDto>,
        selfSpeakerId: String,
    ): String {
        val buffer = Buffer()
        JsonWriter.of(buffer).use { writer ->
            writer.beginArray()
            speakers.forEach { speaker ->
                val isSelf = speaker.speakerId == selfSpeakerId
                writer.beginObject()
                writer.name("speaker_id").value(speaker.speakerId)
                writer.name("display_name").value(speaker.speakerId)
                writer.name("relation_to_user").value(if (isSelf) "self" else "participant")
                writer.name("confidence").value(0.0)
                writer.name("confirmed_by_user").value(isSelf)
                writer.endObject()
            }
            writer.endArray()
        }
        return buffer.readUtf8()
    }

    public fun encodeCallCounterparty(
        speakers: List<MeetingSpeakerPreviewDto>,
        counterpartySpeakerId: String,
    ): String {
        val selfSpeakerId = speakers.firstOrNull { it.speakerId != counterpartySpeakerId }?.speakerId
            ?: counterpartySpeakerId
        return encodeCallSelfAndCounterparty(
            speakers = speakers,
            selfSpeakerId = selfSpeakerId,
            counterpartySpeakerId = counterpartySpeakerId,
        )
    }

    public fun encodeCallSelfAndCounterparty(
        speakers: List<MeetingSpeakerPreviewDto>,
        selfSpeakerId: String,
        counterpartySpeakerId: String,
    ): String {
        val buffer = Buffer()
        JsonWriter.of(buffer).use { writer ->
            writer.beginArray()
            speakers.forEach { speaker ->
                val isCounterparty = speaker.speakerId == counterpartySpeakerId
                val isSelf = speaker.speakerId == selfSpeakerId
                writer.beginObject()
                writer.name("speaker_id").value(speaker.speakerId)
                writer.name("display_name").value(speaker.speakerId)
                writer.name("relation_to_user").value(
                    when {
                        isSelf -> "self"
                        isCounterparty -> "counterparty"
                        else -> "participant"
                    },
                )
                writer.name("confidence").value(0.0)
                writer.name("confirmed_by_user").value(isSelf || isCounterparty)
                writer.endObject()
            }
            writer.endArray()
        }
        return buffer.readUtf8()
    }
}
