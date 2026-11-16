package com.example.bagcatcher

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** In-process diagnostic counters and a bounded event log for one capture session. */
object CatcherDiagnostics {
    data class Snapshot(
        val frames: Long,
        val rawCandidates: Int,
        val detections: Int,
        val tapAttempts: Long,
        val dispatchAccepted: Long,
        val dispatchRejected: Long,
        val gestureCompleted: Long,
        val gestureCancelled: Long,
        val frameErrors: Long,
        val autoTapEnabled: Boolean,
        val lastEvent: String
    )

    private val lock = Any()
    private val events = ArrayDeque<String>()
    private var frames = 0L
    private var rawCandidates = 0
    private var detections = 0
    private var tapAttempts = 0L
    private var dispatchAccepted = 0L
    private var dispatchRejected = 0L
    private var gestureCompleted = 0L
    private var gestureCancelled = 0L
    private var frameErrors = 0L
    private var autoTapEnabled = false

    private fun addEventLocked(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        events.addLast(stamp + "  " + message)
        while (events.size > 120) events.removeFirst()
    }

    fun startSession() {
        synchronized(lock) {
            frames = 0L
            rawCandidates = 0
            detections = 0
            tapAttempts = 0L
            dispatchAccepted = 0L
            dispatchRejected = 0L
            gestureCompleted = 0L
            gestureCancelled = 0L
            frameErrors = 0L
            autoTapEnabled = true
            events.clear()
            addEventLocked("Capture session started")
        }
    }

    fun frame(raw: Int, found: Int) {
        synchronized(lock) {
            frames++
            rawCandidates = raw
            detections = found
            if (found > 0) {
                addEventLocked("Frame " + frames + ": " + raw + " raw matches, " + found + " separated box candidates")
            }
        }
    }

    fun tapAttempt(x: Float, y: Float, durationMs: Int) {
        synchronized(lock) {
            tapAttempts++
            addEventLocked(
                "Tap attempt " + tapAttempts + " at (" + x.toInt() + ", " + y.toInt() +
                    "), duration " + durationMs + " ms"
            )
        }
    }

    fun dispatchAccepted() {
        synchronized(lock) {
            dispatchAccepted++
            addEventLocked("Android accepted gesture dispatch")
        }
    }

    fun dispatchRejected() {
        synchronized(lock) {
            dispatchRejected++
            addEventLocked("Gesture dispatch not accepted (service unavailable, busy, or Android rejected it)")
        }
    }

    fun gestureCompleted() {
        synchronized(lock) {
            gestureCompleted++
            addEventLocked("Android gesture callback: completed; game registration is NOT confirmed")
        }
    }

    fun gestureCancelled() {
        synchronized(lock) {
            gestureCancelled++
            addEventLocked("Android gesture callback: cancelled")
        }
    }

    fun frameError(message: String) {
        synchronized(lock) {
            frameErrors++
            addEventLocked("Frame error: " + message)
        }
    }

    fun setAutoTapEnabled(enabled: Boolean) {
        synchronized(lock) {
            if (autoTapEnabled != enabled) {
                autoTapEnabled = enabled
                addEventLocked(if (enabled) "Automatic tapping enabled" else "Automatic tapping paused")
            }
        }
    }

    fun note(message: String) {
        synchronized(lock) { addEventLocked(message) }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            frames, rawCandidates, detections, tapAttempts, dispatchAccepted,
            dispatchRejected, gestureCompleted, gestureCancelled, frameErrors,
            autoTapEnabled, events.peekLast() ?: "No events yet"
        )
    }

    fun report(settings: CatcherSettings, tapServiceReady: Boolean): String {
        synchronized(lock) {
            val out = StringBuilder()
            out.append("BAG CATCHER DIAGNOSTIC REPORT\n")
            out.append("Accessibility service connected: ").append(tapServiceReady).append('\n')
            out.append("Auto taps enabled: ").append(autoTapEnabled).append('\n')
            out.append("Frames processed: ").append(frames).append('\n')
            out.append("Raw template matches (latest frame): ").append(rawCandidates).append('\n')
            out.append("Separated box candidates (latest frame): ").append(detections).append('\n')
            out.append("Tap attempts: ").append(tapAttempts).append('\n')
            out.append("Android dispatch accepted: ").append(dispatchAccepted).append('\n')
            out.append("Android dispatch rejected/busy: ").append(dispatchRejected).append('\n')
            out.append("Gesture callbacks completed: ").append(gestureCompleted).append('\n')
            out.append("Gesture callbacks cancelled: ").append(gestureCancelled).append('\n')
            out.append("Frame errors: ").append(frameErrors).append('\n')
            out.append("\nLIVE SETTINGS\n")
            out.append("Core confidence threshold: ").append(settings.confidence)
                .append("% (wide template +4 points)\n")
            out.append("Tap duration: ").append(settings.tapDurationMs).append(" ms\n")
            out.append("Prediction lead: ").append(settings.leadMs).append(" ms\n")
            out.append("Estimated fall speed: ").append(settings.fallSpeedPxPerSec).append(" screen px/s\n")
            out.append("Tap X offset: ").append(settings.offsetX).append(" screen px\n")
            out.append("Tap Y offset: ").append(settings.offsetY).append(" screen px\n")
            out.append("Scan step: ").append(settings.scanStep).append(" px\n")
            out.append("Frame interval: ").append(settings.frameIntervalMs).append(" ms\n")
            out.append("Visual markers: ").append(settings.showOverlay).append('\n')
            out.append("\nEVENT LOG (newest last)\n")
            for (event in events) out.append(event).append('\n')
            out.append("\nNote: Android's completed gesture callback does not prove the game registered the tap.\n")
            return out.toString()
        }
    }

    fun resetCounters() {
        synchronized(lock) {
            frames = 0L
            rawCandidates = 0
            detections = 0
            tapAttempts = 0L
            dispatchAccepted = 0L
            dispatchRejected = 0L
            gestureCompleted = 0L
            gestureCancelled = 0L
            frameErrors = 0L
            events.clear()
            addEventLocked("Counters and event log reset")
        }
    }
}
