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

    private fun addEventLocked(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        events.addLast("$"+"stamp  "+"$"+"message")
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
            events.clear()
            addEventLocked("Capture session started")
        }
    }

    fun frame(raw: Int, found: Int) {
        synchronized(lock) {
            frames++
            rawCandidates = raw
            detections = found
            if (found > 0) addEventLocked("Frame "+"$"+"frames: "+"$"+"raw raw matches, "+"$"+"found separated box candidates")
        }
    }

    fun tapAttempt(x: Float, y: Float, durationMs: Int) {
        synchronized(lock) {
            tapAttempts++
            addEventLocked("Tap attempt "+"$"+"tapAttempts at ("+"$"+"{x.toInt()}, "+"$"+"{y.toInt()}), duration "+"$"+"durationMs ms")
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
            addEventLocked("Frame error: "+"$"+"message")
        }
    }

    fun note(message: String) {
        synchronized(lock) { addEventLocked(message) }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            frames, rawCandidates, detections, tapAttempts, dispatchAccepted,
            dispatchRejected, gestureCompleted, gestureCancelled, frameErrors,
            events.peekLast() ?: "No events yet"
        )
    }

    fun report(settings: CatcherSettings, tapServiceReady: Boolean, autoTapEnabled: Boolean): String {
        synchronized(lock) {
            val out = StringBuilder()
            out.append("BAG CATCHER DIAGNOSTIC REPORT\n")
            out.append("Accessibility service connected: "+"$"+"tapServiceReady\n")
            out.append("Auto taps enabled: "+"$"+"autoTapEnabled\n")
            out.append("Frames processed: "+"$"+"frames\n")
            out.append("Raw template matches (latest frame): "+"$"+"rawCandidates\n")
            out.append("Separated box candidates (latest frame): "+"$"+"detections\n")
            out.append("Tap attempts: "+"$"+"tapAttempts\n")
            out.append("Android dispatch accepted: "+"$"+"dispatchAccepted\n")
            out.append("Android dispatch rejected/busy: "+"$"+"dispatchRejected\n")
            out.append("Gesture callbacks completed: "+"$"+"gestureCompleted\n")
            out.append("Gesture callbacks cancelled: "+"$"+"gestureCancelled\n")
            out.append("Frame errors: "+"$"+"frameErrors\n")
            out.append("\nLIVE SETTINGS\n")
            out.append("Core confidence threshold: "+"$"+"{settings.confidence}% (wide template +4 points)\n")
            out.append("Tap duration: "+"$"+"{settings.tapDurationMs} ms\n")
            out.append("Prediction lead: "+"$"+"{settings.leadMs} ms\n")
            out.append("Estimated fall speed: "+"$"+"{settings.fallSpeedPxPerSec} screen px/s\n")
            out.append("Tap X offset: "+"$"+"{settings.offsetX} screen px\n")
            out.append("Tap Y offset: "+"$"+"{settings.offsetY} screen px\n")
            out.append("Scan step: "+"$"+"{settings.scanStep} px\n")
            out.append("Frame interval: "+"$"+"{settings.frameIntervalMs} ms\n")
            out.append("Visual markers: "+"$"+"{settings.showOverlay}\n")
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
