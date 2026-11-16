package com.example.bagcatcher

import android.content.Context
import java.io.File
import kotlin.math.abs

/**
 * Small on-device positive-example store. Only game-screen crops around taps
 * made explicitly while Learning Mode is enabled are saved.
 */
object LearnedTemplateStore {
    const val PATCH_SIZE = 40
    private const val MAX_TEMPLATES = 4
    private const val PREFS = "bag_catcher_learned_data"
    private const val REVISION_KEY = "revision"

    fun count(context: Context): Int = templateFiles(context).size

    fun revision(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(REVISION_KEY, 0)

    @Synchronized
    fun addPositive(
        context: Context,
        gray: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        centerX: Int,
        centerY: Int
    ): Boolean {
        if (frameWidth < PATCH_SIZE || frameHeight < PATCH_SIZE ||
            gray.size < frameWidth * frameHeight) return false

        val left = (centerX - PATCH_SIZE / 2).coerceIn(0, frameWidth - PATCH_SIZE)
        val top = (centerY - PATCH_SIZE / 2).coerceIn(0, frameHeight - PATCH_SIZE)
        val patch = ByteArray(PATCH_SIZE * PATCH_SIZE)
        var sum = 0L
        var sumSquares = 0L
        for (y in 0 until PATCH_SIZE) {
            for (x in 0 until PATCH_SIZE) {
                val value = gray[(top + y) * frameWidth + left + x].toInt() and 0xFF
                patch[y * PATCH_SIZE + x] = value.toByte()
                sum += value
                sumSquares += value.toLong() * value
            }
        }

        val n = patch.size.toDouble()
        val variance = sumSquares - (sum.toDouble() * sum / n)
        if (variance < 18000.0) return false

        val existing = templateFiles(context)
        for (file in existing) {
            val old = try { file.readBytes() } catch (_: Exception) { continue }
            if (old.size != patch.size) continue
            var difference = 0L
            for (i in patch.indices) {
                difference += abs((patch[i].toInt() and 0xFF) - (old[i].toInt() and 0xFF))
            }
            if (difference.toDouble() / patch.size < 5.0) return false
        }

        val directory = directory(context)
        if (!directory.exists() && !directory.mkdirs()) return false
        val file = File(directory, "positive_" + System.currentTimeMillis() + ".bin")
        return try {
            file.writeBytes(patch)
            val files = templateFiles(context)
            while (files.size > MAX_TEMPLATES) files.first().delete()
            bumpRevision(context)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun loadPatches(context: Context): List<ByteArray> =
        templateFiles(context).mapNotNull { file ->
            try {
                file.readBytes().takeIf { it.size == PATCH_SIZE * PATCH_SIZE }
            } catch (_: Exception) {
                null
            }
        }

    @Synchronized
    fun undoLast(context: Context): Boolean {
        val latest = templateFiles(context).lastOrNull() ?: return false
        val removed = latest.delete()
        if (removed) bumpRevision(context)
        return removed
    }

    @Synchronized
    fun clear(context: Context) {
        templateFiles(context).forEach { it.delete() }
        bumpRevision(context)
    }

    private fun templateFiles(context: Context): List<File> =
        directory(context).listFiles()
            ?.filter { it.isFile && it.name.startsWith("positive_") && it.name.endsWith(".bin") }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun directory(context: Context): File =
        File(context.filesDir, "learned_templates")

    private fun bumpRevision(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(REVISION_KEY, revision(context) + 1)
            .apply()
    }
}
