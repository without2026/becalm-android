package com.becalm.android.ui.evidence

import com.becalm.android.data.remote.dto.MeetingSpeakerPreviewDto
import com.squareup.moshi.JsonWriter
import okio.Buffer

public object MeetingSpeakerMappingsJson {
    public fun encode(
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
}
