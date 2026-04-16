package com.becalm.android.worker

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.becalm.android.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject

/**
 * A single fixed-window audio chunk decoded to 16 kHz 16-bit mono PCM.
 *
 * @param startMs    Wall-clock offset in milliseconds from the beginning of the source file.
 * @param endMs      Wall-clock offset in milliseconds of the last sample in this chunk.
 * @param pcm16kMono Raw PCM bytes: 16 kHz sample rate, 16-bit signed little-endian, mono.
 * @param bytesUsed  Number of valid bytes in [pcm16kMono] (may be less than array length on
 *                   the final partial chunk).
 */
public data class AudioChunk(
    val startMs: Long,
    val endMs: Long,
    val pcm16kMono: ByteArray,
    val bytesUsed: Int,
) {
    // ByteArray equality is identity by default; override for value semantics.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return startMs == other.startMs &&
            endMs == other.endMs &&
            bytesUsed == other.bytesUsed &&
            pcm16kMono.contentEquals(other.pcm16kMono)
    }

    override fun hashCode(): Int {
        var result = startMs.hashCode()
        result = 31 * result + endMs.hashCode()
        result = 31 * result + pcm16kMono.contentHashCode()
        result = 31 * result + bytesUsed
        return result
    }
}

/**
 * Decodes an audio [Uri] (WAV / M4A / AMR / any `MediaExtractor`-supported format) to
 * 16 kHz 16-bit mono PCM and emits fixed-window [AudioChunk]s.
 *
 * ## VOI-001 — Audio format support
 * Uses [MediaExtractor] for demuxing and [MediaCodec] for software decode. Any codec
 * available on the device is eligible (AAC, AMR-NB/WB, FLAC, …). Formats that are
 * already 16-bit PCM at 16 kHz mono are passed through without resampling.
 *
 * ## VOI-002 — Fixed windowing
 * Chunks are VAD-less fixed windows of [chunkSeconds] seconds (default 30 s). The final
 * chunk may be shorter. Spec VOI-002 explicitly permits dumb fixed-window chunking.
 *
 * ## Error handling
 * If the URI cannot be opened, or the track yields no samples, the flow emits nothing
 * and returns silently — callers must not assume at least one chunk will be emitted.
 * No exceptions are rethrown; errors are logged at WARN level.
 *
 * ## Threading
 * All IO and codec work runs on [Dispatchers.IO]. Callers collect on any dispatcher.
 */
