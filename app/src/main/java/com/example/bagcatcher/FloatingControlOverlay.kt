package com.example.bagcatcher

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

/**
 * Draggable on-screen controller. The bubble opens a small panel with separate
 * Activate and Stop actions. Pausing detection does not stop screen capture,
 * so the user can resume from the same floating bubble.
 */
class FloatingControlOverlay(
    private val context: Context,
    initialEnabled: Boolean,
    private val onEnabledChanged: (Boolean) -> Unit,
    private val onOpenDebug: () -> Unit
) {
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val bubbleSize = dp(56)
    private val panelWidth = dp(184)
    private val bubble: TextView = TextView(context)
    private val bubbleParams = WindowManager.LayoutParams(
        bubbleSize,
        bubbleSize,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = (context.resources.displayMetrics.widthPixels - bubbleSize - dp(16)).coerceAtLeast(dp(8))
        y = (context.resources.displayMetrics.heightPixels * 0.42f).toInt()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var menuView: View? = null
    private var statusView: TextView? = null
    private var detectedCount = 0
    private var completedTapCount = 0
    private var attemptedTapCount = 0L
    private var rejectedTapCount = 0L
    private var tapServiceReady = false
    private var enabled = initialEnabled
    private var removed = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWindowX = 0
    private var downWindowY = 0
    private var dragged = false

    init {
        configureBubble()
        updateBubble()
        try {
            windowManager.addView(bubble, bubbleParams)
        } catch (_: Exception) {
            removed = true
        }
    }

    private fun configureBubble() {
        bubble.gravity = Gravity.CENTER
        bubble.textSize = 13f
        bubble.typeface = Typeface.DEFAULT_BOLD
        bubble.setTextColor(0xFFFFFFFF.toInt())
        bubble.elevation = dp(8).toFloat()
        bubble.contentDescription = "Bag Catcher floating controls. Tap to open Activate and Stop."
        bubble.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downWindowX = bubbleParams.x
                    downWindowY = bubbleParams.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > dp(5) || abs(dy) > dp(5)) dragged = true
                    if (dragged) {
                        val maxX = (context.resources.displayMetrics.widthPixels - bubbleSize).coerceAtLeast(0)
                        val maxY = (context.resources.displayMetrics.heightPixels - bubbleSize).coerceAtLeast(0)
                        bubbleParams.x = (downWindowX + dx.toInt()).coerceIn(0, maxX)
                        bubbleParams.y = (downWindowY + dy.toInt()).coerceIn(0, maxY)
                        safelyUpdateBubble()
                        repositionMenu()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) toggleMenu()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
    }

    private fun updateBubble() {
        bubble.text = if (enabled) "ON" else "BC"
        val fill = if (enabled) 0xFF16845B.toInt() else 0xFF6541B8.toInt()
        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(dp(2), 0xFFE8E0FF.toInt())
        }
    }

    private fun toggleMenu() {
        if (menuView == null) showMenu() else hideMenu()
    }

    private fun showMenu() {
        if (removed || menuView != null) return

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xF21B1D27.toInt())
                setStroke(dp(1), 0xFF8065CE.toInt())
            }
            elevation = dp(12).toFloat()
        }

        val heading = TextView(context).apply {
            text = "BAG CATCHER"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFE8E2FF.toInt())
        }
        panel.addView(heading, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(22)
        ))

        statusView = TextView(context).apply {
            text = statusText()
            textSize = 11f
            setTextColor(0xFFD0D3DC.toInt())
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        panel.addView(statusView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
        ))

        val activate = actionRow("ACTIVATE AUTO TAPS", 0xFF167B55.toInt())
        activate.setOnClickListener {
            setEnabled(true)
            hideMenu()
        }
        panel.addView(activate, actionParams())

        val stop = actionRow("STOP AUTO TAPS", 0xFF9B343B.toInt())
        stop.setOnClickListener {
            setEnabled(false)
            hideMenu()
        }
        panel.addView(stop, actionParams())

        val debug = actionRow("OPEN DEBUG SETTINGS", 0xFF394457.toInt())
        debug.setOnClickListener {
            hideMenu()
            onOpenDebug()
        }
        panel.addView(debug, actionParams())

        val params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        menuView = panel
        positionMenu(params)
        try {
            windowManager.addView(panel, params)
            menuParams = params
        } catch (_: Exception) {
            menuView = null
            menuParams = null
        }
    }

    private var menuParams: WindowManager.LayoutParams? = null

    private fun actionRow(label: String, color: Int): TextView =
        TextView(context).apply {
            text = label
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(color)
            }
            isClickable = true
        }

    private fun actionParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(36)
    ).apply {
        topMargin = dp(5)
    }

    private fun positionMenu(params: WindowManager.LayoutParams) {
        val metrics = context.resources.displayMetrics
        val maxX = (metrics.widthPixels - panelWidth).coerceAtLeast(0)
        params.x = (bubbleParams.x + bubbleSize - panelWidth).coerceIn(0, maxX)

        val estimatedPanelHeight = dp(220)
        val below = bubbleParams.y + bubbleSize + dp(8)
        params.y = if (below + estimatedPanelHeight <= metrics.heightPixels) {
            below
        } else {
            (bubbleParams.y - estimatedPanelHeight - dp(8)).coerceAtLeast(0)
        }
    }

    private fun repositionMenu() {
        val view = menuView ?: return
        val params = menuParams ?: return
        positionMenu(params)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    private fun safelyUpdateBubble() {
        if (removed) return
        try {
            windowManager.updateViewLayout(bubble, bubbleParams)
        } catch (_: Exception) {
        }
    }

    private fun setEnabled(value: Boolean) {
        enabled = value
        updateBubble()
        safelyUpdateBubble()
        onEnabledChanged(value)
        val panel = menuView as? LinearLayout
        statusView?.text = statusText()
    }

    private fun statusText(): String {
        val tapState = if (tapServiceReady) "tap service ready" else "tap service OFF"
        val autoState = if (enabled) "AUTO ON" else "AUTO PAUSED"
        return "$autoState · $tapState\nBoxes: $detectedCount · attempts: $attemptedTapCount\nAndroid gesture callbacks completed: $completedTapCount · rejected/busy: $rejectedTapCount"
    }

    fun updateStats(
        boxes: Int,
        taps: Int,
        serviceReady: Boolean,
        autoEnabled: Boolean,
        attempts: Long = 0L,
        rejected: Long = 0L
    ) {
        mainHandler.post {
            detectedCount = boxes
            completedTapCount = taps
            attemptedTapCount = attempts
            rejectedTapCount = rejected
            tapServiceReady = serviceReady
            if (enabled != autoEnabled) {
                enabled = autoEnabled
                updateBubble()
            }
            statusView?.text = statusText()
            bubble.contentDescription = statusText()
            safelyUpdateBubble()
        }
    }

    private fun hideMenu() {
        val view = menuView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        menuView = null
        menuParams = null
        statusView = null
    }

    fun remove() {
        if (removed) return
        hideMenu()
        try {
            windowManager.removeView(bubble)
        } catch (_: Exception) {
        }
        removed = true
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
