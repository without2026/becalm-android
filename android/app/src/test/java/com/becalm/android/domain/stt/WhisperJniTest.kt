package com.becalm.android.domain.stt

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [WhisperJni] (SP-34, KTR-WHISPER-JNI).
 *
 * In the test environment `libwhisper.so` is absent, so [WhisperJni.isLoaded] is always
 * `false`. These tests verify the failure-on-no-lib contract:
 * 1. [transcribeAudio] returns [BecalmResult.Failure].
 * 2. The failure wraps [BecalmError.Unknown].
 * 3. The [BecalmError.Unknown.throwable] is an [UnsupportedOperationException].
 * 4. The exception message contains the expected stub marker "SP-34b".
 */
@RunWith(RobolectricTestRunner::class)
class WhisperJniTest {

    // ── SP-34-T1: failure when native lib absent ──────────────────────────────

    @Test
    fun `transcribeAudio returns Failure when libwhisper is not loaded`() = runTest {
        val jni = WhisperJni()

        val result = jni.transcribeAudio("/sdcard/recording.wav")

        assertTrue(
            "Expected BecalmResult.Failure but got $result",
            result is BecalmResult.Failure,
        )
    }

    // ── SP-34-T2: failure wraps BecalmError.Unknown ───────────────────────────

    @Test
    fun `transcribeAudio failure error is BecalmError Unknown`() = runTest {
        val jni = WhisperJni()

        val result = jni.transcribeAudio("/sdcard/recording.wav") as BecalmResult.Failure

        assertTrue(
            "Expected BecalmError.Unknown but got ${result.error::class.simpleName}",
            result.error is BecalmError.Unknown,
        )
    }

    // ── SP-34-T3: cause is UnsupportedOperationException ─────────────────────

    @Test
    fun `transcribeAudio Unknown error throwable is UnsupportedOperationException`() = runTest {
        val jni = WhisperJni()

        val result = jni.transcribeAudio("/sdcard/recording.wav") as BecalmResult.Failure
        val error = result.error as BecalmError.Unknown

        assertTrue(
            "Expected UnsupportedOperationException but got ${error.throwable::class.simpleName}",
            error.throwable is UnsupportedOperationException,
        )
    }

    // ── SP-34-T4: exception message contains stub marker ─────────────────────

    @Test
    fun `transcribeAudio exception message contains SP-34b stub marker`() = runTest {
        val jni = WhisperJni()

        val result = jni.transcribeAudio("/sdcard/recording.wav") as BecalmResult.Failure
        val error = result.error as BecalmError.Unknown

        assertNotNull(error.throwable.message)
        assertTrue(
            "Expected message to contain 'SP-34b' but was: ${error.throwable.message}",
            error.throwable.message!!.contains("SP-34b"),
        )
    }

    // ── SP-34-T5: contract holds for arbitrary path inputs ────────────────────

    @Test
    fun `transcribeAudio returns structured Failure for empty path`() = runTest {
        val jni = WhisperJni()

        val result = jni.transcribeAudio("")

        assertTrue(result is BecalmResult.Failure)
        assertEquals(
            UnsupportedOperationException::class,
            (result as BecalmResult.Failure).let { (it.error as BecalmError.Unknown).throwable::class },
        )
    }
}
