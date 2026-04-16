package com.becalm.android.domain.stt

import android.content.Context
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * DISABLED stub for [SttBackend] that previously delegated to
 * [android.speech.SpeechRecognizer].
 *
 * ## Why this backend is disabled
 * [android.speech.SpeechRecognizer] is a **live-microphone** API — it cannot transcribe
 * pre-recorded audio files. The `EXTRA_AUDIO_SOURCE` extra referenced in earlier revisions
 * is not a real SDK parameter; passing it has no effect.
 *
 * Two additional problems made the original implementation unshippable:
 * 1. [SpeechRecognizer.createSpeechRecognizer] and [SpeechRecognizer.startListening] must be
 *    called from the **main thread**; calling them off-main-thread causes an immediate crash.
 * 2. Without `EXTRA_PREFER_OFFLINE=true`, Samsung devices route recorded audio to Google
 *    Cloud ASR — a PIPA violation for BeCalm's B2B users.
 *
 * ## Tech debt
 * Live-microphone transcription (separate from the file-transcription path) is tracked as
 * **KTR-STT-LIVEMIC**. SP-35b will add a dedicated `LiveMicTranscriber` if the feature is
 * prioritised. That implementation must:
 * - Call SpeechRecognizer exclusively on the main thread via a Handler/Looper.
 * - Set `EXTRA_PREFER_OFFLINE=true` before sending audio off-device.
 * - Gate on `RECORD_AUDIO` permission before constructing the recogniser.
 *
 * @param context Application context (retained for DI compilability; not used).
 */
public class AndroidSpeechRecognizerBackend @Inject constructor(
    @ApplicationContext private val context: Context,
) : SttBackend {

    /**
     * Always returns [BecalmResult.Failure].
     *
     * This backend is permanently disabled until KTR-STT-LIVEMIC / SP-35b ships a proper
     * live-mic transcriber. [android.speech.SpeechRecognizer] cannot transcribe files and
     * must not be instantiated off the main thread.
     *
     * @param path Ignored.
     */
    override suspend fun transcribeAudio(path: String): BecalmResult<String> =
        BecalmResult.Failure(
            BecalmError.Unknown(
                UnsupportedOperationException(
                    "AndroidSpeechRecognizer cannot transcribe files — live-mic only; " +
                        "SP-35b will add a proper LiveMicTranscriber if needed",
                ),
            ),
        )
}
