package com.becalm.android.domain.stt

import com.becalm.android.core.result.BecalmResult

/**
 * Routes STT requests through the available backends in priority order:
 * 1. [WhisperJni] (on-device, preferred for latency and privacy)
 * 2. [AndroidSpeechRecognizerBackend] (fallback)
 *
 * If Whisper succeeds, the Android backend is never called. If Whisper fails,
 * the selector falls back to the Android backend and returns its result
 * (success or failure).
 *
 * Spec: SP-34, KTR-STT-SELECTOR
 */
public class DefaultSttBackendSelector(
    private val whisper: WhisperJni,
    private val androidBackend: AndroidSpeechRecognizerBackend,
) {

    /**
     * Transcribes the audio file at [path] using the best available backend.
     */
    public suspend fun transcribeAudio(path: String): BecalmResult<String> {
        val whisperResult = whisper.transcribeAudio(path)
        if (whisperResult is BecalmResult.Success) return whisperResult
        return androidBackend.transcribeAudio(path)
    }
}
