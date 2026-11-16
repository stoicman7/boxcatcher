package com.example.bagcatcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Transparent, touch-through layer showing the detector's actual search matches
 * and the exact screen coordinate selected for the next tap.
 */
class DetectionOverlay(context: Context) : View(context) {
    private data class Mark(
        val bounds: RectF,
        val anchorX: Float,
        val anchorY: Float,
        val score: Float
    )

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
    }
    private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val tapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5252.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(3).toFloat()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = dp(11).toFloat()
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(dp(2).toFloat(), 0f, 0f, 0xFF000000.toInt())
    }

    @Volatile private var marks: List<Mark> = emptyList()
    @Volatile private var tapX: Float? = null
    @Volatile private var tapY: Float? = null
    private var attached = false

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
    }

    fun attach() {
        if (attached) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        try {
            windowManager.addView(this, params)
            attached = true
        } catch (_: Exception) {
            attached = false
        }
    }

    fun update(
        detections: List<BagDetector.Detection>,
        scaleX: Float,
        scaleY: Float,
        targetX: Float?,
        targetY: Float?,
        visible: Boolean
    ) {
        mainHandler.post {
            marks = if (visible) detections.map { detection ->
                Mark(
                    RectF(
                        detection.left * scaleX,
                        detection.top * scaleY,
                        (detection.left + detection.boxWidth) * scaleX,
                        (detection.top + detection.boxHeight) * scaleY
                    ),
                    detection.centerX * scaleX,
                    detection.centerY * scaleY,
                    detection.score
                )
            } else emptyList()
            tapX = if (visible) targetX else null
            tapY = if (visible) targetY else null
            visibility = if (visible) View.VISIBLE else View.INVISIBLE
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for ((index, mark) in marks.withIndex()) {
            canvas.drawRoundRect(mark.bounds, dp(3).toFloat(), dp(3).toFloat(), boxPaint)
            canvas.drawCircle(mark.anchorX, mark.anchorY, dp(3).toFloat(), anchorPaint)
            canvas.drawText(
                "#"+(index + 1)+"  "+(mark.score * 100).toInt()+"%",
                mark.bounds.left,
                (mark.bounds.top - dp(4)).coerceAtLeast(dp(12).toFloat()),
                labelPaint
            )
        }

        val x = tapX
        val y = tapY
        if (x != null && y != null) {
            val radius = dp(12).toFloat()
            canvas.drawCircle(x, y, radius, tapPaint)
            canvas.drawLine(x - radius - dp(5), y, x + radius + dp(5), y, tapPaint)
            canvas.drawLine(x, y - radius - dp(5), x, y + radius + dp(5), tapPaint)
            canvas.drawText("NEXT TAP", x + radius + dp(4), y - radius, labelPaint)
        }
    }

    fun remove() {
        mainHandler.post {
            if (!attached) return@post
            try {
                windowManager.removeView(this)
            } catch (_: Exception) {
            }
            attached = false
            marks = emptyList()
            tapX = null
            tapY = null
        }
    }

    private fun dp(value: Int): Int =
        (context.resources.displayMetrics.density * value).toInt().coerceAtLeast(1)
}
