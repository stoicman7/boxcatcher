package com.example.bagcatcher

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Learning-mode touch recorder. It briefly makes itself touch-through and asks
 * the accessibility service to replay the user's gesture at the same location.
 */
class LearningTouchOverlay(
    context: Context,
    private val onGesture: (List<PointF>, Long, (Boolean) -> Unit) -> Unit
) : View(context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    private val points = ArrayList<PointF>()
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var attached = false
    private var forwarding = false

    init {
        isClickable = true
        isFocusable = false
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    fun attach() {
        if (attached) return
        try {
            windowManager.addView(this, params)
            attached = true
        } catch (_: Exception) {
            attached = false
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!attached || forwarding) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                points.clear()
                downTime = android.os.SystemClock.uptimeMillis()
                downX = event.rawX
                downY = event.rawY
                points.add(PointF(event.rawX, event.rawY))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val last = points.lastOrNull()
                if (last == null || hypot(
                        (event.rawX - last.x).toDouble(),
                        (event.rawY - last.y).toDouble()
                    ) >= 3.0
                ) {
                    points.add(PointF(event.rawX, event.rawY))
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                points.add(PointF(event.rawX, event.rawY))
                val duration = (android.os.SystemClock.uptimeMillis() - downTime).coerceIn(40L, 1800L)
                val gesturePoints = simplify(points)
                forwarding = true
                setTouchThrough(true)
                try {
                    onGesture(gesturePoints, duration) { success ->
                        mainHandler.post {
                            if (attached) setTouchThrough(false)
                            forwarding = false
                            if (!success) {
                                // The app cannot safely reproduce the original touch if Android
                                // refuses gesture injection; expose this in diagnostics.
                            }
                        }
                    }
                } catch (_: Exception) {
                    setTouchThrough(false)
                    forwarding = false
                }
                points.clear()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                points.clear()
                return true
            }
        }
        return true
    }

    private fun simplify(input: List<PointF>): List<PointF> {
        if (input.size <= 24) return input.map { PointF(it.x, it.y) }
        val output = ArrayList<PointF>(24)
        for (i in 0 until 24) {
            val index = (i.toLong() * (input.size - 1) / 23).toInt()
            output.add(PointF(input[index].x, input[index].y))
        }
        return output
    }

    private fun setTouchThrough(enabled: Boolean) {
        if (!attached) return
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            if (enabled) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0
        try {
            windowManager.updateViewLayout(this, params)
        } catch (_: Exception) {
        }
    }

    fun remove() {
        if (!attached) return
        try {
            windowManager.removeView(this)
        } catch (_: Exception) {
        }
        attached = false
        forwarding = false
        points.clear()
    }
}
