package com.becalm.android.unit.worker

import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.worker.ingestion.AudioMediaStoreDecision
import com.becalm.android.worker.ingestion.AudioMediaStoreIngestionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMediaStoreIngestionPolicySpecTest {

    @Test
    fun `voice policy accepts known recorder-owned paths`() {
        assertEquals(
            AudioMediaStoreDecision.Process,
            AudioMediaStoreIngestionPolicy.classify(
                sourceType = SourceType.VOICE,
                path = "Recordings/",
                displayName = "Voice 001.m4a",
                durationSec = 30,
                isPending = false,
            ),
        )
        assertEquals(
            AudioMediaStoreDecision.Process,
            AudioMediaStoreIngestionPolicy.classify(
                sourceType = SourceType.VOICE,
                path = "Recordings/Voice Recorder/",
                displayName = "Voice 002.m4a",
                durationSec = 30,
                isPending = false,
            ),
        )
        assertEquals(
            AudioMediaStoreDecision.Process,
            AudioMediaStoreIngestionPolicy.classify(
                sourceType = SourceType.VOICE,
                path = "/storage/emulated/0/Recordings/direct-note.m4a",
                displayName = "direct-note.m4a",
                durationSec = 30,
                isPending = false,
            ),
        )
    }

    @Test
    fun `voice policy rejects unsupported app subfolders under Recordings`() {
        val decision = AudioMediaStoreIngestionPolicy.classify(
            sourceType = SourceType.VOICE,
            path = "Recordings/Other Recorder/",
            displayName = "foreign-note.m4a",
            durationSec = 30,
            isPending = false,
        )

        assertEquals(AudioMediaStoreDecision.Ignore("unsupported_voice_path"), decision)
    }

    @Test
    fun `pending rows are deferred so cursors do not skip them`() {
        val decision = AudioMediaStoreIngestionPolicy.classify(
            sourceType = SourceType.CALL_RECORDING,
            path = "Recordings/Call/",
            displayName = "call.m4a",
            durationSec = 30,
            isPending = true,
        )

        assertTrue(decision is AudioMediaStoreDecision.Defer)
    }

    @Test
    fun `short transient audio is ignored instead of uploaded`() {
        assertEquals(
            AudioMediaStoreDecision.Ignore("audio_too_short"),
            AudioMediaStoreIngestionPolicy.classify(
                sourceType = SourceType.MEETING,
                path = "Recordings/BeCalm Meetings/Audio/",
                displayName = "tap.m4a",
                durationSec = 1,
                isPending = false,
            ),
        )
        assertEquals(
            AudioMediaStoreDecision.Ignore("transient_file"),
            AudioMediaStoreIngestionPolicy.classify(
                sourceType = SourceType.VOICE,
                path = "Recordings/",
                displayName = "recording.tmp",
                durationSec = 30,
                isPending = false,
            ),
        )
    }
}
