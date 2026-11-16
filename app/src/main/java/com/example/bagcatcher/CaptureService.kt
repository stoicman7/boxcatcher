package com.example.bagcatcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import java.nio.ByteBuffer
import kotlin.math.max

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var detector: BagDetector? = null
    private val tracker = BagTracker()
    private var lastProcessedMs = 0L
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var workWidth = 360
    private var workHeight = 760
    private var isCapturing = false
    @Volatile private var autoTapEnabled = false
    @Volatile private var lastDetectedCount = 0
    @Volatile private var completedTapCount = 0
    @Volatile private var lastFrameError = false
    private var lastNotificationUpdateMs = 0L
    private var floatingControls: FloatingControlOverlay? = null
    private var detectionOverlay: DetectionOverlay? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCaptureResources()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = getResultData(intent)
        if (resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startCapture(resultCode, resultData)
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun getResultData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (isCapturing) stopCaptureResources()
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val newProjection = manager.getMediaProjection(resultCode, resultData)
        if (newProjection == null) {
            stopSelf()
            return
        }
        projection = newProjection

        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        sourceWidth = metrics.widthPixels
        sourceHeight = metrics.heightPixels
        workWidth = minOf(360, sourceWidth).coerceAtLeast(180)
        workHeight = max(1, (sourceHeight * workWidth.toFloat() / sourceWidth).toInt())

        captureThread = HandlerThread("BagCatcherFrames").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        detector = BagDetector(this)

        val reader = ImageReader.newInstance(sourceWidth, sourceHeight, PixelFormat.RGBA_8888, 3)
        imageReader = reader
        reader.setOnImageAvailableListener({ imageSource -> processLatestImage(imageSource) }, captureHandler)

        newProjection.registerCallback(projectionCallback, captureHandler)
        virtualDisplay = newProjection.createVirtualDisplay(
            "BagCatcherCapture",
            sourceWidth,
            sourceHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        )
        isCapturing = virtualDisplay != null
        lastProcessedMs = 0L
        lastDetectedCount = 0
        completedTapCount = 0
        lastFrameError = false
        CatcherDiagnostics.startSession()

        // Detection remains active while taps are paused. This makes it possible
        // to inspect matches and tune settings without accidentally tapping.
        autoTapEnabled = true
        showFloatingControls()
        refreshSessionStatus(true)
    }

    private fun showFloatingControls() {
        floatingControls?.remove()
        detectionOverlay?.remove()

        floatingControls = FloatingControlOverlay(this, autoTapEnabled, { enabled ->
            autoTapEnabled = enabled
            CatcherDiagnostics.note(if (enabled) "Automatic tapping activated" else "Automatic tapping paused; visual detection continues")
        }, {
            val debugIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(debugIntent)
        })

        detectionOverlay = DetectionOverlay(this).also { it.attach() }
    }

    private fun processLatestImage(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return

        try {
            val now = SystemClock.elapsedRealtime()
            val settings = CatcherSettings.load(this)
            if (now - lastProcessedMs < settings.frameIntervalMs.toLong()) return
            lastProcessedMs = now

            val gray = imageToGray(image, workWidth, workHeight)
            if (gray == null) {
                lastFrameError = true
                CatcherDiagnostics.frameError("Could not convert captured image to grayscale")
                refreshSessionStatus()
                return
            }

            val activeDetector = detector ?: return
            val detections = activeDetector.find(
                gray,
                workWidth,
                workHeight,
                settings.confidence,
                settings.scanStep
            )
            lastDetectedCount = detections.size
            lastFrameError = false
            CatcherDiagnostics.frame(activeDetector.lastRawCandidateCount, detections.size)

            val requests = tracker.update(
                detections,
                now,
                sourceWidth,
                sourceHeight,
                workWidth,
                workHeight,
                settings.leadMs,
                settings.fallSpeedPxPerSec,
                settings.offsetX,
                settings.offsetY
            )

            // Draw the exact detected template bounds and the exact adjusted
            // screen coordinate that would be tapped next.
            val target = requests.firstOrNull()
            detectionOverlay?.update(
                detections,
                sourceWidth.toFloat() / workWidth,
                sourceHeight.toFloat() / workHeight,
                target?.x,
                target?.y,
                settings.showOverlay
            )

            // Pausing auto taps does not pause frame analysis or the visual overlay.
            if (autoTapEnabled && target != null) {
                CatcherDiagnostics.tapAttempt(target.x, target.y, settings.tapDurationMs)
                val accepted = TapAccessibilityService.sendTap(target.x, target.y, settings.tapDurationMs) { completed ->
                    captureHandler?.post {
                        if (completed) {
                            tracker.markTapped(listOf(target.trackId))
                            completedTapCount++
                            CatcherDiagnostics.gestureCompleted()
                        } else {
                            CatcherDiagnostics.gestureCancelled()
                        }
                        refreshSessionStatus(true)
                    }
                }
                if (accepted) {
                    CatcherDiagnostics.dispatchAccepted()
                } else {
                    CatcherDiagnostics.dispatchRejected()
                    refreshSessionStatus()
                }
            } else {
                refreshSessionStatus()
            }
        } catch (error: Exception) {
            lastFrameError = true
            CatcherDiagnostics.frameError(error.javaClass.simpleName + ": " + (error.message ?: "unknown"))
            refreshSessionStatus()
            // Keep capture alive if an individual frame is malformed.
        } finally {
            try {
                image.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun imageToGray(image: Image, outWidth: Int, outHeight: Int): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride < 4 || rowStride <= 0) return null

        val output = ByteArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            val sourceY0 = (y.toLong() * image.height / outHeight).toInt().coerceAtMost(image.height - 1)
            val sourceY1 = (((y + 1L) * image.height / outHeight).toInt()).coerceAtLeast(sourceY0 + 1).coerceAtMost(image.height)
            val outputRow = y * outWidth
            for (x in 0 until outWidth) {
                val sourceX0 = (x.toLong() * image.width / outWidth).toInt().coerceAtMost(image.width - 1)
                val sourceX1 = (((x + 1L) * image.width / outWidth).toInt()).coerceAtLeast(sourceX0 + 1).coerceAtMost(image.width)
                var redSum = 0
                var greenSum = 0
                var blueSum = 0
                var count = 0
                for (sy in sourceY0 until sourceY1) {
                    val rowOffset = sy * rowStride
                    for (sx in sourceX0 until sourceX1) {
                        val offset = rowOffset + sx * pixelStride
                        redSum += buffer.get(offset).toInt() and 0xFF
                        greenSum += buffer.get(offset + 1).toInt() and 0xFF
                        blueSum += buffer.get(offset + 2).toInt() and 0xFF
                        count++
                    }
                }
                if (count == 0) count = 1
                val red = redSum / count
                val green = greenSum / count
                val blue = blueSum / count
                output[outputRow + x] = ((77 * red + 150 * green + 29 * blue) shr 8).toByte()
            }
        }
        return output
    }

    private fun refreshSessionStatus(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationUpdateMs < 900L) return
        lastNotificationUpdateMs = now

        val tapReady = TapAccessibilityService.isConnected()
        val state = when {
            !tapReady -> "Accessibility service disconnected"
            autoTapEnabled -> "AUTO TAPS ON"
            else -> "AUTO TAPS PAUSED · DETECTION ON"
        }
        val snapshot = CatcherDiagnostics.snapshot()
        val detail = "$state · boxes: $lastDetectedCount · gestures completed: $completedTapCount" +
            if (lastFrameError) " · frame error" else ""

        floatingControls?.updateStats(
            lastDetectedCount,
            completedTapCount,
            tapReady,
            autoTapEnabled,
            snapshot.tapAttempts,
            snapshot.dispatchRejected
        )

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(detail))
    }

    private fun buildNotification(
        detail: String = "Tap the floating BC circle to open controls and live calibration."
    ): Notification {
        val stopIntent = Intent(this, CaptureService::class.java).apply { action = ACTION_STOP }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val stopPendingIntent = PendingIntent.getService(this, 21, stopIntent, flags)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            22,
            Intent(this, MainActivity::class.java),
            flags
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Bag Catcher is active")
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bag Catcher session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while Bag Catcher is detecting gift boxes"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun stopCaptureResources() {
        isCapturing = false
        autoTapEnabled = false
        lastDetectedCount = 0
        floatingControls?.remove()
        floatingControls = null
        detectionOverlay?.remove()
        detectionOverlay = null
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        val oldProjection = projection
        projection = null
        try { oldProjection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { oldProjection?.stop() } catch (_: Exception) {}
        try { captureThread?.quitSafely() } catch (_: Exception) {}
        captureThread = null
        captureHandler = null
        detector = null
    }

    override fun onDestroy() {
        stopCaptureResources()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.bagcatcher.action.START"
        const val ACTION_STOP = "com.example.bagcatcher.action.STOP"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val CHANNEL_ID = "bagcatcher_capture"
        private const val NOTIFICATION_ID = 4701
    }
}
