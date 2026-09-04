package com.readme.app.reading.content.pdf

import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingPosition
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment
import com.readme.app.ui.pdf.PdfViewportState

/**
 * Deterministic mapper between the visual AndroidX PDF page viewport,
 * the corresponding [ReadingSection]s, and the active [ReadingSegment]/[ReadingPosition].
 *
 * Page Index Convention:
 * - PDF page index = 0-based
 * - ReadingSection index = 0-based
 *
 * Preserves physical PDF page numbers even when empty pages with no selectable text
 * were omitted during document parsing.
 */
class PdfReadingPositionMapper(
    val documentId: String,
    sections: List<ReadingSection>
) {
    private val pageIndexToSection: Map<Int, ReadingSection>
    private val sectionIdToPageIndex: Map<String, Int>
    private val segmentIdToPageIndex: Map<String, Int>
    private val sectionIndexToPageIndex: Map<Int, Int>
    private val pageIndexToSectionIndex: Map<Int, Int>

    init {
        val pageToSection = mutableMapOf<Int, ReadingSection>()
        val secIdToPage = mutableMapOf<String, Int>()
        val segIdToPage = mutableMapOf<String, Int>()
        val secIdxToPage = mutableMapOf<Int, Int>()
        val pageToSecIdx = mutableMapOf<Int, Int>()

        val pageIdRegex = Regex("^page:(\\d+)")
        val pageTitleRegex = Regex("^Page\\s+(\\d+)", RegexOption.IGNORE_CASE)

        sections.forEachIndexed { sectionIndex, section ->
            // Determine physical 0-based PDF page index from section metadata
            val match = pageIdRegex.find(section.id)
            val physicalPageIndex = when {
                match != null -> match.groupValues[1].toIntOrNull() ?: sectionIndex
                else -> {
                    val titleMatch = pageTitleRegex.find(section.title)
                    if (titleMatch != null) {
                        val oneBasedPage = titleMatch.groupValues[1].toIntOrNull()
                        if (oneBasedPage != null && oneBasedPage > 0) oneBasedPage - 1 else sectionIndex
                    } else {
                        sectionIndex
                    }
                }
            }

            pageToSection[physicalPageIndex] = section
            secIdToPage[section.id] = physicalPageIndex
            secIdxToPage[sectionIndex] = physicalPageIndex
            pageToSecIdx[physicalPageIndex] = sectionIndex

            for (segment in section.segments) {
                segIdToPage[segment.id] = physicalPageIndex
            }
        }

        pageIndexToSection = pageToSection
        sectionIdToPageIndex = secIdToPage
        segmentIdToPageIndex = segIdToPage
        sectionIndexToPageIndex = secIdxToPage
        pageIndexToSectionIndex = pageToSecIdx
    }

    constructor(document: ReadingDocument) : this(document.id, document.sections)

    /**
     * Set of all 0-based physical PDF page indices that contain readable sections.
     */
    val mappedPageIndices: Set<Int> get() = pageIndexToSection.keys

    /**
     * Total number of mapped sections.
     */
    val mappedSectionCount: Int get() = pageIndexToSection.size

    /**
     * Resolves a 0-based PDF page index to its corresponding [ReadingSection],
     * or null if the page was skipped / contains no selectable text.
     */
    fun getSectionForPage(pdfPageIndex: Int): ReadingSection? {
        if (pdfPageIndex < 0) return null
        return pageIndexToSection[pdfPageIndex]
    }

    /**
     * Resolves a 0-based PDF page index to its [ReadingSection] ID, or null.
     */
    fun getSectionIdForPage(pdfPageIndex: Int): String? {
        return getSectionForPage(pdfPageIndex)?.id
    }

    /**
     * Resolves a [ReadingSection] ID to its 0-based physical PDF page index, or null.
     */
    fun getPageForSectionId(sectionId: String): Int? {
        return sectionIdToPageIndex[sectionId]
    }

    /**
     * Resolves a [ReadingSegment] ID to its 0-based physical PDF page index, or null.
     */
    fun getPageForSegmentId(segmentId: String): Int? {
        return segmentIdToPageIndex[segmentId]
    }

    /**
     * Resolves a 0-based [ReadingSection] index to its 0-based physical PDF page index.
     */
    fun getPageForSectionIndex(sectionIndex: Int): Int? {
        return sectionIndexToPageIndex[sectionIndex]
    }

    /**
     * Resolves a 0-based physical PDF page index to its 0-based [ReadingSection] index in the document.
     */
    fun getSectionIndexForPage(pdfPageIndex: Int): Int? {
        return pageIndexToSectionIndex[pdfPageIndex]
    }

    /**
     * Resolves a [ReadingPosition] to its 0-based physical PDF page index.
     * Returns null if [position] is null, if its document ID does not match this mapper,
     * or if the segment/section cannot be resolved.
     */
    fun getPageForPosition(position: ReadingPosition?): Int? {
        if (position == null || position.documentId != documentId || documentId.isEmpty()) {
            return null
        }
        return segmentIdToPageIndex[position.segmentId]
            ?: sectionIdToPageIndex[position.sectionId]
    }

    /**
     * Resolves the first visible page in [viewportState] to its [ReadingSection], or null.
     */
    fun getSectionForViewport(viewportState: PdfViewportState?): ReadingSection? {
        if (viewportState == null || viewportState.visiblePagesCount <= 0) return null
        return getSectionForPage(viewportState.firstVisiblePage)
    }

    /**
     * Resolves all visible PDF pages in [viewportState] to their corresponding [ReadingSection]s.
     */
    fun getSectionsForViewport(viewportState: PdfViewportState?): List<ReadingSection> {
        if (viewportState == null || viewportState.visiblePagesCount <= 0) return emptyList()
        val range = viewportState.firstVisiblePage until (viewportState.firstVisiblePage + viewportState.visiblePagesCount)
        return range.mapNotNull { getSectionForPage(it) }
    }

    /**
     * Returns whether the given 0-based physical PDF page is currently visible in [viewportState].
     */
    fun isPageVisible(pageIndex: Int, viewportState: PdfViewportState?): Boolean {
        if (viewportState == null || viewportState.visiblePagesCount <= 0 || pageIndex < 0) return false
        val visibleRange = viewportState.firstVisiblePage until (viewportState.firstVisiblePage + viewportState.visiblePagesCount)
        return pageIndex in visibleRange
    }

    /**
     * Returns the 0-based visible page range for [viewportState], or null.
     */
    fun getVisiblePageRange(viewportState: PdfViewportState?): IntRange? {
        if (viewportState == null || viewportState.visiblePagesCount <= 0) return null
        return viewportState.firstVisiblePage until (viewportState.firstVisiblePage + viewportState.visiblePagesCount)
    }

    /**
     * Computes the complete [PdfReadingSyncState] from current reading position and viewport state.
     */
    fun computeSyncState(
        position: ReadingPosition?,
        viewportState: PdfViewportState?,
        isViewerActive: Boolean = true,
        pendingNavigationPage: Int? = null
    ): PdfReadingSyncState {
        if (!isViewerActive) return PdfReadingSyncState.Empty

        val speechPage = getPageForPosition(position)
        val visibleRange = getVisiblePageRange(viewportState)
        val viewportPage = if (visibleRange != null) viewportState?.firstVisiblePage else null
        val isSpeechPageVisible = speechPage != null && visibleRange != null && speechPage in visibleRange

        return PdfReadingSyncState(
            speechPage = speechPage,
            viewportPage = viewportPage,
            isSpeechPageVisible = isSpeechPageVisible,
            visiblePageRange = visibleRange,
            documentId = documentId,
            pendingNavigationPage = pendingNavigationPage
        )
    }

    companion object {
        /**
         * Factory function to create a [PdfReadingPositionMapper] from a [ReadingDocument].
         * Returns null if [document] is null, empty, or not a PDF document.
         */
        fun fromDocument(document: ReadingDocument?): PdfReadingPositionMapper? {
            if (document == null || document.id.isEmpty()) return null
            val isPdf = document.sourceType == ReadingDocumentSourceType.PDF || document.id.startsWith("pdf:")
            if (!isPdf) return null
            return PdfReadingPositionMapper(document)
        }
    }
}
