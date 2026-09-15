package com.readme.app.accessibility

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.roundToInt

// =========================================================================
// Explicit Coordinate Spaces (Phase 9P Coordinate-Space Audit)
// =========================================================================

/** 1. Coordinates in the overall physical display space [0, 0, displayWidth, displayHeight]. */
data class DisplayCoordinatesRect(val rect: Rect) {
    val width: Int get() = rect.width()
    val height: Int get() = rect.height()
    val left: Int get() = rect.left
    val top: Int get() = rect.top
    val right: Int get() = rect.right
    val bottom: Int get() = rect.bottom
}

/** 2. Target accessibility window bounds on screen [winLeft, winTop, winRight, winBottom]. */
data class AccessibilityWindowRect(val rect: Rect) {
    val width: Int get() = rect.width()
    val height: Int get() = rect.height()
    val left: Int get() = rect.left
    val top: Int get() = rect.top
    val right: Int get() = rect.right
    val bottom: Int get() = rect.bottom
}

/** 3. Selection overlay coordinates (origin at (0,0) of display due to FLAG_LAYOUT_IN_SCREEN). */
data class SelectionOverlayRect(val rect: Rect) {
    val width: Int get() = rect.width()
    val height: Int get() = rect.height()
    val left: Int get() = rect.left
    val top: Int get() = rect.top
    val right: Int get() = rect.right
    val bottom: Int get() = rect.bottom
}

/** 4. Screenshot pixel coordinates in captured bitmap [0, 0, bitmapWidth, bitmapHeight]. */
data class ScreenshotPixelRect(val rect: Rect) {
    val width: Int get() = rect.width()
    val height: Int get() = rect.height()
    val left: Int get() = rect.left
    val top: Int get() = rect.top
    val right: Int get() = rect.right
    val bottom: Int get() = rect.bottom
}

/** 5. Cropped bitmap pixel coordinates [0, 0, cropWidth, cropHeight]. */
data class CroppedBitmapRect(val rect: Rect) {
    val width: Int get() = rect.width()
    val height: Int get() = rect.height()
    val left: Int get() = rect.left
    val top: Int get() = rect.top
    val right: Int get() = rect.right
    val bottom: Int get() = rect.bottom
}

/** 6. OCR result coordinates relative to the input bitmap passed to ML Kit. */
data class OcrResultRect(val rectF: RectF)

/** 7. Highlight overlay coordinates in display screen space. */
data class HighlightOverlayRect(val rectF: RectF)

/**
 * Deterministic geometry utilities for screen region selection, bitmap cropping,
 * and mapping OCR bounding coordinates back to screen/window coordinate space.
 */
object ScreenGeometryMapper {

    const val DEFAULT_MIN_SIZE_PX = 24
    const val DEFAULT_RECOMMENDED_MIN_OCR_PX = 48

    fun makeRect(l: Int, t: Int, r: Int, b: Int): Rect {
        val rect = Rect()
        rect.left = l
        rect.top = t
        rect.right = r
        rect.bottom = b
        return rect
    }

    fun makeRectF(l: Float, t: Float, r: Float, b: Float): RectF {
        val rect = RectF()
        rect.left = l
        rect.top = t
        rect.right = r
        rect.bottom = b
        return rect
    }

    /**
     * Normalizes a rectangle so that left <= right and top <= bottom,
     * regardless of drag gesture orientation.
     */
    fun normalizeRect(raw: Rect): Rect {
        val l = minOf(raw.left, raw.right)
        val r = maxOf(raw.left, raw.right)
        val t = minOf(raw.top, raw.bottom)
        val b = maxOf(raw.top, raw.bottom)
        return makeRect(l, t, r, b)
    }

    /**
     * Checks if a selection on the display overlaps with the target window bounds.
     */
    fun doesSelectionOverlapWindow(selection: Rect, windowBounds: Rect): Boolean {
        val norm = normalizeRect(selection)
        val winNorm = normalizeRect(windowBounds)
        return norm.left < winNorm.right && norm.right > winNorm.left &&
                norm.top < winNorm.bottom && norm.bottom > winNorm.top
    }

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
        val norm = normalizeRect(selection)
        val winNorm = normalizeRect(windowBounds)

        val left = norm.left.coerceAtLeast(winNorm.left)
        val top = norm.top.coerceAtLeast(winNorm.top)
        val right = norm.right.coerceAtMost(winNorm.right)
        val bottom = norm.bottom.coerceAtMost(winNorm.bottom)

        if (right <= left || bottom <= top) {
            return null
        }

        val w = right - left
        val h = bottom - top
        if (w < minWidth || h < minHeight) {
            return null
        }

        return makeRect(left, top, right, bottom)
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
        val normSelection = normalizeRect(selectionOnScreen)
        val normWindow = normalizeRect(windowBounds)

