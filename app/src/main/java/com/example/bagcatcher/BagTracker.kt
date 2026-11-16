package com.example.bagcatcher

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Tracks each gift-box anchor independently, including bags in the same vertical column. */
class BagTracker {
    data class Track(
        val id: Long,
        var x: Float,
        var y: Float,
        var lastSeenMs: Long,
        var tapped: Boolean
    )

    data class TapCandidate(val trackId: Long, val x: Float, val y: Float)

    private val tracks = ArrayList<Track>()
    private var nextId = 1L

    // Claude measured about 1,080 source-screen pixels/second.
    private val workPixelsPerMs = 0.54f
    // Predict the gift-box anchor forward to compensate for frame processing and
    // AccessibilityService dispatch latency. This is a starting calibration value.
    private val tapLeadMs = 90f

    fun update(
        detections: List<BagDetector.Detection>,
        nowMs: Long,
        sourceWidth: Int,
        sourceHeight: Int,
        workWidth: Int,
        workHeight: Int
    ): List<TapCandidate> {
        tracks.removeAll { nowMs - it.lastSeenMs > 900L }
        val assigned = HashSet<Long>()
        val candidates = ArrayList<TapCandidate>()

        val minTapY = workHeight * 0.06f
        // Do not aim into the lower toolbar; the predicted position is also checked.
        val maxTapY = workHeight * 0.78f

        for (detection in detections.sortedByDescending { it.score }) {
            var best: Track? = null
            var bestError = Float.MAX_VALUE

            for (track in tracks) {
                if (track.id in assigned) continue
                val elapsed = (nowMs - track.lastSeenMs).coerceAtLeast(0L)
                if (elapsed > 900L) continue

                // Bags descend at approximately 0.54 work pixels per millisecond.
                val expectedY = track.y + elapsed * workPixelsPerMs
                val dx = abs(detection.centerX - track.x)
                val dy = abs(detection.centerY - expectedY)
                // The display redraws irregularly; allow one skipped redraw while
                // retaining separate tracks for closely spaced gift boxes.
                val toleranceY = max(28f, elapsed * 0.18f + 18f)

                if (dx <= 16f && dy <= toleranceY) {
                    val error = dx + dy * 0.65f
                    if (error < bestError) {
                        best = track
                        bestError = error
                    }
                }
            }

            val track = best ?: Track(
                id = nextId++,
                x = detection.centerX,
                y = detection.centerY,
                lastSeenMs = nowMs,
                tapped = false
            ).also { tracks.add(it) }

            assigned.add(track.id)
            track.x = detection.centerX
            track.y = detection.centerY
            track.lastSeenMs = nowMs

            if (!track.tapped && detection.centerY in minTapY..maxTapY) {
                val scaleX = sourceWidth.toFloat() / workWidth
                val scaleY = sourceHeight.toFloat() / workHeight
                val screenX = detection.centerX * scaleX
                val currentScreenY = detection.centerY * scaleY
                val leadScreenPixels = 1080f * tapLeadMs / 1000f
                val predictedScreenY = currentScreenY + leadScreenPixels

                // Leave room below the navigation/toolbar area and keep taps on-screen.
                val safeBottom = min(sourceHeight - 1, (sourceHeight * 0.86f).toInt()).toFloat()
                if (predictedScreenY <= safeBottom) {
                    candidates.add(
                        TapCandidate(
                            track.id,
                            screenX.coerceIn(1f, (sourceWidth - 2).coerceAtLeast(1).toFloat()),
                            predictedScreenY.coerceIn(1f, safeBottom)
                        )
                    )
                }
            }
        }

        // Tap the lowest eligible box first: it has the least remaining screen time.
        return candidates.sortedByDescending { it.y }
    }

    fun markTapped(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val idSet = ids.toHashSet()
        tracks.filter { it.id in idSet }.forEach { it.tapped = true }
    }
}
