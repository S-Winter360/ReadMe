package com.readme.app.ui.pdf

/**
 * Lightweight abstraction isolating PDF page navigation from Android View internals.
 * Allows ReadMeViewModel and synchronization logic to request page navigation
 * without directly depending on Android View methods like PdfView.scrollToPage().
 */
interface PdfPageNavigator {
    /**
     * Navigates the visual PDF viewport to the specified 0-based [pageIndex].
     * Implementations must handle negative indexes and visual errors gracefully.
     */
    fun navigateToPage(pageIndex: Int)
}
