package com.readme.app.accessibility.autonav

import android.content.res.Configuration
import android.graphics.Rect

/**
 * Represents a user-calibrated, app-specific page-turn tap location according to Phase 9AD.
 *
 * Stores:
 * - [packageName]: The target reader application package name.
 * - [relativeX]: Fractional horizontal tap position within the reader / display [0.0..1.0].
 * - [relativeY]: Fractional vertical tap position within the reader / display [0.0..1.0].
 * - [absoluteX]: Physical screen pixel X recorded at calibration.
 * - [absoluteY]: Physical screen pixel Y recorded at calibration.
 * - [screenWidth]: Screen / display width in pixels at calibration time.
 * - [screenHeight]: Screen / display height in pixels at calibration time.
 * - [orientation]: Display orientation at calibration (Portrait vs Landscape).
 * - [readerBounds]: Container or window bounds recorded at calibration time (optional).
 * - [calibratedTimestamp]: Epoch timestamp when user tapped the calibration target.
 */
data class PageTurnCalibration(
    val packageName: String,
    val relativeX: Float,
    val relativeY: Float,
    val absoluteX: Float = 0f,
    val absoluteY: Float = 0f,
    val screenWidth: Int = 1080,
    val screenHeight: Int = 1920,
    val orientation: Int = Configuration.ORIENTATION_PORTRAIT,
    val readerBounds: Rect? = null,
    val calibratedTimestamp: Long = System.currentTimeMillis()
) {

    /**
     * Resolves the physical tap coordinates (x, y) dynamically for current reader or screen bounds.
     * Enforces safety margins so tap remains well within interactive reader canvas.
     */
    fun resolveTapPoint(
        readerBounds: Rect?,
        displayBounds: Rect
    ): Pair<Float, Float> {
        val rbW = if (readerBounds != null) readerBounds.right - readerBounds.left else 0
        val rbH = if (readerBounds != null) readerBounds.bottom - readerBounds.top else 0

        val targetBounds = if (readerBounds != null && rbW > 100 && rbH > 100) {
            readerBounds
        } else {
            displayBounds
        }

        val bW = (targetBounds.right - targetBounds.left).toFloat()
        val bH = (targetBounds.bottom - targetBounds.top).toFloat()

        // Map fractional coordinate to target bounds
        val rawX = targetBounds.left + (bW * relativeX)
        val rawY = targetBounds.top + (bH * relativeY)

        // Safety clamp: keep at least 24px away from screen/container edge
        val clampedX = rawX.coerceIn(targetBounds.left + 24f, targetBounds.right - 24f)
        val clampedY = rawY.coerceIn(targetBounds.top + 32f, targetBounds.bottom - 32f)

        return Pair(clampedX, clampedY)
    }

    fun toJson(): String {
        val bStr = if (readerBounds != null) {
            "${readerBounds.left},${readerBounds.top},${readerBounds.right},${readerBounds.bottom}"
        } else "null"
        return "pkg=$packageName;rx=$relativeX;ry=$relativeY;ax=$absoluteX;ay=$absoluteY;sw=$screenWidth;sh=$screenHeight;orient=$orientation;ts=$calibratedTimestamp;b=$bStr"
    }

    companion object {
        fun fromJson(raw: String): PageTurnCalibration? {
            if (raw.isBlank()) return null
            return try {
                val map = mutableMapOf<String, String>()
                raw.split(";").forEach { part ->
                    val idx = part.indexOf("=")
                    if (idx > 0) {
                        map[part.substring(0, idx).trim()] = part.substring(idx + 1).trim()
                    }
                }
                val pkg = map["pkg"] ?: return null
                val rx = map["rx"]?.toFloatOrNull() ?: return null
                val ry = map["ry"]?.toFloatOrNull() ?: return null
                val ax = map["ax"]?.toFloatOrNull() ?: 0f
                val ay = map["ay"]?.toFloatOrNull() ?: 0f
                val sw = map["sw"]?.toIntOrNull() ?: 1080
                val sh = map["sh"]?.toIntOrNull() ?: 1920
                val orient = map["orient"]?.toIntOrNull() ?: Configuration.ORIENTATION_PORTRAIT
                val ts = map["ts"]?.toLongOrNull() ?: System.currentTimeMillis()
                val boundsStr = map["b"]
                val bounds = if (!boundsStr.isNullOrBlank() && boundsStr != "null") {
                    val coords = boundsStr.split(",")
                    if (coords.size == 4) {
                        Rect().apply {
                            left = coords[0].toInt()
                            top = coords[1].toInt()
                            right = coords[2].toInt()
                            bottom = coords[3].toInt()
                        }
                    } else null
                } else null

                PageTurnCalibration(
                    packageName = pkg,
                    relativeX = rx,
                    relativeY = ry,
                    absoluteX = ax,
                    absoluteY = ay,
                    screenWidth = sw,
                    screenHeight = sh,
                    orientation = orient,
                    readerBounds = bounds,
                    calibratedTimestamp = ts
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}