public class VoiceChunker @Inject constructor(
    private val logger: Logger,
) {

    /**
     * Produces [AudioChunk]s from the audio content at [uri].
     *
     * @param context      Used to open [Uri] via [android.content.ContentResolver].
     * @param uri          MediaStore or file URI of the audio recording.
     * @param chunkSeconds Window size in seconds. Must be > 0. Defaults to 30.
     * @return Cold [Flow] of [AudioChunk]s. Completes normally when the file ends or
     *         when no audio track is found. Never throws.
     */
    public fun chunks(
        context: Context,
        uri: Uri,
        chunkSeconds: Int = 30,
    ): Flow<AudioChunk> = flow {
        require(chunkSeconds > 0) { "chunkSeconds must be > 0, was $chunkSeconds" }

        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: run {
                logger.w(TAG, "could not open file descriptor for uri=$uri")
                return@flow
            }

            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) {
                logger.w(TAG, "no audio track found in uri=$uri")
                return@flow
            }

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val sourceSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: ""

            logger.d(
                TAG,
                "audio track: mime=$mime sampleRate=$sourceSampleRate channels=$sourceChannels",
            )

            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            try {
                val chunkPcmBytes = (TARGET_SAMPLE_RATE * BYTES_PER_SAMPLE * chunkSeconds)
                val accumulator = ByteBuffer.allocate(chunkPcmBytes * 2) // extra headroom
                accumulator.order(ByteOrder.LITTLE_ENDIAN)

                var chunkStartMs = 0L
                var currentPts = 0L
                var inputEos = false
                var outputEos = false
                val bufferInfo = MediaCodec.BufferInfo()

                while (!outputEos) {
                    // Feed input buffers
                    if (!inputEos) {
                        val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIdx >= 0) {
                            val inBuf = decoder.getInputBuffer(inIdx)!!
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputEos = true
                            } else {
                                val pts = extractor.sampleTime
                                decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                extractor.advance()
                            }
                        }
                    }

                    // Drain output buffers
                    val outIdx = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // Updated format available — we re-read channel/rate next iteration.
                            logger.d(TAG, "output format changed")
                        }
                        outIdx >= 0 -> {
                            val outBuf = decoder.getOutputBuffer(outIdx)!!
                            val outputFormat = decoder.outputFormat
                            val outSampleRate =
                                outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val outChannels =
                                outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            currentPts = bufferInfo.presentationTimeUs

                            // Downsample + downmix to 16 kHz mono 16-bit PCM
                            val pcmSamples = toPcm16kMono(
                                outBuf,
                                bufferInfo.size,
                                outSampleRate,
                                outChannels,
                            )
                            decoder.releaseOutputBuffer(outIdx, false)

                            var writeOffset = 0
                            while (writeOffset < pcmSamples.size) {
                                val space = accumulator.remaining()
                                val toCopy = minOf(pcmSamples.size - writeOffset, space)
                                accumulator.put(pcmSamples, writeOffset, toCopy)
                                writeOffset += toCopy

                                if (!accumulator.hasRemaining()) {
                                    val endMs = ptsToMs(currentPts)
                                    val payload = accumulator.array().copyOf(chunkPcmBytes)
                                    emit(
                                        AudioChunk(
                                            startMs = chunkStartMs,
                                            endMs = endMs,
                                            pcm16kMono = payload,
                                            bytesUsed = chunkPcmBytes,
                                        ),
                                    )
                                    logger.d(
                                        TAG,
                                        "emitting chunk startMs=$chunkStartMs endMs=$endMs bytes=$chunkPcmBytes",
                                    )
                                    chunkStartMs = endMs
                                    accumulator.clear()
                                }
                            }

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputEos = true
                            }
                        }
                    }
                }

                // Emit final partial chunk if any samples remain
                val remaining = accumulator.position()
                if (remaining > 0) {
                    val endMs = ptsToMs(currentPts)
                    val payload = accumulator.array().copyOf(remaining)
                    emit(
                        AudioChunk(
                            startMs = chunkStartMs,
                            endMs = endMs,
                            pcm16kMono = payload,
                            bytesUsed = remaining,
                        ),
                    )
                    logger.d(
                        TAG,
                        "emitting final partial chunk startMs=$chunkStartMs endMs=$endMs bytes=$remaining",
                    )
                }
            } finally {
                decoder.stop()
                decoder.release()
            }
        } catch (e: Exception) {
            logger.w(TAG, "VoiceChunker failed for uri=$uri — emitting nothing", e)
        } finally {
            extractor.release()
        }
    }.flowOn(Dispatchers.IO)

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the index of the first audio track in [extractor], or -1 if none found.
     */
    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    /**
     * Converts raw [MediaCodec] output bytes to 16 kHz mono 16-bit PCM.
     *
     * Steps:
     * 1. Interpret raw bytes as 16-bit LE samples (MediaCodec PCM output is always 16-bit).
     * 2. Downmix multi-channel to mono by averaging channels.
     * 3. Downsample from [sourceSampleRate] to [TARGET_SAMPLE_RATE] using nearest-neighbour
     *    (sufficient for STT; not for playback).
     *
     * If [sourceSampleRate] == [TARGET_SAMPLE_RATE] and [channels] == 1, the input bytes
     * are returned directly (zero-copy path).
     */
    private fun toPcm16kMono(
        buffer: ByteBuffer,
        size: Int,
        sourceSampleRate: Int,
        channels: Int,
    ): ByteArray {
        if (size <= 0) return ByteArray(0)

        val srcBytes = ByteArray(size)
        buffer.get(srcBytes, 0, size)

        // Fast path: already 16 kHz mono
        if (sourceSampleRate == TARGET_SAMPLE_RATE && channels == 1) return srcBytes

        // Parse 16-bit LE samples
        val totalSamples = size / (BYTES_PER_SAMPLE * channels)
        val monoSamples = ShortArray(totalSamples)
        val srcBuf = ByteBuffer.wrap(srcBytes).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until totalSamples) {
            var sum = 0L
            for (ch in 0 until channels) {
                sum += srcBuf.short.toLong()
            }
            monoSamples[i] = (sum / channels).toShort()
        }

        // Nearest-neighbour resample to TARGET_SAMPLE_RATE
        val outSamples = if (sourceSampleRate == TARGET_SAMPLE_RATE) {
            totalSamples
        } else {
            (totalSamples.toLong() * TARGET_SAMPLE_RATE / sourceSampleRate).toInt()
        }

        val outBytes = ByteArray(outSamples * BYTES_PER_SAMPLE)
        val outBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until outSamples) {
            val srcIdx = (i.toLong() * sourceSampleRate / TARGET_SAMPLE_RATE).toInt()
                .coerceIn(0, totalSamples - 1)
            outBuf.putShort(monoSamples[srcIdx])
        }

        return outBytes
    }

    /** Converts a [MediaCodec] presentation timestamp in microseconds to milliseconds. */
    private fun ptsToMs(ptsUs: Long): Long = ptsUs / 1_000L

    private companion object {
        private const val TAG = "VoiceChunker"
        private const val TARGET_SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2 // 16-bit
        private const val TIMEOUT_US = 10_000L // 10 ms dequeue timeout
    }
}
