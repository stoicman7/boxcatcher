package com.example.bagcatcher

import kotlin.math.abs
import kotlin.math.max

/** Associates detections between frames so one falling bag is tapped only once. */
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

    /**
     * Motion model is based on Claude's measured ~1,080 source px/sec.
     * At the detector's half-width image this is ~540 work px/sec.
     */
    fun update(
        detections: List<BagDetector.Detection>,
        nowMs: Long,
        sourceWidth: Int,
        sourceHeight: Int,
        workWidth: Int,
        workHeight: Int
    ): List<TapCandidate> {
        tracks.removeAll { nowMs - it.lastSeenMs > 750L }
        val alreadyAssigned = HashSet<Long>()
        val newTapCandidates = ArrayList<TapCandidate>()
        val maxTapY = workHeight * 0.82f
        val minTapY = workHeight * 0.07f

        // Most confident hits are associated first.
        for (detection in detections.sortedByDescending { it.score }) {
            var best: Track? = null
            var bestError = Float.MAX_VALUE
            for (track in tracks) {
                if (track.id in alreadyAssigned) continue
                val elapsed = (nowMs - track.lastSeenMs).coerceAtLeast(0L)
                if (elapsed > 750L) continue
                val expectedY = track.y + elapsed * 0.54f
                val dx = abs(detection.centerX - track.x)
                val dy = abs(detection.centerY - expectedY)
                val toleranceY = max(18f, elapsed * 0.10f + 12f)
                if (dx <= 13f && dy <= toleranceY) {
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

            alreadyAssigned.add(track.id)
            track.x = detection.centerX
            track.y = detection.centerY
            track.lastSeenMs = nowMs

            if (!track.tapped && detection.centerY in minTapY..maxTapY) {
                val screenX = detection.centerX * sourceWidth / workWidth
                val screenY = detection.centerY * sourceHeight / workHeight
                // Small forward lead for capture/dispatch latency; the tap remains
                // within the gift-box area rather than moving down to the pouch.
                val leadPixels = 1080f * 0.012f
                newTapCandidates.add(TapCandidate(track.id, screenX, screenY + leadPixels))
            }
        }
        return newTapCandidates
    }

    fun markTapped(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val idSet = ids.toHashSet()
        tracks.filter { it.id in idSet }.forEach { it.tapped = true }
    }
}
