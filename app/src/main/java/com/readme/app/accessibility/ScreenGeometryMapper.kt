package com.readme.app.accessibility

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.roundToInt

// =========================================================================
// Explicit Coordinate Spaces (Phase 9P Coordinate-Space Audit)
// =========================================================================

/** 1. Coordinates in the overall physical display space [0, 0, displayWidth, displayHeight]. */
data class DisplayCoordinatesRect(val rect: Rect) {
    val width: Int get() = rect.right - rect.left
    val height: Int get() = rect.bottom - rect.top
    val left: Int get() = rect.left
    val top: Int get() = rect.top
    val right: Int get() = rect.right
    val bottom: Int get() = rect.bottom
}

/** 2. Target accessibility window bounds on screen [winLeft, winTop, winRight, winBottom]. */
data class AccessibilityWindowRect(val rect: Rect) {
    val width: Int get() = rect.right - rect.left
    val height: Int get() = rect.bottom - rect.top
    val left: Int get() = rect.left
    val top: Int get() = rect.top
    val right: Int get() = rect.right
    val bottom: Int get() = rect.bottom
}

/** 3. Selection overlay coordinates (origin at (0,0) of display due to FLAG_LAYOUT_IN_SCREEN). */
data class SelectionOverlayRect(val rect: Rect) {
    val width: Int get() = rect.right - rect.left
    val height: Int get() = rect.bottom - rect.top
    val left: Int get() = rect.left
    val top: Int get() = rect.top
    val right: Int get() = rect.right
    val bottom: Int get() = rect.bottom
}

/** 4. Screenshot pixel coordinates in captured bitmap [0, 0, bitmapWidth, bitmapHeight]. */
data class ScreenshotPixelRect(val rect: Rect) {
    val width: Int get() = rect.right - rect.left
    val height: Int get() = rect.bottom - rect.top
    val left: Int get() = rect.left
    val top: Int get() = rect.top
    val right: Int get() = rect.right
    val bottom: Int get() = rect.bottom
}

/** 5. Cropped bitmap pixel coordinates [0, 0, cropWidth, cropHeight]. */
data class CroppedBitmapRect(val rect: Rect) {
    val width: Int get() = rect.right - rect.left
    val height: Int get() = rect.bottom - rect.top
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

