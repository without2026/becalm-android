package com.becalm.android.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class MessageScreenshotNormalizeResult(
    val width: Int,
    val height: Int,
    val byteSize: Long,
)

internal object MessageScreenshotImageNormalizer {
    const val OUTPUT_EXTENSION: String = "jpg"
    const val OUTPUT_MIME_TYPE: String = "image/jpeg"
    const val MAX_SOURCE_BYTES: Long = 25L * 1024L * 1024L
    const val MAX_OUTPUT_BYTES: Long = 10L * 1024L * 1024L
    const val MAX_WIDTH_PX: Int = 1440
    const val MAX_PIXELS: Int = 8_000_000

    private const val JPEG_QUALITY = 82
    private const val COPY_BUFFER_BYTES = 8 * 1024

    fun normalize(input: InputStream, target: File): MessageScreenshotNormalizeResult {
        val sourceBytes = input.readBytesLimited(MAX_SOURCE_BYTES)
        val sourceSize = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, this)
        }
        if (sourceSize.outWidth <= 0 || sourceSize.outHeight <= 0) {
            throw MessageScreenshotImportValidationException("file", "unsupported message screenshot format")
        }

        val bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size)
            ?: throw MessageScreenshotImportValidationException("file", "unsupported message screenshot format")
        val normalized = bitmap.scaleForOcr()
        try {
            ByteArrayOutputStream().use { encoded ->
                if (!normalized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, encoded)) {
                    throw IOException("Unable to encode normalized screenshot")
                }
                if (encoded.size() > MAX_OUTPUT_BYTES) {
                    throw MessageScreenshotImportValidationException("file", "message screenshot exceeds 10 MiB")
                }
                target.outputStream().use { output ->
                    encoded.writeTo(output)
                }
            }
            return MessageScreenshotNormalizeResult(
                width = normalized.width,
                height = normalized.height,
                byteSize = target.length(),
            )
        } finally {
            if (normalized !== bitmap) normalized.recycle()
            bitmap.recycle()
        }
    }

    private fun Bitmap.scaleForOcr(): Bitmap {
        val widthScale = min(1.0, MAX_WIDTH_PX.toDouble() / width.toDouble())
        val pixelScale = min(1.0, sqrt(MAX_PIXELS.toDouble() / (width.toDouble() * height.toDouble())))
        val scale = min(widthScale, pixelScale)
        if (scale >= 1.0) return this
        val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    private fun InputStream.readBytesLimited(maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read == -1) return output.toByteArray()
            total += read
            if (total > maxBytes) {
                throw MessageScreenshotImportValidationException("file", "message screenshot exceeds 25 MiB")
            }
            output.write(buffer, 0, read)
        }
    }
}

internal class MessageScreenshotImportValidationException(
    val field: String?,
    val validationMessage: String,
) : Exception(validationMessage)
