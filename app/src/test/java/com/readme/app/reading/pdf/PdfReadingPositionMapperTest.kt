package com.readme.app.reading.content.pdf

import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingPosition
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.ReadingSessionState
import com.readme.app.ui.pdf.PdfViewportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfReadingPositionMapperTest {

    private fun createSamplePdfDocument(
        documentId: String = "pdf:sample1",
        pageIndices: List<Int> = listOf(0, 1, 2)
    ): ReadingDocument {
        val sections = pageIndices.map { pageIndex ->
            ReadingSection(
                id = "page:$pageIndex",
                title = "Page ${pageIndex + 1}",
                segments = listOf(
                    ReadingSegment(
                        id = "page:$pageIndex:segment:0",
                        text = "Sentence one of page ${pageIndex + 1}."
                    ),
                    ReadingSegment(
                        id = "page:$pageIndex:segment:1",
                        text = "Sentence two of page ${pageIndex + 1}."
                    )
                )
            )
        }
        return ReadingDocument(
            id = documentId,
            title = "Sample PDF Document",
            sourceType = ReadingDocumentSourceType.PDF,
            sections = sections
        )
    }

    @Test
    fun test1_pdfPage0_mapsToCorrectReadingSection() {
        val doc = createSamplePdfDocument()
        val mapper = PdfReadingPositionMapper(doc)

        val section = mapper.getSectionForPage(0)
        assertNotNull(section)
        assertEquals("page:0", section?.id)
        assertEquals("Page 1", section?.title)
    }

    @Test
    fun test2_pdfPage1_mapsToCorrectReadingSection() {
        val doc = createSamplePdfDocument()
        val mapper = PdfReadingPositionMapper(doc)

        val section = mapper.getSectionForPage(1)
        assertNotNull(section)
        assertEquals("page:1", section?.id)
        assertEquals("Page 2", section?.title)
    }

    @Test
    fun test3_readingSection_mapsToCorrectPdfPage() {
        val doc = createSamplePdfDocument()
        val mapper = PdfReadingPositionMapper(doc)

        assertEquals(0, mapper.getPageForSectionId("page:0"))
        assertEquals(1, mapper.getPageForSectionId("page:1"))
        assertEquals(2, mapper.getPageForSectionId("page:2"))
    }

    @Test
    fun test4_readingSegment_mapsToCorrectPdfPage() {
        val doc = createSamplePdfDocument()
        val mapper = PdfReadingPositionMapper(doc)

        assertEquals(0, mapper.getPageForSegmentId("page:0:segment:0"))
        assertEquals(0, mapper.getPageForSegmentId("page:0:segment:1"))
        assertEquals(1, mapper.getPageForSegmentId("page:1:segment:0"))
        assertEquals(2, mapper.getPageForSegmentId("page:2:segment:1"))
    }

    @Test
    fun test5_readingPosition_mapsToCorrectPdfPage() {
        val doc = createSamplePdfDocument(documentId = "pdf:doc5")
        val mapper = PdfReadingPositionMapper(doc)

        val pos0 = ReadingPosition(
            documentId = "pdf:doc5",
            sectionId = "page:0",
            segmentId = "page:0:segment:1",
            segmentIndex = 1
        )
        val pos1 = ReadingPosition(
            documentId = "pdf:doc5",
            sectionId = "page:1",
            segmentId = "page:1:segment:0",
            segmentIndex = 2
        )

        assertEquals(0, mapper.getPageForPosition(pos0))
        assertEquals(1, mapper.getPageForPosition(pos1))
    }

    @Test
    fun test6_firstVisiblePage_mapsToCorrectReadingSection() {
        val doc = createSamplePdfDocument()
        val mapper = PdfReadingPositionMapper(doc)

        val viewportState = PdfViewportState(firstVisiblePage = 1, visiblePagesCount = 1)
        val section = mapper.getSectionForViewport(viewportState)

        assertNotNull(section)
        assertEquals("page:1", section?.id)
    }

    @Test
    fun test7_visiblePageRangeCalculation() {
        val doc = createSamplePdfDocument()
        val mapper = PdfReadingPositionMapper(doc)

        val viewportState = PdfViewportState(firstVisiblePage = 2, visiblePagesCount = 3)
        val range = mapper.getVisiblePageRange(viewportState)

        assertEquals(2..4, range)
        assertEquals(setOf(2, 3, 4), range?.toSet())
    }

    @Test
    fun test8_speechPageVisible_isTrueWhenSpeechPageIsInViewport() {
        val doc = createSamplePdfDocument(documentId = "pdf:doc8")
        val mapper = PdfReadingPositionMapper(doc)

        val pos = ReadingPosition(
            documentId = "pdf:doc8",
            sectionId = "page:1",
            segmentId = "page:1:segment:0"
        )
        val viewportState = PdfViewportState(firstVisiblePage = 0, visiblePagesCount = 2)

        val syncState = mapper.computeSyncState(pos, viewportState)

        assertEquals(1, syncState.speechPage)
        assertEquals(0, syncState.viewportPage)
        assertTrue(syncState.isSpeechPageVisible)
        assertEquals(0..1, syncState.visiblePageRange)
    }

    @Test
    fun test9_speechPageVisible_isFalseWhenSpeechPageOutsideViewport() {
        val doc = createSamplePdfDocument(documentId = "pdf:doc9")
        val mapper = PdfReadingPositionMapper(doc)

        val pos = ReadingPosition(
            documentId = "pdf:doc9",
            sectionId = "page:0",
            segmentId = "page:0:segment:0"
        )
        val viewportState = PdfViewportState(firstVisiblePage = 2, visiblePagesCount = 2)

        val syncState = mapper.computeSyncState(pos, viewportState)

        assertEquals(0, syncState.speechPage)
        assertEquals(2, syncState.viewportPage)
        assertFalse(syncState.isSpeechPageVisible)
        assertEquals(2..3, syncState.visiblePageRange)
    }

    @Test
    fun test10_noPdf_createsEmptySyncState() {
        val mapper = PdfReadingPositionMapper.fromDocument(null)
        assertNull(mapper)

        val emptySyncState = PdfReadingSyncState.Empty
        assertTrue(emptySyncState.isEmpty)
        assertFalse(emptySyncState.hasActivePdf)
        assertNull(emptySyncState.speechPage)
        assertNull(emptySyncState.viewportPage)
    }

    @Test
    fun test11_txtDocument_pdfSyncStateEmpty() {
        val txtDoc = ReadingDocument(
            id = "txt:sample",
            title = "Text Doc",
            sourceType = ReadingDocumentSourceType.TXT,
            sections = listOf(ReadingSection(id = "sec:0", title = "Section 0", segments = emptyList()))
        )

        val mapper = PdfReadingPositionMapper.fromDocument(txtDoc)
        assertNull(mapper)
    }

    @Test
    fun test12_epubDocument_pdfSyncStateEmpty() {
        val epubDoc = ReadingDocument(
            id = "epub:sample",
            title = "Epub Doc",
            sourceType = ReadingDocumentSourceType.EPUB,
            sections = listOf(ReadingSection(id = "chapter:1", title = "Chapter 1", segments = emptyList()))
        )

        val mapper = PdfReadingPositionMapper.fromDocument(epubDoc)
        assertNull(mapper)
    }

    @Test
    fun test13_replacingPdfAWithPdfB_createsDistinctMapper() {
        val docA = createSamplePdfDocument(documentId = "pdf:docA", pageIndices = listOf(0, 1))
        val docB = createSamplePdfDocument(documentId = "pdf:docB", pageIndices = listOf(3, 4, 5))

        val mapperA = PdfReadingPositionMapper(docA)
        val mapperB = PdfReadingPositionMapper(docB)

        assertEquals("pdf:docA", mapperA.documentId)
        assertEquals("pdf:docB", mapperB.documentId)

        assertEquals("page:0", mapperA.getSectionForPage(0)?.id)
        assertNull(mapperB.getSectionForPage(0))
        assertEquals("page:3", mapperB.getSectionForPage(3)?.id)
    }

    @Test
    fun test14_stalePdfAMapping_cannotResolveAgainstPdfBPosition() {
        val docA = createSamplePdfDocument(documentId = "pdf:docA")
        val mapperA = PdfReadingPositionMapper(docA)

        val posB = ReadingPosition(
            documentId = "pdf:docB",
            sectionId = "page:0",
            segmentId = "page:0:segment:0"
        )

        // Document ID mismatch MUST return null to prevent stale cross-document resolutions
        val resolvedPage = mapperA.getPageForPosition(posB)
        assertNull(resolvedPage)
    }

    @Test
    fun test15_emptyPdfPage_doesNotCorruptPhysicalPageNumbering() {
        // PDF has 5 physical pages: 0, 1, 2, 3, 4
        // Page 1 and 3 are empty (no selectable text) and omitted
        val docWithSkippedPages = createSamplePdfDocument(
            documentId = "pdf:sparse",
            pageIndices = listOf(0, 2, 4)
        )

        val mapper = PdfReadingPositionMapper(docWithSkippedPages)

        // Physical pages with text
        assertEquals("page:0", mapper.getSectionForPage(0)?.id)
        assertEquals("page:2", mapper.getSectionForPage(2)?.id)
        assertEquals("page:4", mapper.getSectionForPage(4)?.id)

        // Physical pages without text resolve to null
        assertNull(mapper.getSectionForPage(1))
        assertNull(mapper.getSectionForPage(3))
        assertNull(mapper.getSectionForPage(5))
    }

    @Test
    fun test16_skippedEmptyPages_preserveOriginalPdfPageIndices() {
        // 3 sections corresponding to physical pages 10, 20, 30
        val doc = createSamplePdfDocument(
            documentId = "pdf:sparse2",
            pageIndices = listOf(10, 20, 30)
        )

        val mapper = PdfReadingPositionMapper(doc)

        // Check section index to physical page index
        assertEquals(10, mapper.getPageForSectionIndex(0))
        assertEquals(20, mapper.getPageForSectionIndex(1))
        assertEquals(30, mapper.getPageForSectionIndex(2))

        // Check physical page to section index
        assertEquals(0, mapper.getSectionIndexForPage(10))
        assertEquals(1, mapper.getSectionIndexForPage(20))
        assertEquals(2, mapper.getSectionIndexForPage(30))
        assertNull(mapper.getSectionIndexForPage(15))
    }

    @Test
    fun test17_invalidPageIndex_handledSafely() {
        val doc = createSamplePdfDocument()
        val mapper = PdfReadingPositionMapper(doc)

        assertNull(mapper.getSectionForPage(-1))
        assertNull(mapper.getSectionForPage(9999))
        assertFalse(mapper.isPageVisible(-5, PdfViewportState(firstVisiblePage = 0, visiblePagesCount = 2)))
    }

    @Test
    fun test18_invalidSectionId_handledSafely() {
        val doc = createSamplePdfDocument()
        val mapper = PdfReadingPositionMapper(doc)

        assertNull(mapper.getPageForSectionId(""))
        assertNull(mapper.getPageForSectionId("invalid:section:id"))
        assertNull(mapper.getPageForSectionId("nonexistent"))
    }

    @Test
    fun test19_invalidSegmentId_handledSafely() {
        val doc = createSamplePdfDocument()
        val mapper = PdfReadingPositionMapper(doc)

        assertNull(mapper.getPageForSegmentId(""))
        assertNull(mapper.getPageForSegmentId("page:99:segment:99"))
        assertNull(mapper.getPageForSegmentId("random_id"))
    }

    @Test
    fun test20_completedReadingPosition_mapsToCorrectFinalPdfPage() {
        val doc = createSamplePdfDocument(pageIndices = listOf(0, 1, 2))
        val mapper = PdfReadingPositionMapper(doc)

        // Completed session position at the last segment of page 2
        val lastPosition = doc.positionForIndex(doc.allSegments().lastIndex)
        assertNotNull(lastPosition)

        val page = mapper.getPageForPosition(lastPosition)
        assertEquals(2, page)
    }

    @Test
    fun test21_stoppedReadingPosition_mapsToCurrentPdfPage() {
        val doc = createSamplePdfDocument(pageIndices = listOf(0, 1, 2))
        val mapper = PdfReadingPositionMapper(doc)

        val stoppedPosition = ReadingPosition(
            documentId = doc.id,
            sectionId = "page:1",
            segmentId = "page:1:segment:1",
            segmentIndex = 3
        )

        val page = mapper.getPageForPosition(stoppedPosition)
        assertEquals(1, page)
    }

    @Test
    fun test22_readingPositionWithoutActivePdf_doesNotCreatePageMapping() {
        val mapper = PdfReadingPositionMapper.fromDocument(null)
        assertNull(mapper)

        val pos = ReadingPosition(
            documentId = "txt:plain",
            sectionId = "sec:1",
            segmentId = "seg:1"
        )
        val syncState = PdfReadingSyncState.Empty
        assertNull(syncState.speechPage)
        assertFalse(syncState.isSpeechPageVisible)
    }

    @Test
    fun test23_multipleVisiblePages_correctlyDetermineVisibility() {
        val doc = createSamplePdfDocument(pageIndices = (0..9).toList())
        val mapper = PdfReadingPositionMapper(doc)

        // Viewport shows pages 3, 4, 5, 6
        val viewportState = PdfViewportState(firstVisiblePage = 3, visiblePagesCount = 4)

        assertFalse(mapper.isPageVisible(2, viewportState))
        assertTrue(mapper.isPageVisible(3, viewportState))
        assertTrue(mapper.isPageVisible(4, viewportState))
        assertTrue(mapper.isPageVisible(5, viewportState))
        assertTrue(mapper.isPageVisible(6, viewportState))
        assertFalse(mapper.isPageVisible(7, viewportState))

        val visibleSections = mapper.getSectionsForViewport(viewportState)
        assertEquals(4, visibleSections.size)
        assertEquals(listOf("page:3", "page:4", "page:5", "page:6"), visibleSections.map { it.id })
    }

    @Test
    fun test24_zoomChanges_doNotChangePageIdentity() {
        val doc = createSamplePdfDocument()
        val mapper = PdfReadingPositionMapper(doc)

        val viewportZoom1 = PdfViewportState(firstVisiblePage = 1, visiblePagesCount = 1, zoom = 1.0f)
        val viewportZoom2 = PdfViewportState(firstVisiblePage = 1, visiblePagesCount = 1, zoom = 2.5f)

        assertEquals(
            mapper.getSectionForViewport(viewportZoom1)?.id,
            mapper.getSectionForViewport(viewportZoom2)?.id
        )
        assertEquals(
            mapper.getVisiblePageRange(viewportZoom1),
            mapper.getVisiblePageRange(viewportZoom2)
        )
    }

    @Test
    fun test25_viewportChanges_doNotChangeReadingPosition() {
        val doc = createSamplePdfDocument()
        val mapper = PdfReadingPositionMapper(doc)

        val currentPosition = ReadingPosition(
            documentId = doc.id,
            sectionId = "page:0",
            segmentId = "page:0:segment:0"
        )

        // Viewport changes from page 0 to page 5
        val viewport1 = PdfViewportState(firstVisiblePage = 0, visiblePagesCount = 1)
        val sync1 = mapper.computeSyncState(currentPosition, viewport1)
        assertTrue(sync1.isSpeechPageVisible)

        val viewport2 = PdfViewportState(firstVisiblePage = 5, visiblePagesCount = 2)
        val sync2 = mapper.computeSyncState(currentPosition, viewport2)
        assertFalse(sync2.isSpeechPageVisible)

        // The position speechPage remains unchanged (0) despite viewport scrolling
        assertEquals(0, sync1.speechPage)
        assertEquals(0, sync2.speechPage)
    }

    @Test
    fun test26_readingPositionChanges_doNotChangeViewport() {
        val doc = createSamplePdfDocument()
        val mapper = PdfReadingPositionMapper(doc)

        val viewport = PdfViewportState(firstVisiblePage = 2, visiblePagesCount = 1)

        val posPage0 = ReadingPosition(documentId = doc.id, sectionId = "page:0", segmentId = "page:0:segment:0")
        val sync1 = mapper.computeSyncState(posPage0, viewport)
        assertEquals(2, sync1.viewportPage)
        assertFalse(sync1.isSpeechPageVisible)

        val posPage2 = ReadingPosition(documentId = doc.id, sectionId = "page:2", segmentId = "page:2:segment:0")
        val sync2 = mapper.computeSyncState(posPage2, viewport)
        assertEquals(2, sync2.viewportPage)
        assertTrue(sync2.isSpeechPageVisible)
    }

    @Test
    fun test27_readingEngineProgressionAcrossPages_updatesSpeechPageDeterministically() {
        val doc = createSamplePdfDocument(pageIndices = listOf(0, 1, 2))
        val mapper = PdfReadingPositionMapper(doc)
        val engine = com.readme.app.reading.ReadingEngine()
        engine.loadDocument(doc)

        // Start reading -> segment 0 on page 0
        val seg0 = engine.startFromBeginning()
        assertNotNull(seg0)
        assertEquals(0, mapper.getPageForPosition(engine.currentPosition.value))

        // Advance to segment 1 on page 0
        engine.advance()
        assertEquals(0, mapper.getPageForPosition(engine.currentPosition.value))

        // Advance to segment 0 on page 1
        engine.advance()
        assertEquals(1, mapper.getPageForPosition(engine.currentPosition.value))

        // Advance to segment 1 on page 1
        engine.advance()
        assertEquals(1, mapper.getPageForPosition(engine.currentPosition.value))

        // Advance to segment 0 on page 2
        engine.advance()
        assertEquals(2, mapper.getPageForPosition(engine.currentPosition.value))

        // Advance to segment 1 on page 2
        engine.advance()
        assertEquals(2, mapper.getPageForPosition(engine.currentPosition.value))

        // Stop session and verify stopped position page
        engine.stop()
        assertEquals(2, mapper.getPageForPosition(engine.currentPosition.value))
    }

    @Test
    fun test28_sparseDocumentReadingProgression_skipsOmittedPagesCorrectly() {
        val doc = createSamplePdfDocument(pageIndices = listOf(0, 5, 10))
        val mapper = PdfReadingPositionMapper(doc)
        val engine = com.readme.app.reading.ReadingEngine()
        engine.loadDocument(doc)

        // Start at physical page 0
        engine.startFromBeginning()
        assertEquals(0, mapper.getPageForPosition(engine.currentPosition.value))

        // Advance past page 0 segments -> goes directly to physical page 5
        engine.advance()
        engine.advance()
        assertEquals(5, mapper.getPageForPosition(engine.currentPosition.value))

        // Advance past page 5 segments -> goes directly to physical page 10
        engine.advance()
        engine.advance()
        assertEquals(10, mapper.getPageForPosition(engine.currentPosition.value))
    }

    @Test
    fun test29_mappedPageIndices_andSectionCount_matchMetadata() {
        val doc = createSamplePdfDocument(pageIndices = listOf(1, 4, 8))
        val mapper = PdfReadingPositionMapper(doc)

        assertEquals(3, mapper.mappedSectionCount)
        assertEquals(setOf(1, 4, 8), mapper.mappedPageIndices)
    }

    @Test
    fun test30_inactiveViewer_producesEmptySyncState() {
        val doc = createSamplePdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        val pos = ReadingPosition(documentId = doc.id, sectionId = "page:0", segmentId = "page:0:segment:0")
        val viewport = PdfViewportState(firstVisiblePage = 0, visiblePagesCount = 1)

        val syncStateInactive = mapper.computeSyncState(pos, viewport, isViewerActive = false)
        assertTrue(syncStateInactive.isEmpty)
        assertNull(syncStateInactive.speechPage)
        assertNull(syncStateInactive.viewportPage)
    }
}
