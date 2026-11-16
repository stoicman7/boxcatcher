package com.example.bagcatcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicBoolean

class TapAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Sends one normal single-finger tap. We deliberately do not combine multiple
     * bags into simultaneous strokes: many games ignore or mishandle multi-touch.
     */
    fun tap(x: Float, y: Float, onComplete: (Boolean) -> Unit): Boolean {
        if (!gestureBusy.compareAndSet(false, true)) return false

        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val handler = Handler(Looper.getMainLooper())

        val accepted = try {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        gestureBusy.set(false)
                        onComplete(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        gestureBusy.set(false)
                        onComplete(false)
                    }
                },
                handler
            )
        } catch (_: Exception) {
            false
        }

        if (!accepted) gestureBusy.set(false)
        return accepted
    }

    companion object {
        @Volatile
        private var instance: TapAccessibilityService? = null
        private val gestureBusy = AtomicBoolean(false)
        private const val TAP_DURATION_MS = 55L

        fun sendTap(x: Float, y: Float, onComplete: (Boolean) -> Unit): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
            return instance?.tap(x, y, onComplete) ?: false
        }

        fun isConnected(): Boolean = instance != null
    }
}
