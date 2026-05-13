package com.becalm.android.unit.ui.evidence

import com.becalm.android.data.remote.dto.MeetingSpeakerPreviewDto
import com.becalm.android.ui.evidence.MeetingSpeakerMappingsJson
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MeetingSpeakerMappingsJsonSpecTest {

    @Test
    fun `encodes meeting speaker mappings as escaped valid json`() {
        val json = MeetingSpeakerMappingsJson.encode(
            speakers = listOf(
                speaker("""SPEAKER_"02\민홍"""),
                speaker("SPEAKER_03"),
            ),
            selfSpeakerId = """SPEAKER_"02\민홍""",
        )

        val rows = parseRows(json)

        assertEquals(2, rows.size)
        assertEquals("""SPEAKER_"02\민홍""", rows[0]["speaker_id"])
        assertEquals("""SPEAKER_"02\민홍""", rows[0]["display_name"])
        assertEquals("self", rows[0]["relation_to_user"])
        assertEquals(true, rows[0]["confirmed_by_user"])
        assertEquals(0.0, rows[0]["confidence"])
        assertEquals("participant", rows[1]["relation_to_user"])
        assertEquals(false, rows[1]["confirmed_by_user"])
    }

    private fun speaker(id: String): MeetingSpeakerPreviewDto =
        MeetingSpeakerPreviewDto(speakerId = id)

    private fun parseRows(json: String): List<Map<String, Any?>> {
        val listType = Types.newParameterizedType(
            List::class.java,
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
        )
        val rows = Moshi.Builder().build().adapter<List<Map<String, Any?>>>(listType).fromJson(json)
        assertNotNull(rows)
        return rows.orEmpty()
    }
}
