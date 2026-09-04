package com.readme.app.ui.pdf

import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingPosition
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.ReadingEngine
import com.readme.app.reading.ReadingSessionState
import com.readme.app.reading.content.pdf.PdfReadingPositionMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 8F Tests.
 */
class PdfPositionReconcilerTest {

    private fun createPdfDocument(
        id: String = "pdf:doc1",
        pageCount: Int = 5,
        skippedPages: Set<Int> = emptySet(),
        emptyPages: Set<Int> = emptySet()
    ): ReadingDocument {
        val sections = mutableListOf<ReadingSection>()
        for (page in 0 until pageCount) {
            if (page in skippedPages) continue
            val segments = if (page in emptyPages) {
                listOf(ReadingSegment(id = "page:$page:seg:0", text = "   ")) // blank
            } else {
                listOf(
                    ReadingSegment(id = "page:$page:seg:0", text = "Sentence 1 of page $page"),
                    ReadingSegment(id = "page:$page:seg:1", text = "Sentence 2 of page $page")
                )
            }
            sections.add(
                ReadingSection(
                    id = "page:$page",
                    title = "Page $page",
                    segments = segments
                )
            )
        }
        return ReadingDocument(
            id = id,
            title = "Document $id",
            author = null,
            sourceType = ReadingDocumentSourceType.PDF,
            sections = sections
        )
    }

    private fun getReconciledPosition(
        doc: ReadingDocument,
        firstVisiblePage: Int,
        visiblePagesCount: Int = 1,
        isPdfActive: Boolean = true
    ) = PdfPositionReconciler.reconcile(
        document = doc,
        mapper = PdfReadingPositionMapper.fromDocument(doc),
        viewportState = PdfViewportState(firstVisiblePage, visiblePagesCount),
        isPdfActive = isPdfActive
    )

    // 1. Reconcile from page 0
    @Test
    fun test1_reconcileFromPage0_setsPositionToPage0() {
        val doc = createPdfDocument()
        val pos = getReconciledPosition(doc, 0)
        assertNotNull(pos)
        assertEquals("page:0", pos?.sectionId)
        assertEquals("page:0:seg:0", pos?.segmentId)
    }

    // 2. Reconcile from page 2
    @Test
    fun test2_reconcileFromPage2_setsPositionToPage2() {
        val doc = createPdfDocument()
        val pos = getReconciledPosition(doc, 2)
        assertEquals("page:2", pos?.sectionId)
    }

    // 3. Reconcile on empty document does nothing
    @Test
    fun test3_reconcileOnEmptyDocument_doesNothing() {
        val doc = createPdfDocument(pageCount = 0)
        val pos = getReconciledPosition(doc, 0)
        assertNull(pos)
    }

    // 4. Reconcile on page with no text (empty page) skips to next readable page
    @Test
    fun test4_reconcileOnEmptyPage_skipsToNextReadablePage() {
        val doc = createPdfDocument(pageCount = 5, emptyPages = setOf(2))
        val pos = getReconciledPosition(doc, 2)
        assertEquals("page:3", pos?.sectionId)
    }

    // 5. Reconcile on skipped page skips to next readable page
    @Test
    fun test5_reconcileOnSkippedPage_skipsToNextReadablePage() {
        val doc = createPdfDocument(pageCount = 5, skippedPages = setOf(2))
        val pos = getReconciledPosition(doc, 2)
        assertEquals("page:3", pos?.sectionId)
    }

    // 6. Reconcile on last empty page does nothing (no next readable)
    @Test
    fun test6_reconcileOnLastEmptyPage_doesNothing() {
        val doc = createPdfDocument(pageCount = 3, emptyPages = setOf(2))
        val pos = getReconciledPosition(doc, 2)
        assertNull(pos)
    }

    // 7. Reconcile invalid page out of bounds ignores
    @Test
    fun test7_reconcileOutOfBounds_doesNothing() {
        val doc = createPdfDocument()
        val pos = getReconciledPosition(doc, 10)
        assertNull(pos)
    }

    // 8. Reconcile when not PDF does nothing
    @Test
    fun test8_reconcileWhenNotPdf_doesNothing() {
        val doc = createPdfDocument()
        val pos = getReconciledPosition(doc, 0, isPdfActive = false)
        assertNull(pos)
    }