        val winW = (normWindow.right - normWindow.left).coerceAtLeast(1)
        val winH = (normWindow.bottom - normWindow.top).coerceAtLeast(1)

        val scaleX = bitmapWidth.toFloat() / winW
        val scaleY = bitmapHeight.toFloat() / winH

        // Translate from screen coordinates to window-relative coordinates
        val relLeft = (normSelection.left - normWindow.left).coerceIn(0, winW)
        val relTop = (normSelection.top - normWindow.top).coerceIn(0, winH)
        val relRight = (normSelection.right - normWindow.left).coerceIn(0, winW)
        val relBottom = (normSelection.bottom - normWindow.top).coerceIn(0, winH)

        val cropLeft = (relLeft * scaleX).roundToInt().coerceIn(0, (bitmapWidth - 1).coerceAtLeast(0))
        val cropTop = (relTop * scaleY).roundToInt().coerceIn(0, (bitmapHeight - 1).coerceAtLeast(0))
        val cropRight = (relRight * scaleX).roundToInt().coerceIn(cropLeft + 1, bitmapWidth)
        val cropBottom = (relBottom * scaleY).roundToInt().coerceIn(cropTop + 1, bitmapHeight)

        return makeRect(cropLeft, cropTop, cropRight, cropBottom)
    }

    /**
     * Validates that a crop rectangle is non-empty, inside bitmap bounds, and meets minimum dimensions.
     */
    fun isCropValid(
        cropRect: Rect,
        bitmapWidth: Int,
        bitmapHeight: Int,
        minWidth: Int = DEFAULT_MIN_SIZE_PX,
        minHeight: Int = DEFAULT_MIN_SIZE_PX
    ): Boolean {
        if (cropRect.left < 0 || cropRect.top < 0) return false
        if (cropRect.right > bitmapWidth || cropRect.bottom > bitmapHeight) return false
        val w = cropRect.right - cropRect.left
        val h = cropRect.bottom - cropRect.top
        return w >= minWidth && h >= minHeight
    }

    /**
     * Maps a bounding box from cropped-bitmap pixel space back into original screen coordinates.
     * Takes into account an optional [ocrUpscaleFactor] applied to the cropped bitmap before OCR.
     */
    fun mapRectFromCropToScreen(
        rectInCrop: RectF,
        cropRect: Rect,
        scaleX: Float,
        scaleY: Float,
        windowLeft: Float,
        windowTop: Float,
        ocrUpscaleFactor: Float = 1.0f
    ): RectF {
        val sX = if (scaleX > 0f) scaleX else 1f
        val sY = if (scaleY > 0f) scaleY else 1f
        val up = if (ocrUpscaleFactor > 0f) ocrUpscaleFactor else 1.0f

        // 1. If upscaled for OCR, unscale back to standard crop pixel coordinates
        val unscaledLeft = rectInCrop.left / up
        val unscaledTop = rectInCrop.top / up
        val unscaledRight = rectInCrop.right / up
        val unscaledBottom = rectInCrop.bottom / up

        // 2. Map to screenshot bitmap space by adding crop offset
        val screenshotX1 = unscaledLeft + cropRect.left
        val screenshotY1 = unscaledTop + cropRect.top
        val screenshotX2 = unscaledRight + cropRect.left
        val screenshotY2 = unscaledBottom + cropRect.top

        // 3. Map to window space by dividing by scale factor
        val winRelX1 = screenshotX1 / sX
        val winRelY1 = screenshotY1 / sY
        val winRelX2 = screenshotX2 / sX
        val winRelY2 = screenshotY2 / sY

        // 4. Map to screen display space by adding window offset
        val screenLeft = winRelX1 + windowLeft
        val screenTop = winRelY1 + windowTop
        val screenRight = winRelX2 + windowLeft
        val screenBottom = winRelY2 + windowTop

        return makeRectF(screenLeft, screenTop, screenRight, screenBottom)
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
        windowTop: Float,
        ocrUpscaleFactor: Float = 1.0f
    ): CrossAppOcrSentence {
        val mappedBounds = mapRectFromCropToScreen(
            sentence.bounds,
            cropRect,
            scaleX,
            scaleY,
            windowLeft,
            windowTop,
            ocrUpscaleFactor
        )
        val mappedLineBounds = sentence.lineBounds.map { lineRect ->
            mapRectFromCropToScreen(
                lineRect,
                cropRect,
                scaleX,
                scaleY,
                windowLeft,
                windowTop,
                ocrUpscaleFactor
            )
        }

        return CrossAppOcrSentence(
            text = sentence.text,
            bounds = mappedBounds,
            lineBounds = mappedLineBounds.ifEmpty { listOf(mappedBounds) }
        )
    }
}

