package com.becalm.android.domain.stt

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import javax.inject.Inject

/**
 * JNI scaffold for on-device Whisper speech-to-text transcription (SP-34, KTR-WHISPER-JNI).
 *
 * ## Status: native library not yet linked
 * The native shared library `libwhisper.so` is built in SP-34b (C++ source deferred).
 * Until SP-34b lands, [System.loadLibrary] will throw [UnsatisfiedLinkError] at runtime.
 * This class catches that error at class-load time, records [isLoaded] = `false`, and
 * returns a structured [BecalmResult.Failure] from [transcribeAudio] so callers degrade
 * gracefully rather than crashing.
 *
 * ## Native entry points (TODO KTR-WHISPER-JNI)
 * The [external] declarations below define the JNI function signatures that `libwhisper.so`
 * must implement in SP-34b:
 * - `nativeTranscribe(path: String): String` — accepts a WAV/OGG file path and returns the
 *   transcription as a UTF-8 string. Must be thread-safe; will be called from
 *   [kotlinx.coroutines.Dispatchers.IO].
 *
 * ## Threading
 * [transcribeAudio] is a suspend function and is safe to call from any coroutine context.
 * When the native library is linked it is the caller's responsibility to dispatch to
 * [kotlinx.coroutines.Dispatchers.IO] to avoid blocking the main thread during inference.
 *
 * ## Integration path (SP-34b)
 * 1. Add `android/app/src/main/cpp/CMakeLists.txt` and the whisper.cpp sources.
 * 2. Set `isLoaded` validation to non-try-catch path once `libwhisper` is unconditionally
 *    linked.
 * 3. Replace the [transcribeAudio] stub body with a `withContext(Dispatchers.IO)` call to
 *    [nativeTranscribe].
 */
public class WhisperJni @Inject constructor() {

    /**
     * `true` when `libwhisper` was successfully loaded via [System.loadLibrary]; `false`
     * when the library is absent (SP-34 stub mode). Checked by [transcribeAudio] before
     * attempting any native call.
     */
    private val isLoaded: Boolean

    init {
        isLoaded = try {
            System.loadLibrary("whisper")
            true
        } catch (_: UnsatisfiedLinkError) {
            // Library is absent until SP-34b (C++ sources deferred).
            // Swallow the error so the rest of the pipeline can initialise normally.
            false
        }
    }

    // ─── External declarations ────────────────────────────────────────────────

    /**
     * Calls the native `nativeTranscribe` function implemented in `libwhisper.so`.
     *
     * Only callable when [isLoaded] is `true`. Must be dispatched on [kotlinx.coroutines.Dispatchers.IO].
     *
     * @param path Absolute file-system path to the audio file (WAV or OGG format).
     * @return Raw transcription string.
     * @throws [UnsatisfiedLinkError] if called when the library is not loaded (guard
     *   with [isLoaded] before calling).
     */
    private external fun nativeTranscribe(path: String): String

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Transcribes the audio file at [path] using the Whisper on-device model.
     *
     * When [isLoaded] is `false` (native library absent, SP-34 stub mode), returns a
     * [BecalmResult.Failure] wrapping [BecalmError.Unknown] with an [UnsupportedOperationException]
     * cause so callers can surface a structured error without a crash.
     *
     * When [isLoaded] is `true` (SP-34b and beyond), delegates to [nativeTranscribe].
     * TODO(KTR-WHISPER-JNI): Replace stub body with `withContext(Dispatchers.IO) { nativeTranscribe(path) }`
     * once SP-34b lands.
     *
     * @param path Absolute file-system path to the audio file to transcribe.
     * @return [BecalmResult.Success] with the transcription string, or [BecalmResult.Failure]
     *   when the native library is absent or transcription fails.
     */
    public suspend fun transcribeAudio(path: String): BecalmResult<String> {
        if (!isLoaded) {
            return BecalmResult.Failure(
                BecalmError.Unknown(
                    UnsupportedOperationException(
                        "whisper native not linked yet — SP-34b",
                    ),
                ),
            )
        }
        // TODO(KTR-WHISPER-JNI): dispatch to Dispatchers.IO and call nativeTranscribe(path)
        return BecalmResult.Failure(
            BecalmError.Unknown(
                UnsupportedOperationException(
                    "whisper native not linked yet — SP-34b",
                ),
            ),
        )
    }
}
