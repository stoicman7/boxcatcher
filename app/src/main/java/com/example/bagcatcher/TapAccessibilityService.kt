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

    fun tapMany(points: List<Pair<Float, Float>>): Boolean {
        if (points.isEmpty() || !gestureBusy.compareAndSet(false, true)) return false
        val builder = GestureDescription.Builder()
        points.take(8).forEach { (x, y) ->
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 1.0f, y + 1.0f)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0L, 35L))
        }
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                gestureBusy.set(false)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                gestureBusy.set(false)
            }
        }
        val accepted = try {
            dispatchGesture(builder.build(), callback, Handler(Looper.getMainLooper()))
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

        fun sendTaps(points: List<Pair<Float, Float>>): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
            return instance?.tapMany(points) ?: false
        }

        fun isConnected(): Boolean = instance != null
    }
}
