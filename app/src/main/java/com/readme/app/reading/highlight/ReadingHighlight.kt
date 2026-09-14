package com.readme.app.reading.highlight

import android.graphics.RectF

/**
 * Coordinate space for reading highlights.
 */
enum class HighlightCoordinateSpace {
    PDF_PAGE,
    SCREEN_WINDOW,
    IMAGE
}

/**
 * Format-neutral highlight model representing the active reading focus geometry.
 *
 * Encapsulates:
 * - Document and segment identity
 * - Physical page index if applicable (e.g. for PDF documents)
 * - One or more rectangular bounds (supporting multi-line sentences)
 * - Explicit coordinate space designation
 */
data class ReadingHighlight(
    val documentId: String,
    val sectionId: String,
    val segmentId: String,
    val pageIndex: Int? = null,
    val rectangles: List<RectF> = emptyList(),
    val coordinateSpace: HighlightCoordinateSpace = HighlightCoordinateSpace.SCREEN_WINDOW
) {
    val isEmpty: Boolean get() = rectangles.isEmpty()
}
