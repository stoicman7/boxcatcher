package com.example.bagcatcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Lightweight, dependency-free multi-template detector for the gift box/bow. */
class BagDetector(context: Context) {
    private data class Sample(val offsetX: Int, val offsetY: Int, val value: Int)

    private data class PreparedTemplate(
        val width: Int,
        val height: Int,
        val samples: Array<Sample>,
        val mean: Double,
        val variance: Double,
        val anchorX: Float,
        val anchorY: Float,
        val threshold: Double
    )

    data class Detection(
        val centerX: Float,
        val centerY: Float,
        val score: Float,
        val boxWidth: Int,
        val boxHeight: Int,
        val left: Float,
        val top: Float
    )

    private data class Candidate(
        val centerX: Float,
        val centerY: Float,
        val score: Float,
        val width: Int,
        val height: Int,
        val left: Float,
        val top: Float
    )

    private val sourceTemplates: List<SourceTemplate>
    private var preparedForWidth = -1
    private var preparedConfidence = -1
    private var preparedTemplates: List<PreparedTemplate> = emptyList()
    var lastRawCandidateCount: Int = 0
        private set

    private data class SourceTemplate(
        val bitmap: Bitmap,
        val anchorX: Float,
        val anchorY: Float,
        val threshold: Double
    )

    init {
        val core = context.assets.open("giftbox_core.png").use {
            BitmapFactory.decodeStream(it)
        }
        val wide = context.assets.open("giftbox_wide.png").use {
            BitmapFactory.decodeStream(it)
        }
        sourceTemplates = listOf(
            // This crop focuses on the box and bow, so it remains useful when
            // the red pouch body of a neighbouring bag overlaps it.
            SourceTemplate(core, 0.50f, 0.50f, 0.74),
            // Wider context is a second independent confirmation pattern.
            SourceTemplate(wide, 33f / 82f, 29f / 62f, 0.78)
        )
    }

    /** gray is a one-byte-per-pixel luminance image, already resized to 360 px wide. */
    fun find(
        gray: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        confidencePercent: Int = 74,
        requestedScanStep: Int = 2
    ): List<Detection> {
        lastRawCandidateCount = 0
        if (frameWidth <= 0 || frameHeight <= 0 || gray.size < frameWidth * frameHeight) return emptyList()
        val confidence = confidencePercent.coerceIn(45, 90)
        prepareTemplates(frameWidth, confidence)

        val candidates = ArrayList<Candidate>(24)
        val scanStep = requestedScanStep.coerceIn(1, 4)
        val yStart = (frameHeight * 0.025f).toInt()
        // Scan the entire width: Claude's measurements show bags can enter as far left as x≈14 px.
        val xStart = 0
        // Avoid the bottom toolbar/chat controls; only tap bags in the main play area.
        val maxAnchorY = (frameHeight * 0.87f).toInt()

        for (template in preparedTemplates) {
            val maxX = frameWidth - template.width
            val maxY = min(frameHeight - template.height, maxAnchorY - template.anchorY.toInt())
            if (maxX <= 0 || maxY <= yStart) continue

            val samples = template.samples
            val count = samples.size.toDouble()
            val templateNorm = sqrt(template.variance)
            if (templateNorm <= 0.0) continue

            var y = yStart
            while (y <= maxY) {
                var x = xStart
                while (x <= maxX) {
                    var sum = 0.0
                    var sumSquares = 0.0
                    var dot = 0.0
                    for (sample in samples) {
                        val value = gray[(y + sample.offsetY) * frameWidth + x + sample.offsetX].toInt() and 0xFF
                        sum += value
                        sumSquares += value * value
                        dot += value * sample.value
                    }
                    val patchVariance = sumSquares - (sum * sum / count)
                    if (patchVariance > 500.0) {
                        val numerator = dot - (sum * template.mean)
                        val score = numerator / (templateNorm * sqrt(patchVariance))
                        if (score >= template.threshold) {
                            candidates.add(
                                Candidate(
                                    x + template.anchorX,
                                    y + template.anchorY,
                                    score.toFloat(),
                                    template.width,
                                    template.height,
                                    x.toFloat(),
                                    y.toFloat()
                                )
                            )
                        }
                    }
                    x += scanStep
                }
                y += scanStep
            }
        }

        lastRawCandidateCount = candidates.size
        if (candidates.isEmpty()) return emptyList()
        candidates.sortByDescending { it.score }

        // Non-maximum suppression removes repeated matches around one box but
        // keeps distinct boxes stacked in the same vertical column.
        val selected = ArrayList<Detection>(16)
        for (candidate in candidates) {
            val suppressX = max(7f, min(candidate.width, 28) * 0.30f)
            val suppressY = max(7f, min(candidate.height, 24) * 0.30f)
            val overlapsExisting = selected.any { existing ->
                abs(existing.centerX - candidate.centerX) < suppressX &&
                    abs(existing.centerY - candidate.centerY) < suppressY
            }
            if (!overlapsExisting) {
                selected.add(
                    Detection(
                        candidate.centerX,
                        candidate.centerY,
                        candidate.score,
                        candidate.width,
                        candidate.height,
                        candidate.left,
                        candidate.top
                    )
                )
            }
            if (selected.size >= 16) break
        }
        return selected
    }

    private fun prepareTemplates(frameWidth: Int, confidencePercent: Int) {
        if (preparedForWidth == frameWidth && preparedConfidence == confidencePercent) return
        val scale = frameWidth / 360f
        preparedTemplates = sourceTemplates.map { source ->
            val targetWidth = max(14, (source.bitmap.width * scale).roundToInt())
            val targetHeight = max(14, (source.bitmap.height * scale).roundToInt())
            val scaled = Bitmap.createScaledBitmap(source.bitmap, targetWidth, targetHeight, true)
            val pixels = IntArray(targetWidth * targetHeight)
            scaled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
            if (scaled !== source.bitmap) scaled.recycle()

            val sampleList = ArrayList<Sample>()
            var sum = 0.0
            var sumSquares = 0.0
            var sy = 1
            while (sy < targetHeight - 1) {
                var sx = 1
                while (sx < targetWidth - 1) {
                    val color = pixels[sy * targetWidth + sx]
                    val red = (color shr 16) and 0xFF
                    val green = (color shr 8) and 0xFF
                    val blue = color and 0xFF
                    val gray = (77 * red + 150 * green + 29 * blue) shr 8
                    sampleList.add(Sample(sx, sy, gray))
                    sum += gray
                    sumSquares += gray * gray
                    sx += 4
                }
                sy += 4
            }
            val n = sampleList.size.toDouble()
            val mean = sum / n
            val variance = max(1.0, sumSquares - (sum * sum / n))
            PreparedTemplate(
                targetWidth,
                targetHeight,
                sampleList.toTypedArray(),
                mean,
                variance,
                source.anchorX * targetWidth,
                source.anchorY * targetHeight,
                (source.threshold + (confidencePercent - 74) / 100.0).coerceIn(0.45, 0.96)
            )
        }
        preparedForWidth = frameWidth
        preparedConfidence = confidencePercent
    }
}
