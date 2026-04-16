package com.becalm.android.domain.stt

import com.becalm.android.core.result.BecalmResult

/**
 * Abstraction over a single speech-to-text back-end.
 *
 * Implementations:
 * - [AndroidSpeechRecognizerBackend] — wraps [android.speech.SpeechRecognizer] (online, SP-35).
 * - `WhisperJni` — on-device Whisper.cpp native engine (offline, SP-34, Batch A).
 *
 * The selector [DefaultSttBackendSelector] implements this interface and tries Whisper first,
 * falling back to [AndroidSpeechRecognizerBackend] on failure.
 *
 * Spec coverage: VOI-001.
 */
public interface SttBackend {

    /**
     * Transcribes the audio file at [path] and returns the recognised text.
     *
     * @param path Filesystem path to the decoded audio file (16 kHz 16-bit mono PCM or
     *             any format the back-end accepts).
     * @return [BecalmResult.Success] carrying the transcript string (may be empty when no
     *         speech is detected), or [BecalmResult.Failure] on codec / network / permission
     *         error.
     */
    public suspend fun transcribeAudio(path: String): BecalmResult<String>
}
