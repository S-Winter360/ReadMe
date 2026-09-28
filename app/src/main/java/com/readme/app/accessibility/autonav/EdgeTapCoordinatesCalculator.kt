package com.readme.app.accessibility.autonav

import android.graphics.Rect

/**
 * Calculates deterministic screen-edge tap coordinates for paginated novel readers.
 *
 * Implements Phase 9AC Sections 4, 5, 6:
 * - Dynamic relative positioning based on actual reader viewport dimensions.
 * - Next page tap at ~92% width, previous page tap at ~8% width.
 * - Clamped within reader content area, avoiding status bar, toolbar, and bottom system navigation.
 * - Uses direct field arithmetic (right - left, bottom - top) for 100% deterministic cross-platform JVM & device execution.
 */
object EdgeTapCoordinatesCalculator {

    const val DEFAULT_NEXT_PAGE_X_RATIO = 0.92f
    const val DEFAULT_PREV_PAGE_X_RATIO = 0.08f
    const val DEFAULT_CENTER_Y_RATIO = 0.50f

    /**
     * Calculates screen coordinates (in physical pixels) for a next-page tap.
     */
    fun calculateNextPageTap(
        readerBounds: Rect,
        readingRegion: Rect? = null,
        bubbleBounds: Rect? = null
    ): Pair<Float, Float> {
        val w = (readerBounds.right - readerBounds.left).toFloat()
        val h = (readerBounds.bottom - readerBounds.top).toFloat()

        val rawX = readerBounds.left + (w * DEFAULT_NEXT_PAGE_X_RATIO)
        val x = rawX.coerceIn(readerBounds.left + 24f, readerBounds.right - 24f)

        val regH = readingRegion?.let { (it.bottom - it.top).toFloat() } ?: 0f
        val regCenterY = readingRegion?.let { it.top + (it.bottom - it.top) / 2f } ?: 0f
        val readerCenterY = readerBounds.top + (h / 2f)

        val targetY = if (readingRegion != null && regH > 40f) {
            regCenterY
        } else {
            readerCenterY
        }

        // Avoid top 12% (toolbars/status bar) and bottom 12% (navigation bar/ad banners)
        val topPadding = (h * 0.12f).coerceAtLeast(48f)
        val bottomPadding = (h * 0.12f).coerceAtLeast(48f)
        val minY = readerBounds.top + topPadding
        val maxY = readerBounds.bottom - bottomPadding
        var y = targetY.coerceIn(minY, maxY)

        // If the tap point conflicts with the floating bubble, shift vertically away from bubble
        if (bubbleBounds != null && isPointInsideRect(x.toInt(), y.toInt(), bubbleBounds)) {
            val bubbleCenterY = bubbleBounds.top + (bubbleBounds.bottom - bubbleBounds.top) / 2
            y = if (y < bubbleCenterY) {
                (bubbleBounds.top - 32f).coerceAtLeast(minY)
            } else {
                (bubbleBounds.bottom + 32f).coerceAtMost(maxY)
            }
        }

        return Pair(x, y)
    }

    /**
     * Calculates screen coordinates (in physical pixels) for a previous-page tap.
     */
    fun calculatePreviousPageTap(
        readerBounds: Rect,
        readingRegion: Rect? = null,
        bubbleBounds: Rect? = null
    ): Pair<Float, Float> {
        val w = (readerBounds.right - readerBounds.left).toFloat()
        val h = (readerBounds.bottom - readerBounds.top).toFloat()

        val rawX = readerBounds.left + (w * DEFAULT_PREV_PAGE_X_RATIO)
        val x = rawX.coerceIn(readerBounds.left + 24f, readerBounds.right - 24f)

        val regH = readingRegion?.let { (it.bottom - it.top).toFloat() } ?: 0f
        val regCenterY = readingRegion?.let { it.top + (it.bottom - it.top) / 2f } ?: 0f
        val readerCenterY = readerBounds.top + (h / 2f)

        val targetY = if (readingRegion != null && regH > 40f) {
            regCenterY
        } else {
            readerCenterY
        }

        val topPadding = (h * 0.12f).coerceAtLeast(48f)
        val bottomPadding = (h * 0.12f).coerceAtLeast(48f)
        val minY = readerBounds.top + topPadding
        val maxY = readerBounds.bottom - bottomPadding
        var y = targetY.coerceIn(minY, maxY)

        if (bubbleBounds != null && isPointInsideRect(x.toInt(), y.toInt(), bubbleBounds)) {
            val bubbleCenterY = bubbleBounds.top + (bubbleBounds.bottom - bubbleBounds.top) / 2
            y = if (y < bubbleCenterY) {
                (bubbleBounds.top - 32f).coerceAtLeast(minY)
            } else {
                (bubbleBounds.bottom + 32f).coerceAtMost(maxY)
            }
        }

        return Pair(x, y)
    }

    private fun isPointInsideRect(x: Int, y: Int, rect: Rect): Boolean {
        return x in rect.left..rect.right && y in rect.top..rect.bottom
    }
}
