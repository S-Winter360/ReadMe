package com.readme.app.ui.pdf

import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingPosition
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.content.pdf.PdfReadingPositionMapper

object PdfPositionReconciler {
    /**
     * Reconciles the reading position based on the current PDF viewport.
     * Returns the new [ReadingPosition] or null if reconciliation is not possible/needed.
     */
    fun reconcile(
        document: ReadingDocument?,
        mapper: PdfReadingPositionMapper?,
        viewportState: PdfViewportState,
        isPdfActive: Boolean
    ): ReadingPosition? {
        if (!isPdfActive || mapper == null || document == null || document.id.isEmpty()) return null
        if (viewportState.visiblePagesCount <= 0) return null

        var targetPage = viewportState.firstVisiblePage
        var targetSection = mapper.getSectionForPage(targetPage)
        
        // Find the first page starting from targetPage that has non-blank content
        var validSegment: ReadingSegment? = null
        
        val mappedPages = mapper.mappedPageIndices.sorted()
        var searchPage = targetPage
        
        while (validSegment == null) {
            targetSection = mapper.getSectionForPage(searchPage)
            if (targetSection != null) {
                validSegment = targetSection.segments.firstOrNull { it.text.isNotBlank() }
            }
            
            if (validSegment == null) {
                val nextReadable = mappedPages.firstOrNull { it > searchPage } ?: return null
                searchPage = nextReadable
            }
        }
        
        return document.positionForSegmentId(validSegment.id)
    }
}