    /**
     * Resolves the true physical display bounds in pixels regardless of window insets,
     * status bars, navigation bars, or display cutouts.
     */
    fun getRealDisplayBounds(context: Context): Rect {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val bounds = wm.maximumWindowMetrics.bounds
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        return Rect(0, 0, bounds.width(), bounds.height())
                    }
                } catch (_: Throwable) {}
            }
            try {
                @Suppress("DEPRECATION")
                val display = wm.defaultDisplay
                if (display != null) {
                    val realDm = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    display.getRealMetrics(realDm)
                    if (realDm.widthPixels > 0 && realDm.heightPixels > 0) {
                        return Rect(0, 0, realDm.widthPixels, realDm.heightPixels)
                    }
                }
            } catch (_: Throwable) {}
        }
        val dm = context.resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

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
     * Determines the coordinate origin of the captured screenshot.
     */
    enum class ScreenshotOriginMode {
        DISPLAY_ORIGIN,
        WINDOW_ORIGIN
    }

    /**
     * Determines whether a captured screenshot is anchored to the physical display origin (0,0)
     * or the target window's top-left origin.
     */
    fun determineOriginMode(
        bitmapWidth: Int,
        bitmapHeight: Int,
        windowBounds: Rect,
        displayWidth: Int = 0,
        displayHeight: Int = 0
    ): ScreenshotOriginMode {
        val normWindow = normalizeRect(windowBounds)
        val winW = (normWindow.right - normWindow.left).coerceAtLeast(1)
        val winH = (normWindow.bottom - normWindow.top).coerceAtLeast(1)

        // 1. Exact match with display dimensions
        if (displayWidth > 0 && displayHeight > 0) {
            val matchesDisplay = kotlin.math.abs(bitmapWidth - displayWidth) <= 2 &&
                    kotlin.math.abs(bitmapHeight - displayHeight) <= 2
            val matchesWindow = kotlin.math.abs(bitmapWidth - winW) <= 2 &&
                    kotlin.math.abs(bitmapHeight - winH) <= 2

            if (matchesDisplay && !matchesWindow) {
                return ScreenshotOriginMode.DISPLAY_ORIGIN
            }
            if (matchesWindow && !matchesDisplay) {
                return ScreenshotOriginMode.WINDOW_ORIGIN
            }
        }

        // 2. If bitmap dimensions exceed window bounds (e.g. including system bars / display height),
        // it is a display-origin capture.
        if (bitmapHeight >= winH + 8 || bitmapWidth >= winW + 8) {
            return ScreenshotOriginMode.DISPLAY_ORIGIN
        }

        // 3. If window origin is (0, 0), both display-origin and window-origin math produce identical results
        if (normWindow.left == 0 && normWindow.top == 0) {
            return ScreenshotOriginMode.DISPLAY_ORIGIN
        }

        // 4. Default to WINDOW_ORIGIN if bitmap matches window dimensions
        return ScreenshotOriginMode.WINDOW_ORIGIN
    }

    /**
     * Converts a selection rectangle (in screen coordinates) into pixel crop bounds
     * within a captured window or display bitmap.
     */
    fun calculateCropRect(
        selectionOnScreen: Rect,
        windowBounds: Rect,
        bitmapWidth: Int,
        bitmapHeight: Int,
        displayWidth: Int = 0,
        displayHeight: Int = 0,
        originMode: ScreenshotOriginMode = determineOriginMode(
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight,
            windowBounds = windowBounds,
            displayWidth = displayWidth,
            displayHeight = displayHeight
        )
    ): Rect {
        val normSelection = normalizeRect(selectionOnScreen)
        val normWindow = normalizeRect(windowBounds)

        return if (originMode == ScreenshotOriginMode.DISPLAY_ORIGIN) {
            val effectiveDispW = if (displayWidth > 0) displayWidth else (normWindow.right - normWindow.left).coerceAtLeast(1)
            val effectiveDispH = if (displayHeight > 0) displayHeight else (normWindow.bottom - normWindow.top).coerceAtLeast(1)
            val scaleX = bitmapWidth.toFloat() / effectiveDispW.coerceAtLeast(1)
            val scaleY = bitmapHeight.toFloat() / effectiveDispH.coerceAtLeast(1)

            val cropLeft = (normSelection.left * scaleX).roundToInt().coerceIn(0, bitmapWidth)
            val cropTop = (normSelection.top * scaleY).roundToInt().coerceIn(0, bitmapHeight)
            val cropRight = (normSelection.right * scaleX).roundToInt().coerceIn(cropLeft, bitmapWidth)
            val cropBottom = (normSelection.bottom * scaleY).roundToInt().coerceIn(cropTop, bitmapHeight)

            makeRect(cropLeft, cropTop, cropRight, cropBottom)
        } else {
            val winW = (normWindow.right - normWindow.left).coerceAtLeast(1)
            val winH = (normWindow.bottom - normWindow.top).coerceAtLeast(1)

            val scaleX = bitmapWidth.toFloat() / winW
            val scaleY = bitmapHeight.toFloat() / winH

            // Translate from screen coordinates to window-relative coordinates
            val relLeft = (normSelection.left - normWindow.left).coerceIn(0, winW)
            val relTop = (normSelection.top - normWindow.top).coerceIn(0, winH)
            val relRight = (normSelection.right - normWindow.left).coerceIn(0, winW)
            val relBottom = (normSelection.bottom - normWindow.top).coerceIn(0, winH)

            val cropLeft = (relLeft * scaleX).roundToInt().coerceIn(0, bitmapWidth)
            val cropTop = (relTop * scaleY).roundToInt().coerceIn(0, bitmapHeight)
            val cropRight = (relRight * scaleX).roundToInt().coerceIn(cropLeft, bitmapWidth)
            val cropBottom = (relBottom * scaleY).roundToInt().coerceIn(cropTop, bitmapHeight)

            makeRect(cropLeft, cropTop, cropRight, cropBottom)
        }
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
        ocrUpscaleFactor: Float = 1.0f,
        originMode: ScreenshotOriginMode = ScreenshotOriginMode.WINDOW_ORIGIN
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

        // 3. For DISPLAY_ORIGIN, screenshot space IS display space (scaled by scale factor)
        // For WINDOW_ORIGIN, add windowLeft and windowTop offset
        val offsetX = if (originMode == ScreenshotOriginMode.WINDOW_ORIGIN) windowLeft else 0f
        val offsetY = if (originMode == ScreenshotOriginMode.WINDOW_ORIGIN) windowTop else 0f

        val screenLeft = (screenshotX1 / sX) + offsetX
        val screenTop = (screenshotY1 / sY) + offsetY
        val screenRight = (screenshotX2 / sX) + offsetX
        val screenBottom = (screenshotY2 / sY) + offsetY

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
        ocrUpscaleFactor: Float = 1.0f,
        originMode: ScreenshotOriginMode = ScreenshotOriginMode.WINDOW_ORIGIN
    ): CrossAppOcrSentence {
        val mappedBounds = mapRectFromCropToScreen(
            rectInCrop = sentence.bounds,
            cropRect = cropRect,
            scaleX = scaleX,
            scaleY = scaleY,
            windowLeft = windowLeft,
            windowTop = windowTop,
            ocrUpscaleFactor = ocrUpscaleFactor,
            originMode = originMode
        )
        val mappedLineBounds = sentence.lineBounds.map { lineRect ->
            mapRectFromCropToScreen(
                rectInCrop = lineRect,
                cropRect = cropRect,
                scaleX = scaleX,
                scaleY = scaleY,
                windowLeft = windowLeft,
                windowTop = windowTop,
                ocrUpscaleFactor = ocrUpscaleFactor,
                originMode = originMode
            )
        }

        return CrossAppOcrSentence(
            text = sentence.text,
            bounds = mappedBounds,
            lineBounds = mappedLineBounds.ifEmpty { listOf(mappedBounds) }
        )
    }
}

