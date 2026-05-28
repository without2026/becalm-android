package com.becalm.android.unit.ui.evidence

import com.becalm.android.data.remote.dto.MeetingSpeakerPreviewDto
import com.becalm.android.domain.meeting.MeetingSpeakerMappingsJson
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

    @Test
    fun `encodes call speaker mapping with selected self and counterparty confirmed`() {
        val json = MeetingSpeakerMappingsJson.encodeCallSelfAndCounterparty(
            speakers = listOf(
                speaker("SPEAKER_01"),
                speaker("SPEAKER_02"),
                speaker("SPEAKER_03"),
            ),
            selfSpeakerId = "SPEAKER_01",
            counterpartySpeakerId = "SPEAKER_03",
        )

        val rows = parseRows(json)

        assertEquals("self", rows[0]["relation_to_user"])
        assertEquals(true, rows[0]["confirmed_by_user"])
        assertEquals("participant", rows[1]["relation_to_user"])
        assertEquals(false, rows[1]["confirmed_by_user"])
        assertEquals("counterparty", rows[2]["relation_to_user"])
        assertEquals(true, rows[2]["confirmed_by_user"])
    }

    @Test
    fun `legacy call counterparty encoder still infers remaining speaker as self`() {
        val json = MeetingSpeakerMappingsJson.encodeCallCounterparty(
            speakers = listOf(
                speaker("SPEAKER_01"),
                speaker("SPEAKER_02"),
            ),
            counterpartySpeakerId = "SPEAKER_02",
        )

        val rows = parseRows(json)

        assertEquals("self", rows[0]["relation_to_user"])
        assertEquals(true, rows[0]["confirmed_by_user"])
        assertEquals("counterparty", rows[1]["relation_to_user"])
        assertEquals(true, rows[1]["confirmed_by_user"])
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
