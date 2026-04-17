package com.becalm.android.domain.stt

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult

/**
 * Thin JNI wrapper around the Whisper C++ library for on-device STT.
 *
 * In the current MVP the native `.so` is not bundled, so [isLoaded] is always `false`
 * and [transcribeAudio] returns a structured [BecalmResult.Failure]. This stub exists
 * so that [DefaultSttBackendSelector] can compile and tests can verify the fallback
 * contract (SP-34).
 *
 * Spec: SP-34, KTR-WHISPER-JNI
 */
public class WhisperJni {

    /** Whether `libwhisper.so` was successfully loaded. Always `false` in MVP. */
    public val isLoaded: Boolean = false

    /**
     * Transcribes the audio file at [path] using the Whisper model.
     *
     * @return [BecalmResult.Failure] wrapping [BecalmError.Unknown] with an
     *   [UnsupportedOperationException] while the native library is absent (SP-34b stub).
     */
    public suspend fun transcribeAudio(path: String): BecalmResult<String> {
        if (!isLoaded) {
            return BecalmResult.Failure(
                BecalmError.Unknown(
                    UnsupportedOperationException("SP-34b: Whisper native lib not loaded"),
                ),
            )
        }
        // Real JNI call would go here once libwhisper.so is bundled.
        return BecalmResult.Failure(
            BecalmError.Unknown(UnsupportedOperationException("SP-34b: not implemented")),
        )
    }
}
