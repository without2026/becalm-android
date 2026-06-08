package com.becalm.android.unit.ui.parity

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.InflaterInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PrototypeEvidenceVisualScoreSpecTest {
    @Test
    fun `generated parity fixtures can be scored against all prototype evidence screenshots`() {
        val evidenceDir = requireExistingDirectoryForLocalVisualScoring(
            label = "prototype evidence screenshots",
            candidates = listOf(
                File("../../../CEOhandoffs/inkmist-soft-click-render-evidence-20260607/screenshots"),
                File("../../CEOhandoffs/inkmist-soft-click-render-evidence-20260607/screenshots"),
                File("../CEOhandoffs/inkmist-soft-click-render-evidence-20260607/screenshots"),
            ),
        )
        val currentDir = requireExistingDirectoryForLocalVisualScoring(
            label = "Samsung full parity fixtures",
            candidates = listOf(
                File("app/build/outputs/ui-parity-fixtures/html-prototype-20260604-samsung-20260608-full"),
                File("build/outputs/ui-parity-fixtures/html-prototype-20260604-samsung-20260608-full"),
            ),
        )

        assertEquals(expectedPrototypeEvidenceNames, visualScorePairs.map { it.evidence })
        visualScorePairs.forEach { pair ->
            val referenceFile = evidenceDir.resolve(pair.evidence)
            val currentFile = currentDir.resolve(pair.current)
            assertTrue("Missing prototype evidence ${referenceFile.absolutePath}", referenceFile.isFile)
            assertTrue("Missing Android parity fixture ${currentFile.absolutePath}", currentFile.isFile)

            val reference = PngImage.read(referenceFile)
            val current = PngImage.read(currentFile)

            val stats = coarseRgbDiff(reference, current)
            assertTrue("Expected finite mean diff for ${pair.evidence}", stats.meanRgbAbsDiff.isFinite())
            assertTrue(
                "Expected mean diff in RGB byte range for ${pair.evidence}: ${stats.meanRgbAbsDiff}",
                stats.meanRgbAbsDiff in 0.0..255.0,
            )
            assertTrue(
                "Expected max diff in RGB byte range for ${pair.evidence}: ${stats.maxRgbAbsDiff}",
                stats.maxRgbAbsDiff in 0..255,
            )
        }
    }

    private fun requireExistingDirectoryForLocalVisualScoring(label: String, candidates: List<File>): File {
        val directory = candidates.firstOrNull(File::isDirectory)
        if (directory != null) {
            return directory
        }
        assumeTrue(
            "$label not found; run after generating local parity fixtures from the workspace root",
            false,
        )
        throw AssertionError("unreachable")
    }

    private fun coarseRgbDiff(reference: PngImage, current: PngImage): DiffStats {
        var total = 0L
        var max = 0
        for (sampleY in 0 until SAMPLE_GRID_SIZE) {
            val refY = sampleCoordinate(sampleY, SAMPLE_GRID_SIZE, reference.height)
            val curY = sampleCoordinate(sampleY, SAMPLE_GRID_SIZE, current.height)
            for (sampleX in 0 until SAMPLE_GRID_SIZE) {
                val refX = sampleCoordinate(sampleX, SAMPLE_GRID_SIZE, reference.width)
                val curX = sampleCoordinate(sampleX, SAMPLE_GRID_SIZE, current.width)
                val refRgb = reference.rgbAt(refX, refY)
                val curRgb = current.rgbAt(curX, curY)
                for (shift in RGB_SHIFTS) {
                    val diff = kotlin.math.abs(((refRgb shr shift) and 0xFF) - ((curRgb shr shift) and 0xFF))
                    total += diff.toLong()
                    if (diff > max) {
                        max = diff
                    }
                }
            }
        }
        val samples = SAMPLE_GRID_SIZE * SAMPLE_GRID_SIZE * RGB_SHIFTS.size
        return DiffStats(
            meanRgbAbsDiff = total.toDouble() / samples.toDouble(),
            maxRgbAbsDiff = max,
        )
    }

    private fun sampleCoordinate(index: Int, sampleSize: Int, actualSize: Int): Int {
        if (actualSize <= 1 || sampleSize <= 1) {
            return 0
        }
        return ((index.toLong() * (actualSize - 1)) / (sampleSize - 1)).toInt()
    }

    private data class DiffStats(
        val meanRgbAbsDiff: Double,
        val maxRgbAbsDiff: Int,
    )

    private data class VisualScorePair(
        val evidence: String,
        val current: String,
    )

    private data class PngImage(
        val width: Int,
        val height: Int,
        val rgb: IntArray,
    ) {
        fun rgbAt(x: Int, y: Int): Int = rgb[(y * width) + x]

        companion object {
            fun read(file: File): PngImage {
                val bytes = file.readBytes()
                require(bytes.size >= PNG_SIGNATURE.size) { "PNG too small: ${file.absolutePath}" }
                require(bytes.take(PNG_SIGNATURE.size) == PNG_SIGNATURE.toList()) {
                    "Invalid PNG signature: ${file.absolutePath}"
                }

                var offset = PNG_SIGNATURE.size
                var width = 0
                var height = 0
                var colorType = -1
                val idat = ByteArrayOutputStream()

                while (offset < bytes.size) {
                    val length = bytes.readInt(offset)
                    val chunkType = bytes.decodeToString(offset + 4, offset + 8)
                    val dataStart = offset + 8
                    val dataEnd = dataStart + length
                    require(dataEnd + 4 <= bytes.size) { "Invalid PNG chunk bounds: ${file.absolutePath}" }

                    when (chunkType) {
                        "IHDR" -> {
                            width = bytes.readInt(dataStart)
                            height = bytes.readInt(dataStart + 4)
                            val bitDepth = bytes[dataStart + 8].toInt() and 0xFF
                            colorType = bytes[dataStart + 9].toInt() and 0xFF
                            val compression = bytes[dataStart + 10].toInt() and 0xFF
                            val filter = bytes[dataStart + 11].toInt() and 0xFF
                            val interlace = bytes[dataStart + 12].toInt() and 0xFF
                            require(bitDepth == 8) { "Only 8-bit PNGs are supported: ${file.absolutePath}" }
                            require(colorType == PNG_RGB || colorType == PNG_RGBA) {
                                "Only RGB/RGBA PNGs are supported: ${file.absolutePath}"
                            }
                            require(compression == 0 && filter == 0 && interlace == 0) {
                                "Unsupported PNG encoding flags: ${file.absolutePath}"
                            }
                        }
                        "IDAT" -> idat.write(bytes, dataStart, length)
                        "IEND" -> break
                    }
                    offset = dataEnd + 4
                }

                require(width > 0 && height > 0 && colorType != -1) { "Missing PNG IHDR: ${file.absolutePath}" }
                val bytesPerPixel = if (colorType == PNG_RGBA) 4 else 3
                val decompressed = InflaterInputStream(ByteArrayInputStream(idat.toByteArray())).use { input ->
                    input.readBytes()
                }
                val rowLength = width * bytesPerPixel
                val expectedLength = height * (rowLength + 1)
                require(decompressed.size >= expectedLength) { "PNG payload too small: ${file.absolutePath}" }

                val previous = ByteArray(rowLength)
                val current = ByteArray(rowLength)
                val rgb = IntArray(width * height)
                var sourceOffset = 0
                for (y in 0 until height) {
                    val filterType = decompressed[sourceOffset].toInt() and 0xFF
                    sourceOffset += 1
                    for (index in 0 until rowLength) {
                        val raw = decompressed[sourceOffset + index].toInt() and 0xFF
                        val left = if (index >= bytesPerPixel) current[index - bytesPerPixel].toInt() and 0xFF else 0
                        val up = previous[index].toInt() and 0xFF
                        val upLeft = if (index >= bytesPerPixel) previous[index - bytesPerPixel].toInt() and 0xFF else 0
                        current[index] = when (filterType) {
                            0 -> raw
                            1 -> raw + left
                            2 -> raw + up
                            3 -> raw + ((left + up) / 2)
                            4 -> raw + paeth(left, up, upLeft)
                            else -> error("Unsupported PNG filter $filterType: ${file.absolutePath}")
                        }.toByte()
                    }
                    sourceOffset += rowLength

                    for (x in 0 until width) {
                        val pixelOffset = x * bytesPerPixel
                        val red = current[pixelOffset].toInt() and 0xFF
                        val green = current[pixelOffset + 1].toInt() and 0xFF
                        val blue = current[pixelOffset + 2].toInt() and 0xFF
                        val alpha = if (bytesPerPixel == 4) current[pixelOffset + 3].toInt() and 0xFF else 255
                        val composited = compositeOverWhite(red, green, blue, alpha)
                        rgb[(y * width) + x] = composited
                    }
                    current.copyInto(previous)
                }
                return PngImage(width = width, height = height, rgb = rgb)
            }

            private fun paeth(left: Int, up: Int, upLeft: Int): Int {
                val estimate = left + up - upLeft
                val leftDistance = kotlin.math.abs(estimate - left)
                val upDistance = kotlin.math.abs(estimate - up)
                val upLeftDistance = kotlin.math.abs(estimate - upLeft)
                return when {
                    leftDistance <= upDistance && leftDistance <= upLeftDistance -> left
                    upDistance <= upLeftDistance -> up
                    else -> upLeft
                }
            }

            private fun compositeOverWhite(red: Int, green: Int, blue: Int, alpha: Int): Int {
                val outRed = ((red * alpha) + (255 * (255 - alpha))) / 255
                val outGreen = ((green * alpha) + (255 * (255 - alpha))) / 255
                val outBlue = ((blue * alpha) + (255 * (255 - alpha))) / 255
                return (outRed shl 16) or (outGreen shl 8) or outBlue
            }

            private fun ByteArray.readInt(offset: Int): Int =
                ((this[offset].toInt() and 0xFF) shl 24) or
                    ((this[offset + 1].toInt() and 0xFF) shl 16) or
                    ((this[offset + 2].toInt() and 0xFF) shl 8) or
                    (this[offset + 3].toInt() and 0xFF)

            private val PNG_SIGNATURE = byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4E,
                0x47,
                0x0D,
                0x0A,
                0x1A,
                0x0A,
            )
            private const val PNG_RGB = 2
            private const val PNG_RGBA = 6
        }
    }

    private companion object {
        private const val SAMPLE_GRID_SIZE = 128
        private val RGB_SHIFTS = intArrayOf(16, 8, 0)

        private val expectedPrototypeEvidenceNames = listOf(
            "01-initial.png",
            "02-onboarding-identity.png",
            "03-onboarding-people-connected.png",
            "04-onboarding-calendar-connected.png",
            "05-onboarding-mail-done.png",
            "06-onboarding-first-aha.png",
            "07-person-search-tax.png",
            "08-person-kim-detail.png",
            "09-person-kim-evidence-sheet.png",
            "10-person-kim-compose-sheet.png",
            "11-person-choi-recall.png",
            "12-person-choi-compose-sheet.png",
            "13-schedule-diff-tab.png",
            "14-schedule-evidence-sheet.png",
            "15-commitment-give-take.png",
            "16-sync-reconnect-toast.png",
        )

        private val visualScorePairs = listOf(
            VisualScorePair(
                evidence = "01-initial.png",
                current = "onboarding-welcome-privacy-note.png",
            ),
            VisualScorePair(
                evidence = "02-onboarding-identity.png",
                current = "onboarding-identity-input.png",
            ),
            VisualScorePair(
                evidence = "03-onboarding-people-connected.png",
                current = "onboarding-people-connected.png",
            ),
            VisualScorePair(
                evidence = "04-onboarding-calendar-connected.png",
                current = "onboarding-calendar-connected.png",
            ),
            VisualScorePair(
                evidence = "05-onboarding-mail-done.png",
                current = "onboarding-mail-connected.png",
            ),
            VisualScorePair(
                evidence = "06-onboarding-first-aha.png",
                current = "onboarding-gmail-activation-ready.png",
            ),
            VisualScorePair(
                evidence = "07-person-search-tax.png",
                current = "persons-role-search-tax.png",
            ),
            VisualScorePair(
                evidence = "08-person-kim-detail.png",
                current = "person-detail-primary-action-density.png",
            ),
            VisualScorePair(
                evidence = "09-person-kim-evidence-sheet.png",
                current = "person-action-evidence-sheet.png",
            ),
            VisualScorePair(
                evidence = "10-person-kim-compose-sheet.png",
                current = "person-action-draft-reply-sheet.png",
            ),
            VisualScorePair(
                evidence = "11-person-choi-recall.png",
                current = "person-detail-stale-recall.png",
            ),
            VisualScorePair(
                evidence = "12-person-choi-compose-sheet.png",
                current = "person-reconnect-draft-sheet.png",
            ),
            VisualScorePair(
                evidence = "13-schedule-diff-tab.png",
                current = "schedule-missing-calendar-actions.png",
            ),
            VisualScorePair(
                evidence = "14-schedule-evidence-sheet.png",
                current = "schedule-action-evidence-sheet.png",
            ),
            VisualScorePair(
                evidence = "15-commitment-give-take.png",
                current = "commitments-open-give-take.png",
            ),
            VisualScorePair(
                evidence = "16-sync-reconnect-toast.png",
                current = "persons-source-processing-status.png",
            ),
        )
    }
}
