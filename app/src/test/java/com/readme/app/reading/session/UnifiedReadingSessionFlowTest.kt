package com.readme.app.reading.session

import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState
import com.readme.app.reading.DocumentLoadState
import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentMetadata
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingEngine
import com.readme.app.reading.ReadingPosition
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.ReadingSessionCoordinator
import com.readme.app.reading.ReadingSessionState
import com.readme.app.speech.TtsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end tests for Phase 9A unified reading session flow.
 */
class UnifiedReadingSessionFlowTest {

    private lateinit var engine: ReadingEngine
    private lateinit var coordinator: ReadingSessionCoordinator

    private fun createDoc(
        id: String,
        title: String,
        sourceType: ReadingDocumentSourceType,
        sentences: List<String>
    ): ReadingDocument {
        val segments = sentences.mapIndexed { idx, text ->
            ReadingSegment(id = "${id}_s_$idx", text = text)
        }
        return ReadingDocument(
            id = id,
            metadata = ReadingDocumentMetadata(
                title = title,
                sourceType = sourceType
            ),
            sections = listOf(
                ReadingSection(id = "${id}_sec_0", title = "Main", segments = segments)
            )
        )
    }

    @Before
    fun setUp() {
        engine = ReadingEngine()
        coordinator = ReadingSessionCoordinator(engine)
    }

    @Test
    fun completeReadingLifecycle_fromLoadToCompletionAndRestart() {
        // 1. Initial State: No document
        assertEquals(DocumentLoadState.NoDocument, coordinator.activeDocumentState.value.loadState)
        assertNull(coordinator.readingSessionState.value.activeDocumentId)

        // 2. Load TXT Document
        val token = coordinator.startLoading("essay.txt")
        val doc = createDoc(
            id = "essay_1",
            title = "Essay on Architecture",
            sourceType = ReadingDocumentSourceType.TXT,
            sentences = listOf("First paragraph.", "Second paragraph.")
        )
        assertTrue(coordinator.onDocumentLoaded(token, doc, "essay.txt"))

        val docState = coordinator.activeDocumentState.value
        assertEquals("essay_1", docState.documentId)
        assertEquals(ReadingDocumentSourceType.TXT, docState.sourceType)

        // 3. Start Reading
        val seg0 = coordinator.startReading()
        assertNotNull(seg0)
        assertEquals("First paragraph.", seg0?.text)
        assertEquals(ReadingSessionState.Reading, coordinator.readingSessionState.value.sessionState)
        assertEquals("essay_1", coordinator.readingSessionState.value.activeDocumentId)

        // 4. Advance
        val seg1 = coordinator.advanceReading()
        assertNotNull(seg1)
        assertEquals("Second paragraph.", seg1?.text)

        // 5. Complete
        val endSeg = coordinator.advanceReading()
        assertNull(endSeg)
        coordinator.onReadingCompleted()

        assertEquals(ReadingSessionState.Completed, coordinator.readingSessionState.value.sessionState)
        assertTrue(coordinator.readingSessionState.value.isCompleted)
        assertEquals("essay_1", coordinator.readingSessionState.value.activeDocumentId)

        // 6. Restart from beginning
        val restartSeg = coordinator.startReading()
        assertNotNull(restartSeg)
        assertEquals("First paragraph.", restartSeg?.text)
        assertEquals(ReadingSessionState.Reading, coordinator.readingSessionState.value.sessionState)
    }

    @Test
    fun documentSwitching_duringActiveReading_safelyTransfersState() {
        // Load PDF
        val tokenPdf = coordinator.startLoading("manual.pdf")
        val pdfDoc = createDoc(
            id = "pdf_manual",
            title = "User Manual",
            sourceType = ReadingDocumentSourceType.PDF,
            sentences = listOf("Page 1 Intro.", "Page 1 Safety.")
        )
        coordinator.onDocumentLoaded(tokenPdf, pdfDoc, "manual.pdf")
        coordinator.startReading()
        assertEquals(ReadingSessionState.Reading, coordinator.readingSessionState.value.sessionState)

        // Now load EPUB while reading PDF
        val tokenEpub = coordinator.startLoading("guide.epub")
        // During loading, reading must be stopped and state reset
        assertFalse(coordinator.readingSessionState.value.isReading)
        assertEquals(ReadingSessionState.Idle, coordinator.readingSessionState.value.sessionState)
        assertNull(coordinator.readingSessionState.value.activeDocumentId)

        val epubDoc = createDoc(
            id = "epub_guide",
            title = "Field Guide",
            sourceType = ReadingDocumentSourceType.EPUB,
            sentences = listOf("Welcome to the wilderness.")
        )
        coordinator.onDocumentLoaded(tokenEpub, epubDoc, "guide.epub")

        val state = coordinator.activeDocumentState.value
        assertEquals("epub_guide", state.documentId)
        assertEquals(ReadingDocumentSourceType.EPUB, state.sourceType)
        assertEquals(ReadingSessionState.Idle, coordinator.readingSessionState.value.sessionState)

        // Start reading EPUB
        val epubSeg = coordinator.startReading()
        assertNotNull(epubSeg)
        assertEquals("Welcome to the wilderness.", epubSeg?.text)
    }

    @Test
    fun raceConditionSafety_multipleRapidLoads_onlyLatestSucceeds() {
        val token1 = coordinator.startLoading("file1.txt")
        val token2 = coordinator.startLoading("file2.txt")
        val token3 = coordinator.startLoading("file3.txt")

        // Responses arrive out of order: token2, token1, token3
        val doc2 = createDoc("id_2", "Doc 2", ReadingDocumentSourceType.TXT, listOf("Text 2"))
        val doc1 = createDoc("id_1", "Doc 1", ReadingDocumentSourceType.TXT, listOf("Text 1"))
        val doc3 = createDoc("id_3", "Doc 3", ReadingDocumentSourceType.TXT, listOf("Text 3"))

        assertFalse(coordinator.onDocumentLoaded(token2, doc2, "file2.txt"))
        assertFalse(coordinator.onDocumentLoaded(token1, doc1, "file1.txt"))
        assertTrue(coordinator.onDocumentLoaded(token3, doc3, "file3.txt"))

        assertEquals("id_3", coordinator.activeDocumentState.value.documentId)
    }
}
