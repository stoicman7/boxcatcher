package com.example.bagcatcher

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var statsView: TextView
    private lateinit var accessButton: Button
    private var liveSettings = CatcherSettings()
    private val projectionRequestCode = 4107
    private val statsHandler = Handler(Looper.getMainLooper())

    private val statsRefresh = object : Runnable {
        override fun run() {
            if (::statsView.isInitialized) refreshStats()
            statsHandler.postDelayed(this, 900L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        liveSettings = CatcherSettings.load(this)
        buildScreen()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
        statsHandler.removeCallbacks(statsRefresh)
        statsHandler.post(statsRefresh)
    }

    override fun onPause() {
        statsHandler.removeCallbacks(statsRefresh)
        super.onPause()
    }

    private fun buildScreen() {
        liveSettings = CatcherSettings.load(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
            setBackgroundColor(0xFF10131A.toInt())
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        }

        val title = TextView(this).apply {
            text = "Bag Catcher"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        page.addView(title, matchWrap())

        val subtitle = TextView(this).apply {
            text = "LIVE CALIBRATION · Android 8.1+"
            textSize = 13f
            setTextColor(0xFFB7BEC9.toInt())
            setPadding(0, dp(5), 0, dp(18))
        }
        page.addView(subtitle, matchWrap())

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFE2E6ED.toInt())
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFF202632.toInt())
        }
        page.addView(status, matchWrap())

        accessButton = makeButton("1. Enable automatic tapping") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        page.addView(accessButton, buttonParams())

        val startButton = makeButton("2. Start screen detection") {
            beginCapturePermission()
        }
        page.addView(startButton, buttonParams())

        val stopButton = makeButton("Stop detection session") {
            stopService(Intent(this, CaptureService::class.java))
            CatcherDiagnostics.setAutoTapEnabled(false)
            status.text = "Detection stopped."
            Toast.makeText(this, "Bag Catcher stopped", Toast.LENGTH_SHORT).show()
        }.apply { setBackgroundColor(0xFF343B48.toInt()) }
        page.addView(stopButton, buttonParams())

        addSection(page, "LIVE DIAGNOSTICS")
        statsView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFE3E7EF.toInt())
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFF1A202B.toInt())
        }
        page.addView(statsView, matchWrap())

        val copyButton = makeButton("Copy diagnostic report") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Bag Catcher diagnostic report", buildReport()))
            Toast.makeText(this, "Diagnostic report copied", Toast.LENGTH_SHORT).show()
        }
        page.addView(copyButton, buttonParams())

        val shareButton = makeButton("Share diagnostic report") {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Bag Catcher diagnostic report")
                putExtra(Intent.EXTRA_TEXT, buildReport())
            }
            startActivity(Intent.createChooser(send, "Share diagnostic report"))
        }.apply { setBackgroundColor(0xFF343B48.toInt()) }
        page.addView(shareButton, buttonParams())

        val resetLogButton = makeButton("Reset diagnostic counters and log") {
            CatcherDiagnostics.resetCounters()
            refreshStats()
            Toast.makeText(this, "Diagnostic counters reset", Toast.LENGTH_SHORT).show()
        }.apply { setBackgroundColor(0xFF343B48.toInt()) }
        page.addView(resetLogButton, buttonParams())

        addSection(page, "LIVE DETECTION AND TAP SETTINGS")
        addDescription(
            page,
            "Changes are saved on this phone and applied during the next processed frame. You do not need to rebuild or reinstall the APK to tune these values."
        )

        addSlider(page, "Detection confidence", 45, 90, liveSettings.confidence, { value ->
            value.toString() + "% core · " + (value + 4).coerceAtMost(100) + "% wide"
        }) { value ->
            saveSettings(liveSettings.copy(confidence = value))
        }
        addDescription(page, "Lower confidence finds more boxes but can create false matches. Raise it if the overlay marks objects that are not gift boxes.")

        addSlider(page, "Tap duration", 25, 180, liveSettings.tapDurationMs, { value ->
            value.toString() + " ms"
        }) { value ->
            saveSettings(liveSettings.copy(tapDurationMs = value))
        }
        addDescription(page, "This changes the length of Android's single-finger gesture. It does not prove the game accepted the touch.")

        addSlider(page, "Prediction lead", 0, 100, liveSettings.leadMs, { value ->
            value.toString() + " ms"
        }) { value ->
            saveSettings(liveSettings.copy(leadMs = value))
        }
        addDescription(page, "The target is moved forward along the falling path to compensate for processing and gesture delay.")

        addSlider(page, "Estimated fall speed", 400, 1800, liveSettings.fallSpeedPxPerSec, { value ->
            value.toString() + " screen px/s"
        }) { value ->
            saveSettings(liveSettings.copy(fallSpeedPxPerSec = value))
        }

        addSlider(page, "Horizontal tap offset", -100, 100, liveSettings.offsetX, { value ->
            signedPixels(value)
        }) { value ->
            saveSettings(liveSettings.copy(offsetX = value))
        }
        addSlider(page, "Vertical tap offset", -180, 180, liveSettings.offsetY, { value ->
            signedPixels(value)
        }) { value ->
            saveSettings(liveSettings.copy(offsetY = value))
        }
        addDescription(page, "Offsets move the red NEXT TAP crosshair. Positive vertical values move it down; negative values move it up. Values are real screen pixels.")

        addSlider(page, "Scan precision", 1, 4, liveSettings.scanStep, { value ->
            value.toString() + " px step"
        }) { value ->
            saveSettings(liveSettings.copy(scanStep = value))
        }
        addDescription(page, "1 scans most carefully but uses more CPU. 4 is faster but can miss small or narrow details. Start at 2.")

        addSlider(page, "Frame processing interval", 40, 140, liveSettings.frameIntervalMs, { value ->
            value.toString() + " ms"
        }) { value ->
            saveSettings(liveSettings.copy(frameIntervalMs = value))
        }
        addDescription(page, "Lower values analyse more often and use more battery/CPU. Increase this if the phone becomes slow or hot.")

        val overlayCheck = CheckBox(this).apply {
            text = "Show detection boxes and NEXT TAP crosshair over the game"
            textSize = 14f
            setTextColor(0xFFE5E8EE.toInt())
            isChecked = liveSettings.showOverlay
            setPadding(0, dp(12), 0, dp(12))
            setOnCheckedChangeListener { _, checked ->
                saveSettings(liveSettings.copy(showOverlay = checked))
            }
        }
        page.addView(overlayCheck, matchWrap())

        val defaultsButton = makeButton("Restore recommended default settings") {
            CatcherSettings.reset(this)
            liveSettings = CatcherSettings.load(this)
            buildScreen()
            refreshStatus()
            Toast.makeText(this, "Recommended settings restored", Toast.LENGTH_SHORT).show()
        }.apply { setBackgroundColor(0xFF343B48.toInt()) }
        page.addView(defaultsButton, buttonParams())

        addSection(page, "HOW TO CALIBRATE")
        addDescription(
            page,
            "1. Start a game round and start detection.\n" +
                "2. Tap the floating BC circle and choose OPEN DEBUG SETTINGS.\n" +
                "3. Keep auto taps paused. New sessions start in detection-only mode, and coloured boxes continue running.\n" +
                "4. Cyan rectangles show the objects the detector matched. The red NEXT TAP crosshair shows the exact point it would tap.\n" +
                "5. Adjust confidence if boxes are missed or false matches appear. Adjust X/Y offsets until the red crosshair sits on the cream gift box.\n" +
                "6. Activate auto taps and tune duration/lead only after the target point is correct.\n" +
                "7. Copy the diagnostic report after a failed attempt and send it for analysis."
        )

        addDescription(
            page,
            "Important: “gesture completed” means Android finished sending the touch gesture. Android does not tell this app whether the game actually caught the bag."
        )

        setContentView(scroll)
        refreshStats()
    }

    private fun saveSettings(value: CatcherSettings) {
        liveSettings = value
        CatcherSettings.save(this, value)
    }

    private fun addSlider(
        page: LinearLayout,
        label: String,
        minValue: Int,
        maxValue: Int,
        initialValue: Int,
        formatter: (Int) -> String,
        onChanged: (Int) -> Unit
    ) {
        val valueText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFE8EAF0.toInt())
            setPadding(0, dp(13), 0, dp(1))
        }
        fun updateLabel(value: Int) {
            valueText.text = label + ": " + formatter(value)
        }
        updateLabel(initialValue)
        page.addView(valueText, matchWrap())

        val seek = SeekBar(this).apply {
            max = maxValue - minValue
            progress = (initialValue - minValue).coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = minValue + progress
                    updateLabel(value)
                    if (fromUser) onChanged(value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        page.addView(seek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(42)
        ))
    }

    private fun addSection(page: LinearLayout, label: String) {
        val heading = TextView(this).apply {
            text = label
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFBDA7FF.toInt())
            setPadding(0, dp(24), 0, dp(9))
        }
        page.addView(heading, matchWrap())
    }

    private fun addDescription(page: LinearLayout, message: String) {
        val text = TextView(this).apply {
            this.text = message
            textSize = 13f
            setTextColor(0xFF9FA8B7.toInt())
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(4), 0, dp(9))
        }
        page.addView(text, matchWrap())
    }

    private fun refreshStatus() {
        val enabled = isTapServiceEnabled()
        accessButton.text = if (enabled) "1. Automatic tapping is enabled" else "1. Enable automatic tapping"
        val snapshot = CatcherDiagnostics.snapshot()
        val capture = if (snapshot.frames > 0) "Capture analysis is running." else "Start a capture session after opening the game."
        status.text = "Accessibility service: " + (if (enabled) "enabled" else "not enabled") +
            "\n" + capture +
            "\nAuto taps: " + (if (snapshot.autoTapEnabled) "ON" else "PAUSED")
        refreshStats()
    }

    private fun refreshStats() {
        if (!::statsView.isInitialized) return
        val d = CatcherDiagnostics.snapshot()
        statsView.text =
            "Frames analysed: " + d.frames +
            "\nRaw template matches (latest frame): " + d.rawCandidates +
            "\nSeparated box candidates (latest frame): " + d.detections +
            "\nTap attempts: " + d.tapAttempts +
            "\nAndroid dispatch accepted: " + d.dispatchAccepted +
            "\nDispatch rejected / busy: " + d.dispatchRejected +
            "\nAndroid gesture callback completed: " + d.gestureCompleted +
            "\nAndroid gesture callback cancelled: " + d.gestureCancelled +
            "\nFrame errors: " + d.frameErrors +
            "\nAuto taps: " + (if (d.autoTapEnabled) "ON" else "PAUSED") +
            "\nLast event: " + d.lastEvent
    }

    private fun buildReport(): String {
        return CatcherDiagnostics.report(liveSettings, TapAccessibilityService.isConnected())
    }

    private fun isTapServiceEnabled(): Boolean {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val expectedClass = TapAccessibilityService::class.java.name
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
            info.resolveInfo?.serviceInfo?.let { serviceInfo ->
                serviceInfo.packageName == packageName && serviceInfo.name == expectedClass
            } == true
        }
    }

    private fun beginCapturePermission() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow display over other apps so the floating controls can appear", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
            )
            return
        }
        if (!isTapServiceEnabled()) {
            Toast.makeText(this, "Enable Bag Catcher in Accessibility settings first", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), projectionRequestCode)
    }

    @Deprecated("The result is required for Android 8.1 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != projectionRequestCode) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture permission was not granted", Toast.LENGTH_LONG).show()
            return
        }
        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        status.text = "Detection is starting. Return to the game; use the floating controls or notification to stop."
        Toast.makeText(this, "Bag Catcher started", Toast.LENGTH_SHORT).show()
        window.decorView.postDelayed({ moveTaskToBack(true) }, 450)
    }

    private fun makeButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xFF6941C6.toInt())
        setOnClickListener { action() }
    }

    private fun buttonParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(50)
    ).apply { bottomMargin = dp(10) }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun signedPixels(value: Int): String =
        (if (value > 0) "+" else "") + value + " px"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
