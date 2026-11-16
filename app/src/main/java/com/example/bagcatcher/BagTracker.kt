package com.example.bagcatcher

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Tracks each gift-box anchor independently, including boxes in the same column. */
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

    fun update(
        detections: List<BagDetector.Detection>,
        nowMs: Long,
        sourceWidth: Int,
        sourceHeight: Int,
        workWidth: Int,
        workHeight: Int,
        tapLeadMs: Int = 12,
        fallSpeedPxPerSec: Int = 1080,
        offsetX: Int = 0,
        offsetY: Int = 0
    ): List<TapCandidate> {
        tracks.removeAll { nowMs - it.lastSeenMs > 900L }
        val assigned = HashSet<Long>()
        val candidates = ArrayList<TapCandidate>()

        val minTapY = workHeight * 0.06f
        // Exclude the lower game toolbar. The predicted point gets a second safety check.
        val maxTapY = workHeight * 0.78f
        val workFallSpeed = fallSpeedPxPerSec.coerceIn(400, 1800) / 1000f *
            (workHeight.toFloat() / sourceHeight.coerceAtLeast(1))

        for (detection in detections.sortedByDescending { it.score }) {
            var best: Track? = null
            var bestError = Float.MAX_VALUE

            for (track in tracks) {
                if (track.id in assigned) continue
                val elapsed = (nowMs - track.lastSeenMs).coerceAtLeast(0L)
                if (elapsed > 900L) continue

                val expectedY = track.y + elapsed * workFallSpeed
                val dx = abs(detection.centerX - track.x)
                val dy = abs(detection.centerY - expectedY)
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
                val scaleX = sourceWidth.toFloat() / workWidth.coerceAtLeast(1)
                val scaleY = sourceHeight.toFloat() / workHeight.coerceAtLeast(1)
                val currentScreenX = detection.centerX * scaleX
                val currentScreenY = detection.centerY * scaleY
                val leadScreenPixels = fallSpeedPxPerSec.coerceIn(400, 1800) *
                    tapLeadMs.coerceIn(0, 100) / 1000f
                val predictedScreenY = currentScreenY + leadScreenPixels + offsetY

                val safeBottom = min(sourceHeight - 1, (sourceHeight * 0.86f).toInt()).toFloat()
                if (predictedScreenY in 1f..safeBottom) {
                    candidates.add(
                        TapCandidate(
                            track.id,
                            (currentScreenX + offsetX).coerceIn(1f, (sourceWidth - 2).coerceAtLeast(1).toFloat()),
                            predictedScreenY
                        )
                    )
                }
            }
        }

        // The lowest eligible box has the least remaining screen time.
        return candidates.sortedByDescending { it.y }
    }

    fun markTapped(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val idSet = ids.toHashSet()
        tracks.filter { it.id in idSet }.forEach { it.tapped = true }
    }
}