    // 9. Reconcile when viewport not ready (visiblePagesCount = 0) does nothing
    @Test
    fun test9_reconcileWhenViewportNotReady_doesNothing() {
        val doc = createPdfDocument()
        val pos = getReconciledPosition(doc, 2, visiblePagesCount = 0)
        assertNull(pos)
    }

    // 10. Reconcile from page 1 when page 1 is completely blank (no segments)
    @Test
    fun test10_reconcileOnCompletelyBlankPage_skips() {
        val doc = ReadingDocument("doc1", "Doc", null, ReadingDocumentSourceType.PDF, listOf(
            ReadingSection("page:0", "Page 0", listOf(ReadingSegment("s0", "Text"))),
            ReadingSection("page:1", "Page 1", emptyList()), // empty
            ReadingSection("page:2", "Page 2", listOf(ReadingSegment("s2", "Text")))
        ))
        val pos = getReconciledPosition(doc, 1)
        assertEquals("page:2", pos?.sectionId)
    }

    // 11. Reconcile sets correct segment index
    @Test
    fun test11_reconcileSetsCorrectSegmentIndex() {
        val doc = createPdfDocument()
        val pos = getReconciledPosition(doc, 2)
        assertEquals(4, pos?.segmentIndex)
    }

    // 12. Reconcile from page with first segment blank
    @Test
    fun test12_reconcileFirstSegmentBlank() {
        val doc = ReadingDocument("doc1", "Doc", null, ReadingDocumentSourceType.PDF, listOf(
            ReadingSection("page:0", "Page 0", listOf(ReadingSegment("s0", "  "), ReadingSegment("s1", "Text")))
        ))
        val pos = getReconciledPosition(doc, 0)
        assertEquals("s1", pos?.segmentId)
    }

    // 13. Reconcile does not affect non-PDF documents
    @Test
    fun test13_reconcileNonPdf() {
        val doc = ReadingDocument("txt1", "Txt", null, ReadingDocumentSourceType.TXT, listOf(
            ReadingSection("sec", "Sec", listOf(ReadingSegment("s0", "Text")))
        ))
        val pos = getReconciledPosition(doc, 0)
        assertNull(pos)
    }

    // 14. Reconcile with 0 mapped pages
    @Test
    fun test14_reconcileZeroMappedPages() {
        val doc = createPdfDocument(pageCount = 5, emptyPages = setOf(0, 1, 2, 3, 4))
        val pos = getReconciledPosition(doc, 0)
        assertNull(pos)
    }

    // 15. Reconcile on last page that is skipped
    @Test
    fun test15_reconcileLastPageSkipped() {
        val doc = createPdfDocument(pageCount = 5, skippedPages = setOf(4))
        val pos = getReconciledPosition(doc, 4)
        assertNull(pos)
    }

    // 16. Reconcile sets position exactly
    @Test
    fun test16_reconcileSetsPositionExactly() {
        val doc = createPdfDocument()
        val pos = getReconciledPosition(doc, 1)
        assertEquals(2, pos?.segmentIndex)
    }

    // 17. Reconcile with multiple empty pages sequentially
    @Test
    fun test17_reconcileMultipleEmptyPages() {
        val doc = createPdfDocument(pageCount = 5, emptyPages = setOf(1, 2, 3))
        val pos = getReconciledPosition(doc, 1)
        assertEquals("page:4", pos?.sectionId)
    }

    // ReadingEngine Tests for setPosition
    
    @Test
    fun test18_engineSetPosition_updatesCurrentPosition() {
        val doc = createPdfDocument()
        val engine = ReadingEngine(doc)
        val pos = getReconciledPosition(doc, 3)!!
        engine.setPosition(pos)
        assertEquals("page:3", engine.currentPosition.value?.sectionId)
        assertEquals(6, engine.currentSegmentIndex.value)
    }

    @Test
    fun test19_engineSetPosition_onCompletedState_changesToStopped() {
        val doc = createPdfDocument(pageCount = 1) // 2 segments
        val engine = ReadingEngine(doc)
        engine.startSession()
        engine.advance() // segment 1
        engine.advance() // out of bounds -> Completed
        assertEquals(ReadingSessionState.Completed, engine.readingState.value)
        
        val pos = getReconciledPosition(doc, 0)!!
        engine.setPosition(pos)
        
        assertEquals(ReadingSessionState.Stopped, engine.readingState.value)
        assertEquals("page:0", engine.currentPosition.value?.sectionId)
    }

