package com.example.bagcatcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var accessButton: Button
    private val projectionRequestCode = 4107

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(24))
            setBackgroundColor(0xFF10131A.toInt())
        }

        val scroll = ScrollView(this).apply {
            fillViewport = true
            addView(page)
        }

        val title = TextView(this).apply {
            text = "Bag Catcher"
            textSize = 29f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        page.addView(title, matchWrap())

        val subtitle = TextView(this).apply {
            text = "Gift-box detector · Android 8.1+"
            textSize = 15f
            setTextColor(0xFFB7BEC9.toInt())
            setPadding(0, dp(5), 0, dp(22))
        }
        page.addView(subtitle, matchWrap())

        val explanation = TextView(this).apply {
            text = "The detector looks for the cream gift box and peach bow, then taps near the box centre. It can detect several boxes in one frame, including vertically separated boxes in the same column."
            textSize = 16f
            setTextColor(0xFFE6E9EF.toInt())
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, 0, 0, dp(22))
        }
        page.addView(explanation, matchWrap())

        accessButton = makeButton("1. Enable automatic tapping") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        page.addView(accessButton, buttonParams())

        val startButton = makeButton("2. Start screen detection") {
            beginCapturePermission()
        }
        page.addView(startButton, buttonParams())

        val stopButton = makeButton("Stop detection") {
            stopService(Intent(this, CaptureService::class.java))
            status.text = "Detection stopped."
            Toast.makeText(this, "Bag Catcher stopped", Toast.LENGTH_SHORT).show()
        }
        stopButton.setBackgroundColor(0xFF343B48.toInt())
        page.addView(stopButton, buttonParams())

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFB7BEC9.toInt())
            setPadding(0, dp(18), 0, dp(18))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        page.addView(status, matchWrap())

        val note = TextView(this).apply {
            text = "How to use\n1. Open the game first.\n2. Return here and enable the accessibility service once.\n3. Start detection and approve screen capture.\n4. Bag Catcher moves to the background so you can return to the game.\n5. Stop from the app or the persistent notification.\n\nKeep the phone awake and the game in the foreground during a round. The first version uses image matching, so performance and detection thresholds may need tuning on your exact phone."
            textSize = 14f
            setTextColor(0xFF8F99A8.toInt())
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
        }
        page.addView(note, matchWrap())

        setContentView(scroll)
    }

    private fun refreshStatus() {
        val enabled = isTapServiceEnabled()
        accessButton.text = if (enabled) "1. Automatic tapping is enabled" else "1. Enable automatic tapping"
        status.text = if (enabled) {
            "Accessibility service: enabled\nScreen capture: requested when you start a session."
        } else {
            "Accessibility service: not enabled\nEnable it before starting detection. Android will show a system confirmation screen."
        }
    }

    private fun isTapServiceEnabled(): Boolean {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val expectedClass = TapAccessibilityService::class.java.name
        return manager.getEnabledAccessibilityServiceList(AccessibilityManager.FEEDBACK_ALL_MASK).any { info ->
            info.resolveInfo?.serviceInfo?.let { serviceInfo ->
                serviceInfo.packageName == packageName && serviceInfo.name == expectedClass
            } == true
        }
    }

    private fun beginCapturePermission() {
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
        status.text = "Detection is starting. Return to the game; use the notification or this app to stop."
        Toast.makeText(this, "Bag Catcher started", Toast.LENGTH_SHORT).show()
        // The game should be the task immediately behind this helper app.
        window.decorView.postDelayed({ moveTaskToBack(true) }, 450)
    }

    private fun makeButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xFF6941C6.toInt())
        setOnClickListener { action() }
    }

    private fun buttonParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(54)
    ).apply { bottomMargin = dp(12) }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
