package com.becalm.android.domain.stt

import com.becalm.android.core.result.BecalmResult
import timber.log.Timber
import javax.inject.Inject

/**
 * [SttBackend] that tries the Whisper.cpp native engine first and falls back to
 * [AndroidSpeechRecognizerBackend] when Whisper fails.
 *
 * ## Selection logic
 * 1. Delegate to [whisper]. On [BecalmResult.Success], return immediately.
 * 2. On [BecalmResult.Failure], log the Whisper error (with redacted path) and delegate to
 *    [android].
 * 3. If [android] also fails, log WARN and return android's failure.
 *
 * ## Android fallback note
 * [android] ([AndroidSpeechRecognizerBackend]) is currently a **disabled stub** — it always
 * returns [BecalmResult.Failure]. This fallback chain is retained so the wiring compiles and
 * a future live-mic implementation (KTR-STT-LIVEMIC / SP-35b) can be slotted in without
 * changing callers. The WARN log below ensures operators can diagnose why transcription
 * fails even when Whisper is also unavailable (SP-34 stub mode).
 *
 * ## PII handling
 * [path] contains a real filesystem location. All log statements use an 8-char hex surrogate
 * (`"%08x".format(path.hashCode())`) so paths never appear in logcat.
 *
 * @param whisper  Whisper.cpp native back-end (SP-34, Batch A).
 * @param android  [AndroidSpeechRecognizerBackend] fallback — disabled stub until SP-35b.
 *
 * Spec coverage: VOI-001.
 */
public class DefaultSttBackendSelector @Inject constructor(
    private val whisper: WhisperJni,
    private val android: AndroidSpeechRecognizerBackend,
) : SttBackend {

    override suspend fun transcribeAudio(path: String): BecalmResult<String> {
        val redactedPath = "%08x".format(path.hashCode())

        return when (val whisperResult = whisper.transcribeAudio(path)) {
            is BecalmResult.Success -> whisperResult
            is BecalmResult.Failure -> {
                Timber.w(
                    "Whisper failed for path=%s error=%s — falling back to AndroidSpeechRecognizer (disabled stub, KTR-STT-LIVEMIC)",
                    redactedPath,
                    whisperResult.error,
                )
                when (val androidResult = android.transcribeAudio(path)) {
                    is BecalmResult.Success -> androidResult
                    is BecalmResult.Failure -> {
                        Timber.w(
                            "AndroidSpeechRecognizer fallback also failed for path=%s error=%s — " +
                                "no STT backend available (Whisper: KTR-WHISPER-JNI; Android: KTR-STT-LIVEMIC)",
                            redactedPath,
                            androidResult.error,
                        )
                        androidResult
                    }
                }
            }
        }
    }
}
