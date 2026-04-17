package com.becalm.android.domain.stt

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult

/**
 * STT backend backed by Android's [android.speech.SpeechRecognizer].
 *
 * In the current MVP this is a stub that always returns a structured failure.
 * The real implementation will bind to the on-device SpeechRecognizer service
 * and stream audio for KR/EN transcription.
 *
 * Spec: SP-34, KTR-ANDROID-SR
 */
public class AndroidSpeechRecognizerBackend {

    /**
     * Transcribes the audio file at [path] using Android SpeechRecognizer.
     *
     * @return [BecalmResult.Failure] in the current MVP stub.
     */
    public suspend fun transcribeAudio(path: String): BecalmResult<String> {
        return BecalmResult.Failure(
            BecalmError.Unknown(
                UnsupportedOperationException("SP-34b: AndroidSpeechRecognizerBackend not implemented"),
            ),
        )
    }
}
