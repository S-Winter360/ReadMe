package com.readme.app.accessibility

import android.graphics.Rect
import android.graphics.RectF

/**
 * Deterministic geometry utilities for screen region selection, bitmap cropping,
 * and mapping OCR bounding coordinates back to screen/window coordinate space.
 */
object ScreenGeometryMapper {

    const val DEFAULT_MIN_SIZE_PX = 48

    /**
     * Clamps a raw user selection rectangle within the bounds of the target window.
     * Enforces minimum width and height constraints.
     *
     * @return Clamped [Rect] if valid and meeting minimum size requirements; null if degenerate/too small.
     */
    fun clampRegion(
        selection: Rect,
        windowBounds: Rect,
        minWidth: Int = DEFAULT_MIN_SIZE_PX,
        minHeight: Int = DEFAULT_MIN_SIZE_PX
    ): Rect? {
        val left = selection.left.coerceAtLeast(windowBounds.left)
        val top = selection.top.coerceAtLeast(windowBounds.top)
        val right = selection.right.coerceAtMost(windowBounds.right)
        val bottom = selection.bottom.coerceAtMost(windowBounds.bottom)

        val clamped = Rect(
            minOf(left, right),
            minOf(top, bottom),
            maxOf(left, right),
            maxOf(top, bottom)
        )

        if (clamped.width() < minWidth || clamped.height() < minHeight) {
            return null
        }

        return clamped
    }

    /**
     * Converts a selection rectangle (in screen coordinates) into pixel crop bounds
     * within a captured window bitmap.
     */
    fun calculateCropRect(
        selectionOnScreen: Rect,
        windowBounds: Rect,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): Rect {
        val winW = windowBounds.width().coerceAtLeast(1)
        val winH = windowBounds.height().coerceAtLeast(1)

        val scaleX = bitmapWidth.toFloat() / winW
        val scaleY = bitmapHeight.toFloat() / winH

        val relLeft = (selectionOnScreen.left - windowBounds.left).coerceAtLeast(0)
        val relTop = (selectionOnScreen.top - windowBounds.top).coerceAtLeast(0)
        val relRight = (selectionOnScreen.right - windowBounds.left).coerceAtMost(winW)
        val relBottom = (selectionOnScreen.bottom - windowBounds.top).coerceAtMost(winH)

        val cropLeft = (relLeft * scaleX).toInt().coerceIn(0, (bitmapWidth - 1).coerceAtLeast(0))
        val cropTop = (relTop * scaleY).toInt().coerceIn(0, (bitmapHeight - 1).coerceAtLeast(0))
        val cropRight = (relRight * scaleX).toInt().coerceIn(cropLeft + 1, bitmapWidth)
        val cropBottom = (relBottom * scaleY).toInt().coerceIn(cropTop + 1, bitmapHeight)

        return Rect(cropLeft, cropTop, cropRight, cropBottom)
    }

    /**
     * Maps a bounding box from cropped-bitmap pixel space back into original screen coordinates.
     */
    fun mapRectFromCropToScreen(
        rectInCrop: RectF,
        cropRect: Rect,
        scaleX: Float,
        scaleY: Float,
        windowLeft: Float,
        windowTop: Float
    ): RectF {
        val sX = if (scaleX > 0f) scaleX else 1f
        val sY = if (scaleY > 0f) scaleY else 1f

        val screenLeft = (rectInCrop.left + cropRect.left) / sX + windowLeft
        val screenTop = (rectInCrop.top + cropRect.top) / sY + windowTop
        val screenRight = (rectInCrop.right + cropRect.left) / sX + windowLeft
        val screenBottom = (rectInCrop.bottom + cropRect.top) / sY + windowTop

        return RectF(screenLeft, screenTop, screenRight, screenBottom)
    }

    /**
     * Transforms an entire recognized OCR sentence (including individual line bounds)
     * from cropped-bitmap coordinate space back into screen coordinates.
     */
    fun mapSentenceGeometryToScreen(
        sentence: CrossAppOcrSentence,
        cropRect: Rect,
        scaleX: Float,
        scaleY: Float,
        windowLeft: Float,
        windowTop: Float
    ): CrossAppOcrSentence {
        val mappedBounds = mapRectFromCropToScreen(sentence.bounds, cropRect, scaleX, scaleY, windowLeft, windowTop)
        val mappedLineBounds = sentence.lineBounds.map { lineRect ->
            mapRectFromCropToScreen(lineRect, cropRect, scaleX, scaleY, windowLeft, windowTop)
        }

        return CrossAppOcrSentence(
            text = sentence.text,
            bounds = mappedBounds,
            lineBounds = mappedLineBounds.ifEmpty { listOf(mappedBounds) }
        )
    }
}
