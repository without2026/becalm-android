package com.becalm.android.domain.stt

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SttBackendSelectorTest {

    private val whisper: WhisperJni = mockk()
    private val androidBackend: AndroidSpeechRecognizerBackend = mockk()
    private val selector = DefaultSttBackendSelector(whisper, androidBackend)

    private val testPath = "/data/user/0/com.becalm.android/files/audio_test.pcm"

    /**
     * When Whisper fails, selector falls back to AndroidSpeechRecognizerBackend.
     * If AndroidSpeechRecognizerBackend succeeds, the Success is returned.
     */
    @Test
    fun `whisper fails android succeeds returns android Success`() = runTest {
        val whisperError = BecalmResult.Failure(BecalmError.Unknown(RuntimeException("Whisper native error")))
        val androidSuccess = BecalmResult.Success("hello world")
        coEvery { whisper.transcribeAudio(testPath) } returns whisperError
        coEvery { androidBackend.transcribeAudio(testPath) } returns androidSuccess

        val result = selector.transcribeAudio(testPath)

        assertTrue(result is BecalmResult.Success)
        assertEquals("hello world", (result as BecalmResult.Success).value)
        coVerify(exactly = 1) { whisper.transcribeAudio(testPath) }
        coVerify(exactly = 1) { androidBackend.transcribeAudio(testPath) }
    }

    /**
     * When both Whisper and AndroidSpeechRecognizerBackend fail, selector returns
     * the android backend's Failure (not Whisper's).
     */
    @Test
    fun `both backends fail returns android Failure`() = runTest {
        val whisperError = BecalmResult.Failure(BecalmError.Unknown(RuntimeException("Whisper native error")))
        val androidError = BecalmResult.Failure(BecalmError.Unknown(RuntimeException("SpeechRecognizer error code: 7")))
        coEvery { whisper.transcribeAudio(testPath) } returns whisperError
        coEvery { androidBackend.transcribeAudio(testPath) } returns androidError

        val result = selector.transcribeAudio(testPath)

        assertTrue(result is BecalmResult.Failure)
        val error = (result as BecalmResult.Failure).error
        assertTrue(error is BecalmError.Unknown)
        assertTrue((error as BecalmError.Unknown).throwable.message!!.contains("SpeechRecognizer"))
        coVerify(exactly = 1) { whisper.transcribeAudio(testPath) }
        coVerify(exactly = 1) { androidBackend.transcribeAudio(testPath) }
    }

    /**
     * When Whisper succeeds, AndroidSpeechRecognizerBackend is never called.
     */
    @Test
    fun `whisper succeeds android backend never called`() = runTest {
        val whisperSuccess = BecalmResult.Success("transcribed by whisper")
        coEvery { whisper.transcribeAudio(testPath) } returns whisperSuccess

        val result = selector.transcribeAudio(testPath)

        assertTrue(result is BecalmResult.Success)
        assertEquals("transcribed by whisper", (result as BecalmResult.Success).value)
        coVerify(exactly = 1) { whisper.transcribeAudio(testPath) }
        coVerify(exactly = 0) { androidBackend.transcribeAudio(any()) }
    }
}
