package com.example.bagcatcher

import android.content.Context

/** Shared live settings. Changes apply during a running capture session. */
data class CatcherSettings(
    val confidence: Int = 74,
    val tapDurationMs: Int = 75,
    val leadMs: Int = 12,
    val fallSpeedPxPerSec: Int = 1080,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val scanStep: Int = 2,
    val frameIntervalMs: Int = 55,
    val showOverlay: Boolean = true
) {
    companion object {
        private const val PREFS = "bag_catcher_live_settings"

        fun load(context: Context): CatcherSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return CatcherSettings(
                confidence = p.getInt("confidence", 74).coerceIn(45, 90),
                tapDurationMs = p.getInt("tapDurationMs", 75).coerceIn(25, 180),
                leadMs = p.getInt("leadMs", 12).coerceIn(0, 100),
                fallSpeedPxPerSec = p.getInt("fallSpeedPxPerSec", 1080).coerceIn(400, 1800),
                offsetX = p.getInt("offsetX", 0).coerceIn(-100, 100),
                offsetY = p.getInt("offsetY", 0).coerceIn(-180, 180),
                scanStep = p.getInt("scanStep", 2).coerceIn(1, 4),
                frameIntervalMs = p.getInt("frameIntervalMs", 55).coerceIn(40, 140),
                showOverlay = p.getBoolean("showOverlay", true)
            )
        }

        fun save(context: Context, value: CatcherSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("confidence", value.confidence.coerceIn(45, 90))
                .putInt("tapDurationMs", value.tapDurationMs.coerceIn(25, 180))
                .putInt("leadMs", value.leadMs.coerceIn(0, 100))
                .putInt("fallSpeedPxPerSec", value.fallSpeedPxPerSec.coerceIn(400, 1800))
                .putInt("offsetX", value.offsetX.coerceIn(-100, 100))
                .putInt("offsetY", value.offsetY.coerceIn(-180, 180))
                .putInt("scanStep", value.scanStep.coerceIn(1, 4))
                .putInt("frameIntervalMs", value.frameIntervalMs.coerceIn(40, 140))
                .putBoolean("showOverlay", value.showOverlay)
                .apply()
        }

        fun reset(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
