package com.readme.app.ui.overlay

import android.graphics.Rect

/**
 * Deterministic geometry model and calculation engine for screen region selection and bounding boxes.
 */
data class RegionRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = maxOf(0, right - left)
    val height: Int get() = maxOf(0, bottom - top)
    val isValid: Boolean get() = width > 0 && height > 0

    fun toAndroidRect(): Rect = Rect(left, top, right, bottom)

    companion object {
        fun fromRect(rect: Rect): RegionRect {
            return RegionRect(rect.left, rect.top, rect.right, rect.bottom)
        }

        fun normalizeAndClamp(
            startX: Float,
            startY: Float,
            currentX: Float,
            currentY: Float,
            boundLeft: Int,
            boundTop: Int,
            boundRight: Int,
            boundBottom: Int
        ): RegionRect {
            val minX = minOf(startX, currentX).toInt().coerceIn(boundLeft, boundRight)
            val minY = minOf(startY, currentY).toInt().coerceIn(boundTop, boundBottom)
            val maxX = maxOf(startX, currentX).toInt().coerceIn(boundLeft, boundRight)
            val maxY = maxOf(startY, currentY).toInt().coerceIn(boundTop, boundBottom)
            return RegionRect(
                left = minX,
                top = minY,
                right = maxX,
                bottom = maxY
            )
        }
    }
}
