package com.readme.app.ui.pdf

/**
 * Observable visual state model for the current PDF viewport.
 * Captures first visible page index, visible page count, and zoom level.
 * In Phase 8C, this serves as an observable state model without triggering
 * speech or automatic page navigation.
 */
data class PdfViewportState(
    val firstVisiblePage: Int = 0,
    val visiblePagesCount: Int = 0,
    val zoom: Float = 1.0f
)