    @Test
    fun test20_engineSetPosition_onIdle_remainsIdle() {
        val doc = createPdfDocument()
        val engine = ReadingEngine(doc)
        val pos = getReconciledPosition(doc, 2)!!
        engine.setPosition(pos)
        assertEquals(ReadingSessionState.Idle, engine.readingState.value)
    }

    @Test
    fun test21_engineSetPosition_onReading_remainsReading() {
        val doc = createPdfDocument()
        val engine = ReadingEngine(doc)
        engine.startSession()
        val pos = getReconciledPosition(doc, 2)!!
        engine.setPosition(pos)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)
    }

    @Test
    fun test22_engineSetPosition_invalidDocument_ignored() {
        val doc = createPdfDocument()
        val engine = ReadingEngine(doc)
        val pos = getReconciledPosition(createPdfDocument(id = "pdf:other"), 2)!!
        engine.setPosition(pos)
        assertNull(engine.currentPosition.value)
    }

    @Test
    fun test23_engineSetPosition_invalidSegment_ignored() {
        val doc = createPdfDocument()
        val engine = ReadingEngine(doc)
        val badPos = ReadingPosition("pdf:doc1", "invalid", "invalid", 100)
        engine.setPosition(badPos)
        assertNull(engine.currentPosition.value)
    }

    @Test
    fun test24_engineSetPosition_thenStart_startsFromNewPosition() {
        val doc = createPdfDocument()
        val engine = ReadingEngine(doc)
        val pos = getReconciledPosition(doc, 2)!!
        engine.setPosition(pos)
        // Resume from current position should be called!
        val seg = engine.resumeFromCurrentPosition()
        assertEquals("page:2:seg:0", seg?.id)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)
    }

    @Test
    fun test25_engineSetPosition_thenResume_resumesFromNewPosition() {
        val doc = createPdfDocument()
        val engine = ReadingEngine(doc)
        engine.startSession()
        engine.stop()
        val pos = getReconciledPosition(doc, 3)!!
        engine.setPosition(pos)
        
        val seg = engine.resumeFromCurrentPosition()
        assertEquals("page:3:seg:0", seg?.id)
    }

    @Test
    fun test26_engineSetPosition_middleOfSection() {
        val doc = createPdfDocument()
        val engine = ReadingEngine(doc)
        // Target second segment on page 2
        val pos = doc.positionForSegmentId("page:2:seg:1")!!
        engine.setPosition(pos)
        val seg = engine.resumeFromCurrentPosition()
        assertEquals("page:2:seg:1", seg?.id)
    }

    @Test
    fun test27_engineSetPosition_thenAdvance_advancesToNext() {
        val doc = createPdfDocument()
        val engine = ReadingEngine(doc)
        val pos = doc.positionForSegmentId("page:2:seg:1")!!
        engine.setPosition(pos)
        engine.resumeFromCurrentPosition() // starts reading
        val next = engine.advance()
        assertEquals("page:3:seg:0", next?.id)
    }

    @Test
    fun test28_reconcile_whenMultipleEmptyTrailingPages_doesNothing() {
        val doc = createPdfDocument(pageCount = 5, emptyPages = setOf(3, 4))
        val pos = getReconciledPosition(doc, 3)
        assertNull(pos)
    }

    @Test
    fun test29_reconcile_startWithSkippedPage_skipsProperly() {
        val doc = createPdfDocument(pageCount = 5, skippedPages = setOf(0))
        val pos = getReconciledPosition(doc, 0)
        assertEquals("page:1", pos?.sectionId)
    }

    @Test
    fun test30_reconcile_emptyDocumentId_ignored() {
        val doc = createPdfDocument(id = "")
        val pos = getReconciledPosition(doc, 0)
        assertNull(pos)
    }

    @Test
    fun test31_engineSetPosition_emptyDocument_ignored() {
        val doc = createPdfDocument(id = "")
        val engine = ReadingEngine(doc)
        val goodDoc = createPdfDocument()
        val pos = getReconciledPosition(goodDoc, 0)!!
        engine.setPosition(pos)
        assertNull(engine.currentPosition.value)
    }
}
