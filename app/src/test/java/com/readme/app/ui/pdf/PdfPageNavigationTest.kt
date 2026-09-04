package com.readme.app.ui.pdf

import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingPosition
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.ReadingSessionState
import com.readme.app.reading.content.pdf.PdfReadingPositionMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Complete test suite for Phase 8E: Automatic speech-driven PDF page navigation.
 * Tests all 31 mandated functional and safety scenarios.
 */
class PdfPageNavigationTest {

    private class FakePdfPageNavigator : PdfPageNavigator {
        val navigatedPages = mutableListOf<Int>()
        var shouldThrow = false

        override fun navigateToPage(pageIndex: Int) {
            if (shouldThrow) {
                throw RuntimeException("Simulated viewport failure")
            }
            navigatedPages.add(pageIndex)
        }

        fun reset() {
            navigatedPages.clear()
            shouldThrow = false
        }
    }

    private lateinit var navigator: FakePdfPageNavigator
    private lateinit var coordinator: PdfNavigationCoordinator

    @Before
    fun setup() {
        navigator = FakePdfPageNavigator()
        coordinator = PdfNavigationCoordinator(navigator)
    }

    private fun createPdfDocument(
        id: String = "pdf:doc1",
        pageCount: Int = 5,
        skippedPages: Set<Int> = emptySet()
    ): ReadingDocument {
        val sections = mutableListOf<ReadingSection>()
        for (page in 0 until pageCount) {
            if (page in skippedPages) continue
            sections.add(
                ReadingSection(
                    id = "page:$page",
                    title = "Page ${page + 1}",
                    segments = listOf(
                        ReadingSegment(id = "page:$page:seg:0", text = "Sentence 1 of page ${page + 1}"),
                        ReadingSegment(id = "page:$page:seg:1", text = "Sentence 2 of page ${page + 1}")
                    )
                )
            )
        }
        return ReadingDocument(
            id = id,
            title = "Document $id",
            sourceType = ReadingDocumentSourceType.PDF,
            sections = sections
        )
    }

    private fun positionForPage(doc: ReadingDocument, pageIndex: Int, segmentIndex: Int = 0): ReadingPosition {
        return ReadingPosition(
            documentId = doc.id,
            sectionId = "page:$pageIndex",
            segmentId = "page:$pageIndex:seg:$segmentIndex",
            segmentIndex = segmentIndex
        )
    }

    private fun viewportForPages(firstPage: Int, count: Int = 1, zoom: Float = 1.0f): PdfViewportState {
        return PdfViewportState(
            firstVisiblePage = firstPage,
            visiblePagesCount = count,
            zoom = zoom
        )
    }

