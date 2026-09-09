package com.readme.app.reading.content.pdf.ocr

import android.util.Size

/**
 * Dimensions in pixels for rasterizing a PDF page.
 */
data class RasterDimensions(val width: Int, val height: Int) {
    fun toSize(): Size = Size(width, height)
}

/**
 * Deterministic rasterization policy for rendering PDF pages for OCR.
 *
 * Establishes a bounded, memory-safe pixel dimension strategy:
 * - Capped at [MAX_DIMENSION] (1600px) on the longest edge to prevent excessive memory allocation
 * - Scaled up to at least [MIN_DIMENSION] (1000px) on the longest edge for low-resolution points
 *   so that standard font sizes remain legible to on-device OCR
 * - Defaults to [DEFAULT_DIMENSIONS] (1200x1600) when page dimensions are unavailable or non-positive
 */
object PdfPageRasterizer {
    const val MAX_DIMENSION = 1600
    const val MIN_DIMENSION = 1000
    val DEFAULT_DIMENSIONS = RasterDimensions(1200, 1600)
    val DEFAULT_SIZE: Size get() = DEFAULT_DIMENSIONS.toSize()

    /**
     * Calculates optimal rasterization [RasterDimensions] for a PDF page given its point dimensions.
     */
    fun calculateDimensions(pageWidth: Int, pageHeight: Int): RasterDimensions {
        if (pageWidth <= 0 || pageHeight <= 0) {
            return DEFAULT_DIMENSIONS
        }

        val maxEdge = maxOf(pageWidth, pageHeight)
        val scale = when {
            maxEdge > MAX_DIMENSION -> MAX_DIMENSION.toFloat() / maxEdge
            maxEdge < MIN_DIMENSION -> minOf(2.0f, MAX_DIMENSION.toFloat() / maxEdge)
            else -> minOf(1.5f, MAX_DIMENSION.toFloat() / maxEdge)
        }

        val targetWidth = (pageWidth * scale).toInt().coerceAtLeast(100)
        val targetHeight = (pageHeight * scale).toInt().coerceAtLeast(100)
        return RasterDimensions(targetWidth, targetHeight)
    }

    /**
     * Calculates optimal rasterization [Size] for a PDF page given its point dimensions.
     */
    fun calculateRasterSize(pageWidth: Int, pageHeight: Int): Size {
        return calculateDimensions(pageWidth, pageHeight).toSize()
    }
}