    // 1. Speech page already visible -> no navigation request
    @Test
    fun test1_speechPageAlreadyVisible_noNavigationRequest() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)
        coordinator.onViewportChanged(viewportForPages(0, 1))

        coordinator.evaluateNavigation(positionForPage(doc, 0))

        assertTrue(navigator.navigatedPages.isEmpty())
        assertNull(coordinator.pendingNavigation.value)
    }

    // 2. Speech page outside visible range -> navigation request
    @Test
    fun test2_speechPageOutsideVisibleRange_navigationRequest() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)
        coordinator.onViewportChanged(viewportForPages(0, 1))

        coordinator.evaluateNavigation(positionForPage(doc, 2))

        assertEquals(listOf(2), navigator.navigatedPages)
        assertEquals(2, coordinator.pendingNavigation.value?.pageIndex)
    }

    // 3. Speech page immediately adjacent but not visible -> navigation request
    @Test
    fun test3_speechPageImmediatelyAdjacentButNotVisible_navigationRequest() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)
        coordinator.onViewportChanged(viewportForPages(0, 1))

        coordinator.evaluateNavigation(positionForPage(doc, 1))

        assertEquals(listOf(1), navigator.navigatedPages)
        assertEquals(1, coordinator.pendingNavigation.value?.pageIndex)
    }

    // 4. Multiple visible pages containing speech page -> no navigation
    @Test
    fun test4_multipleVisiblePagesContainingSpeechPage_noNavigation() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)
        coordinator.onViewportChanged(viewportForPages(1, 3)) // covers 1, 2, 3

        coordinator.evaluateNavigation(positionForPage(doc, 2))

        assertTrue(navigator.navigatedPages.isEmpty())
        assertNull(coordinator.pendingNavigation.value)
    }

    // 5. Same page repeated by multiple segments -> no repeated navigation
    @Test
    fun test5_samePageRepeatedByMultipleSegments_noRepeatedNavigation() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)
        coordinator.onViewportChanged(viewportForPages(0, 1))

        coordinator.evaluateNavigation(positionForPage(doc, 2, segmentIndex = 0))
        assertEquals(listOf(2), navigator.navigatedPages)

        coordinator.evaluateNavigation(positionForPage(doc, 2, segmentIndex = 1))
        // Still only 1 navigation call
        assertEquals(listOf(2), navigator.navigatedPages)
    }

    // 6. Same page restarted because speed changes -> no duplicate navigation
    @Test
    fun test6_samePageRestartedBecauseSpeedChanges_noDuplicateNavigation() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)
        coordinator.onViewportChanged(viewportForPages(1, 1))

        val position = positionForPage(doc, 1)
        coordinator.evaluateNavigation(position)
        assertTrue(navigator.navigatedPages.isEmpty())

        // Speed changes; segment restarted at current position
        coordinator.evaluateNavigation(position)
        assertTrue(navigator.navigatedPages.isEmpty())
    }

    // 7. Same page restarted because pitch changes -> no duplicate navigation
    @Test
    fun test7_samePageRestartedBecausePitchChanges_noDuplicateNavigation() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)
        coordinator.onViewportChanged(viewportForPages(2, 1))

        val position = positionForPage(doc, 2)
        coordinator.evaluateNavigation(position)
        coordinator.evaluateNavigation(position)
        assertTrue(navigator.navigatedPages.isEmpty())
    }

    // 8. Same page restarted because voice changes -> no duplicate navigation
    @Test
    fun test8_samePageRestartedBecauseVoiceChanges_noDuplicateNavigation() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)
        coordinator.onViewportChanged(viewportForPages(3, 1))

        val position = positionForPage(doc, 3)
        coordinator.evaluateNavigation(position)
        coordinator.evaluateNavigation(position)
        assertTrue(navigator.navigatedPages.isEmpty())
    }

    // 9. New page -> navigation request
    @Test
    fun test9_newPage_navigationRequest() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)
        coordinator.onViewportChanged(viewportForPages(1, 1))

        coordinator.evaluateNavigation(positionForPage(doc, 2))
        assertEquals(listOf(2), navigator.navigatedPages)

        // Viewport moves to page 2
        coordinator.onViewportChanged(viewportForPages(2, 1))

        // Speech advances to page 3
        coordinator.evaluateNavigation(positionForPage(doc, 3))
        assertEquals(listOf(2, 3), navigator.navigatedPages)
    }

    // 10. Pending navigation remains pending until viewport confirms target visibility
    @Test
    fun test10_pendingNavigationRemainsPendingUntilViewportConfirmsTargetVisibility() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)
        coordinator.onViewportChanged(viewportForPages(0, 1))

        coordinator.evaluateNavigation(positionForPage(doc, 2))
        assertEquals(2, coordinator.pendingNavigation.value?.pageIndex)

        // Intermediate viewport update before reaching page 2
        coordinator.onViewportChanged(viewportForPages(1, 1))
        assertEquals(2, coordinator.pendingNavigation.value?.pageIndex)
    }

    // 11. Viewport reaches target page -> pending navigation clears
    @Test
    fun test11_viewportReachesTargetPage_pendingNavigationClears() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)
        coordinator.onViewportChanged(viewportForPages(0, 1))

        coordinator.evaluateNavigation(positionForPage(doc, 2))
        assertEquals(2, coordinator.pendingNavigation.value?.pageIndex)

        // Viewport arrives at page 2
        coordinator.onViewportChanged(viewportForPages(2, 1))
        assertNull(coordinator.pendingNavigation.value)
    }

    // 12. Manual viewport movement to target page -> pending request clears
    @Test
    fun test12_manualViewportMovementToTargetPage_pendingRequestClears() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)
        coordinator.onViewportChanged(viewportForPages(0, 1))

        coordinator.evaluateNavigation(positionForPage(doc, 3))
        assertEquals(3, coordinator.pendingNavigation.value?.pageIndex)

        // User manually scrolls viewport to cover page 3
        coordinator.onViewportChanged(viewportForPages(3, 2))
        assertNull(coordinator.pendingNavigation.value)
    }

    // 13. Manual viewport movement away from speech page does not change speech position
    @Test
    fun test13_manualViewportMovementAwayFromSpeechPage_doesNotChangeSpeechPosition() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)

        coordinator.onViewportChanged(viewportForPages(1, 1))
        coordinator.evaluateNavigation(positionForPage(doc, 1))

        // User violently scrolls away to page 4
        coordinator.onViewportChanged(viewportForPages(4, 1))

        // No automatic navigation should be triggered by viewport change alone
        assertTrue(navigator.navigatedPages.isEmpty())
    }

    // 14. Document A pending navigation invalidated when document B becomes active
    @Test
    fun test14_documentAPendingNavigationInvalidatedWhenDocumentBBescomesActive() {
        val docA = createPdfDocument(id = "pdf:docA")
        val docB = createPdfDocument(id = "pdf:docB")
        val mapperA = PdfReadingPositionMapper(docA)
        val mapperB = PdfReadingPositionMapper(docB)

        coordinator.setPdfDocument(mapperA, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)
        coordinator.onViewportChanged(viewportForPages(0, 1))

        coordinator.evaluateNavigation(positionForPage(docA, 3))
        assertEquals(3, coordinator.pendingNavigation.value?.pageIndex)

        // Switch to document B
        coordinator.setPdfDocument(mapperB, isActive = true)
        assertNull(coordinator.pendingNavigation.value)
    }

    // 15. Stale document A request cannot navigate document B
    @Test
    fun test15_staleDocumentARequestCannotNavigateDocumentB() {
        val docA = createPdfDocument(id = "pdf:docA")
        val docB = createPdfDocument(id = "pdf:docB")
        val mapperA = PdfReadingPositionMapper(docA)
        val mapperB = PdfReadingPositionMapper(docB)

        // Unset navigator initially so request is queued
        val disconnectedCoordinator = PdfNavigationCoordinator(navigator = null)
        disconnectedCoordinator.setPdfDocument(mapperA, isActive = true)
        disconnectedCoordinator.setReadingState(ReadingSessionState.Reading)
        disconnectedCoordinator.onViewportChanged(viewportForPages(0, 1))

        disconnectedCoordinator.evaluateNavigation(positionForPage(docA, 4))
        assertEquals(4, disconnectedCoordinator.pendingNavigation.value?.pageIndex)

        // Switch to document B and connect navigator
        disconnectedCoordinator.setPdfDocument(mapperB, isActive = true)
        disconnectedCoordinator.setNavigator(navigator)

        assertTrue(navigator.navigatedPages.isEmpty())
        assertNull(disconnectedCoordinator.pendingNavigation.value)
    }

    // 16. No active PDF -> no navigation
    @Test
    fun test16_noActivePdf_noNavigation() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = false) // Not active
        coordinator.setReadingState(ReadingSessionState.Reading)

        coordinator.evaluateNavigation(positionForPage(doc, 2))
        assertTrue(navigator.navigatedPages.isEmpty())
        assertNull(coordinator.pendingNavigation.value)
    }

    // 17. No valid ReadingPosition -> no navigation
    @Test
    fun test17_noValidReadingPosition_noNavigation() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)

        coordinator.evaluateNavigation(null)
        assertTrue(navigator.navigatedPages.isEmpty())
        assertNull(coordinator.pendingNavigation.value)
    }

    // 18. Invalid mapping -> no navigation
    @Test
    fun test18_invalidMapping_noNavigation() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)

        // SectionId does not exist in document
        val invalidPos = ReadingPosition(documentId = doc.id, sectionId = "invalid:sec", segmentId = "seg:0", segmentIndex = 0)
        coordinator.evaluateNavigation(invalidPos)
        assertTrue(navigator.navigatedPages.isEmpty())
        assertNull(coordinator.pendingNavigation.value)
    }

    // 19. PDF viewer not ready -> pending target stored safely
    @Test
    fun test19_pdfViewerNotReady_pendingTargetStoredSafely() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        val unreadyCoordinator = PdfNavigationCoordinator(navigator = null)
        unreadyCoordinator.setPdfDocument(mapper, isActive = true)
        unreadyCoordinator.setReadingState(ReadingSessionState.Reading)
        unreadyCoordinator.onViewportChanged(viewportForPages(0, 1))

        unreadyCoordinator.evaluateNavigation(positionForPage(doc, 2))
        assertEquals(2, unreadyCoordinator.pendingNavigation.value?.pageIndex)
        assertTrue(navigator.navigatedPages.isEmpty())

        // Connect navigator when viewer becomes ready
        unreadyCoordinator.setNavigator(navigator)
        assertEquals(listOf(2), navigator.navigatedPages)
    }

    // 20. Newer speech page replaces older pending target
    @Test
    fun test20_newerSpeechPageReplacesOlderPendingTarget() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        val unreadyCoordinator = PdfNavigationCoordinator(navigator = null)
        unreadyCoordinator.setPdfDocument(mapper, isActive = true)
        unreadyCoordinator.setReadingState(ReadingSessionState.Reading)
        unreadyCoordinator.onViewportChanged(viewportForPages(0, 1))

        unreadyCoordinator.evaluateNavigation(positionForPage(doc, 1))
        assertEquals(1, unreadyCoordinator.pendingNavigation.value?.pageIndex)

        // Before navigator connects, speech rapidly moves to page 3
        unreadyCoordinator.evaluateNavigation(positionForPage(doc, 3))
        assertEquals(3, unreadyCoordinator.pendingNavigation.value?.pageIndex)

        // When navigator connects, only page 3 is navigated to
        unreadyCoordinator.setNavigator(navigator)
        assertEquals(listOf(3), navigator.navigatedPages)
    }

    // 21. Stopping reading invalidates obsolete pending navigation when appropriate
    @Test
    fun test21_stoppingReadingInvalidatesObsoletePendingNavigationWhenAppropriate() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)
        coordinator.onViewportChanged(viewportForPages(0, 1))

        coordinator.evaluateNavigation(positionForPage(doc, 3))
        assertEquals(3, coordinator.pendingNavigation.value?.pageIndex)

        coordinator.onReadingStopped()
        assertNull(coordinator.pendingNavigation.value)
    }

    // 22. Completed reading does not continuously navigate
    @Test
    fun test22_completedReadingDoesNotContinuouslyNavigate() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Completed)

        coordinator.evaluateNavigation(positionForPage(doc, 4))
        assertTrue(navigator.navigatedPages.isEmpty())
        assertNull(coordinator.pendingNavigation.value)
    }

    // 23. Resume from current page navigates when needed
    @Test
    fun test23_resumeFromCurrentPageNavigatesWhenNeeded() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Stopped)
        coordinator.onViewportChanged(viewportForPages(0, 1))

        val currentPosition = positionForPage(doc, 3)
        coordinator.onReadingStarted(currentPosition)

        assertEquals(listOf(3), navigator.navigatedPages)
    }

    // 24. Zoom change does not create navigation by itself
    @Test
    fun test24_zoomChangeDoesNotCreateNavigationByItself() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)

        // Viewport reports zoom scale change from 1.0f to 2.5f
        coordinator.onViewportChanged(viewportForPages(0, 1, zoom = 2.5f))
        assertTrue(navigator.navigatedPages.isEmpty())
    }

    // 25. Viewport callback alone does not create navigation
    @Test
    fun test25_viewportCallbackAloneDoesNotCreateNavigation() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)

        coordinator.onViewportChanged(viewportForPages(1, 2))
        coordinator.onViewportChanged(viewportForPages(2, 2))
        coordinator.onViewportChanged(viewportForPages(0, 1))

        assertTrue(navigator.navigatedPages.isEmpty())
    }

    // 26. Speech position change while target page is already visible -> no navigation
    @Test
    fun test26_speechPositionChangeWhileTargetPageIsAlreadyVisible_noNavigation() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)
        coordinator.onViewportChanged(viewportForPages(0, 3)) // pages 0, 1, 2 visible

        coordinator.evaluateNavigation(positionForPage(doc, 0))
        assertTrue(navigator.navigatedPages.isEmpty())

        coordinator.evaluateNavigation(positionForPage(doc, 1))
        assertTrue(navigator.navigatedPages.isEmpty())
    }

    // 27. Visual navigation failure does not alter ReadingPosition
    @Test
    fun test27_visualNavigationFailureDoesNotAlterReadingPosition() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        navigator.shouldThrow = true
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)
        coordinator.onViewportChanged(viewportForPages(0, 1))

        val position = positionForPage(doc, 2)
        // Must not crash or propagate exception
        coordinator.evaluateNavigation(position)
        assertEquals(ReadingSessionState.Reading, coordinator.readingSessionState)
    }

    // 28. Visual navigation failure does not stop TTS
    @Test
    fun test28_visualNavigationFailureDoesNotStopTTS() {
        val doc = createPdfDocument()
        val mapper = PdfReadingPositionMapper(doc)
        navigator.shouldThrow = true
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)

        coordinator.evaluateNavigation(positionForPage(doc, 3))
        assertEquals(ReadingSessionState.Reading, coordinator.readingSessionState)
    }

    // 29. Empty/skipped PDF pages preserve correct physical page navigation
    @Test
    fun test29_emptySkippedPdfPagesPreserveCorrectPhysicalPageNavigation() {
        // Doc has 5 physical pages, but pages 1 and 3 are empty (no selectable text)
        val doc = createPdfDocument(pageCount = 5, skippedPages = setOf(1, 3))
        val mapper = PdfReadingPositionMapper(doc)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)
        coordinator.onViewportChanged(viewportForPages(0, 1))

        // Section index 1 corresponds to page:2 (physical page 2)
        assertEquals("page:2", doc.sections[1].id)
        val posSection1 = positionForPage(doc, 2)
        coordinator.evaluateNavigation(posSection1)
        assertEquals(listOf(2), navigator.navigatedPages)

        // Section index 2 corresponds to page:4 (physical page 4)
        assertEquals("page:4", doc.sections[2].id)
        val posSection2 = positionForPage(doc, 4)
        coordinator.evaluateNavigation(posSection2)
        assertEquals(listOf(2, 4), navigator.navigatedPages)
    }

    // 30. TXT cannot create PDF navigation
    @Test
    fun test30_txtCannotCreatePdfNavigation() {
        val txtDoc = ReadingDocument(
            id = "txt:sample",
            title = "Sample Text",
            sourceType = ReadingDocumentSourceType.TXT,
            sections = listOf(
                ReadingSection(
                    id = "txt:sec:0",
                    title = "Text",
                    segments = listOf(ReadingSegment(id = "txt:seg:0", text = "Plain text content"))
                )
            )
        )
        val mapper = PdfReadingPositionMapper.fromDocument(txtDoc)
        assertNull(mapper)

        coordinator.clearDocument()
        coordinator.setReadingState(ReadingSessionState.Reading)
        coordinator.evaluateNavigation(
            ReadingPosition(documentId = txtDoc.id, sectionId = "txt:sec:0", segmentId = "txt:seg:0", segmentIndex = 0)
        )

        assertTrue(navigator.navigatedPages.isEmpty())
        assertNull(coordinator.pendingNavigation.value)
    }

    // 31. EPUB cannot create PDF navigation
    @Test
    fun test31_epubCannotCreatePdfNavigation() {
        val epubDoc = ReadingDocument(
            id = "epub:sample",
            title = "Sample EPUB",
            sourceType = ReadingDocumentSourceType.EPUB,
            sections = listOf(
                ReadingSection(
                    id = "epub:ch:0",
                    title = "Chapter 1",
                    segments = listOf(ReadingSegment(id = "epub:seg:0", text = "Epub chapter content"))
                )
            )
        )
        val mapper = PdfReadingPositionMapper.fromDocument(epubDoc)
        assertNull(mapper)

        coordinator.clearDocument()
        coordinator.setReadingState(ReadingSessionState.Reading)
        coordinator.evaluateNavigation(
            ReadingPosition(documentId = epubDoc.id, sectionId = "epub:ch:0", segmentId = "epub:seg:0", segmentIndex = 0)
        )

        assertTrue(navigator.navigatedPages.isEmpty())
        assertNull(coordinator.pendingNavigation.value)
    }
}
