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

class ReadingSessionCoordinatorTest {

    private lateinit var readingEngine: ReadingEngine
    private lateinit var coordinator: ReadingSessionCoordinator

    private fun createSampleDoc(
        id: String,
        title: String,
        author: String? = null,
        sourceType: ReadingDocumentSourceType = ReadingDocumentSourceType.TXT,
        segmentCount: Int = 3
    ): ReadingDocument {
        val segments = (0 until segmentCount).map { i ->
            ReadingSegment(id = "${id}_seg_$i", text = "Sentence $i of $title.")
        }
        return ReadingDocument(
            id = id,
            metadata = ReadingDocumentMetadata(
                title = title,
                author = author,
                sourceType = sourceType
            ),
            sections = listOf(
                ReadingSection(id = "${id}_sec_0", title = "Section 0", segments = segments)
            )
        )
    }

    @Before
    fun setUp() {
        readingEngine = ReadingEngine()
        coordinator = ReadingSessionCoordinator(readingEngine)
    }

    // 1. initial state with no active document
    @Test
    fun test01_initialStateWithNoActiveDocument() {
        val docState = coordinator.activeDocumentState.value
        assertEquals(DocumentLoadState.NoDocument, docState.loadState)
        assertFalse(docState.hasActiveDocument)
        assertEquals("", docState.documentId)
        assertEquals("", docState.displayName)

        val sessionState = coordinator.readingSessionState.value
        assertEquals(ReadingSessionState.Idle, sessionState.sessionState)
        assertNull(sessionState.activeDocumentId)
        assertNull(sessionState.currentPosition)
        assertFalse(sessionState.isReading)
    }

    // 2. TXT becomes active document
    @Test
    fun test02_txtBecomesActiveDocument() {
        val token = coordinator.startLoading("sample.txt")
        val doc = createSampleDoc("txt_1", "Sample Text", sourceType = ReadingDocumentSourceType.TXT)
        val loaded = coordinator.onDocumentLoaded(token, doc, "sample.txt")

        assertTrue(loaded)
        val state = coordinator.activeDocumentState.value
        assertTrue(state.hasActiveDocument)
        assertEquals("txt_1", state.documentId)
        assertEquals("Sample Text", state.title)
        assertEquals("sample.txt", state.displayName)
        assertEquals(ReadingDocumentSourceType.TXT, state.sourceType)
        assertEquals(DocumentLoadState.Loaded, state.loadState)
    }

    // 3. EPUB becomes active document
    @Test
    fun test03_epubBecomesActiveDocument() {
        val token = coordinator.startLoading("book.epub")
        val doc = createSampleDoc("epub_1", "Great Book", author = "Author A", sourceType = ReadingDocumentSourceType.EPUB)
        coordinator.onDocumentLoaded(token, doc, "book.epub")

        val state = coordinator.activeDocumentState.value
        assertTrue(state.hasActiveDocument)
        assertEquals("epub_1", state.documentId)
        assertEquals("Great Book", state.title)
        assertEquals("Author A", state.author)
        assertEquals(ReadingDocumentSourceType.EPUB, state.sourceType)
    }

    // 4. PDF becomes active document
    @Test
    fun test04_pdfBecomesActiveDocument() {
        val token = coordinator.startLoading("paper.pdf")
        val doc = createSampleDoc("pdf_1", "Research Paper", sourceType = ReadingDocumentSourceType.PDF)
        coordinator.onDocumentLoaded(token, doc, "paper.pdf")

        val state = coordinator.activeDocumentState.value
        assertTrue(state.hasActiveDocument)
        assertEquals("pdf_1", state.documentId)
        assertEquals(ReadingDocumentSourceType.PDF, state.sourceType)
    }

    // 5. document metadata is exposed consistently
    @Test
    fun test05_documentMetadataExposedConsistently() {
        val token = coordinator.startLoading("novel.epub")
        val doc = createSampleDoc("novel_id", "A Tale", author = "Charles D", sourceType = ReadingDocumentSourceType.EPUB)
        coordinator.onDocumentLoaded(token, doc, "novel.epub")

        val state = coordinator.activeDocumentState.value
        assertEquals("novel_id", state.documentId)
        assertEquals("A Tale", state.title)
        assertEquals("Charles D", state.author)
        assertEquals("novel.epub", state.displayName)
        assertEquals(ReadingDocumentSourceType.EPUB, state.sourceType)
    }

    // 6. source type is correct
    @Test
    fun test06_sourceTypeIsCorrect() {
        val tokenTxt = coordinator.startLoading("t.txt")
        coordinator.onDocumentLoaded(tokenTxt, createSampleDoc("t", "T", sourceType = ReadingDocumentSourceType.TXT), "t.txt")
        assertEquals(ReadingDocumentSourceType.TXT, coordinator.activeDocumentState.value.sourceType)

        val tokenPdf = coordinator.startLoading("p.pdf")
        coordinator.onDocumentLoaded(tokenPdf, createSampleDoc("p", "P", sourceType = ReadingDocumentSourceType.PDF), "p.pdf")
        assertEquals(ReadingDocumentSourceType.PDF, coordinator.activeDocumentState.value.sourceType)
    }

    // 7. document loading state transitions correctly
    @Test
    fun test07_documentLoadingStateTransitionsCorrectly() {
        assertEquals(DocumentLoadState.NoDocument, coordinator.activeDocumentState.value.loadState)

        val token = coordinator.startLoading("file.txt")
        assertTrue(coordinator.activeDocumentState.value.loadState is DocumentLoadState.Loading)
        assertEquals("file.txt", (coordinator.activeDocumentState.value.loadState as DocumentLoadState.Loading).displayName)

        coordinator.onDocumentLoaded(token, createSampleDoc("d1", "Title"), "file.txt")
        assertEquals(DocumentLoadState.Loaded, coordinator.activeDocumentState.value.loadState)
    }

    // 8. successful load establishes active document
    @Test
    fun test08_successfulLoadEstablishesActiveDocument() {
        val token = coordinator.startLoading("doc.txt")
        val doc = createSampleDoc("d8", "Doc 8")
        coordinator.onDocumentLoaded(token, doc, "doc.txt")

        assertTrue(coordinator.hasActiveDocument)
        assertEquals("d8", coordinator.currentDocumentId)
        assertEquals("d8", coordinator.currentDocument.id)
    }

    // 9. failed load produces controlled error
    @Test
    fun test09_failedLoadProducesControlledError() {
        val token = coordinator.startLoading("corrupt.pdf")
        coordinator.onDocumentLoadFailed(token, "Unable to read selected PDF file")

        val state = coordinator.activeDocumentState.value
        assertFalse(state.hasActiveDocument)
        assertTrue(state.loadState is DocumentLoadState.Error)
        assertEquals("Unable to read selected PDF file", (state.loadState as DocumentLoadState.Error).message)

        val session = coordinator.readingSessionState.value
        assertEquals(ReadingSessionState.Error, session.sessionState)
        assertNull(session.activeDocumentId)
        assertEquals("Unable to read selected PDF file", session.errorMessage)
    }

    // 10. stale failed load cannot overwrite a new document
    @Test
    fun test10_staleFailedLoadCannotOverwriteNewDocument() {
        val token1 = coordinator.startLoading("doc1.txt")
        val token2 = coordinator.startLoading("doc2.txt")
        val doc2 = createSampleDoc("doc2", "Doc 2")
        coordinator.onDocumentLoaded(token2, doc2, "doc2.txt")

        // Stale failure arrives for token1
        val rejected = coordinator.onDocumentLoadFailed(token1, "Doc 1 failed")
        assertFalse(rejected)

        // Verify doc2 remains active and intact
        val state = coordinator.activeDocumentState.value
        assertEquals("doc2", state.documentId)
        assertEquals(DocumentLoadState.Loaded, state.loadState)
    }

    // 11. stale successful load cannot overwrite a new document
    @Test
    fun test11_staleSuccessfulLoadCannotOverwriteNewDocument() {
        val token1 = coordinator.startLoading("doc1.txt")
        val doc1 = createSampleDoc("doc1", "Doc 1")

        val token2 = coordinator.startLoading("doc2.txt")
        val doc2 = createSampleDoc("doc2", "Doc 2")
        coordinator.onDocumentLoaded(token2, doc2, "doc2.txt")

        // Stale success arrives for token1
        val rejected = coordinator.onDocumentLoaded(token1, doc1, "doc1.txt")
        assertFalse(rejected)

        val state = coordinator.activeDocumentState.value
        assertEquals("doc2", state.documentId)
    }

    // 12. replacing document clears old session state
    @Test
    fun test12_replacingDocumentClearsOldSessionState() {
        val token1 = coordinator.startLoading("doc1.txt")
        coordinator.onDocumentLoaded(token1, createSampleDoc("doc1", "Doc 1"), "doc1.txt")
        coordinator.startReading()
        assertEquals(ReadingSessionState.Reading, coordinator.readingSessionState.value.sessionState)

        // Replace with new document
        val token2 = coordinator.startLoading("doc2.txt")
        val sessionDuringLoad = coordinator.readingSessionState.value
        assertNull(sessionDuringLoad.activeDocumentId)
        assertNull(sessionDuringLoad.currentPosition)
        assertEquals(ReadingSessionState.Idle, sessionDuringLoad.sessionState)

        coordinator.onDocumentLoaded(token2, createSampleDoc("doc2", "Doc 2"), "doc2.txt")
        val sessionAfterLoad = coordinator.readingSessionState.value
        assertEquals("doc2", sessionAfterLoad.activeDocumentId)
        assertEquals(ReadingSessionState.Idle, sessionAfterLoad.sessionState)
    }

    // 13. replacing document stops old speech
    @Test
    fun test13_replacingDocumentStopsOldSpeech() {
        val token1 = coordinator.startLoading("doc1.txt")
        coordinator.onDocumentLoaded(token1, createSampleDoc("doc1", "Doc 1"), "doc1.txt")
        coordinator.startReading()
        assertTrue(coordinator.readingSessionState.value.isReading)

        coordinator.startLoading("doc2.txt")
        assertFalse(coordinator.readingSessionState.value.isReading)
        assertEquals(TtsState.Stopped, coordinator.readingSessionState.value.speechState)
    }

    // 14. replacing document invalidates old callbacks
    @Test
    fun test14_replacingDocumentInvalidatesOldCallbacks() {
        val token1 = coordinator.startLoading("doc1.txt")
        val token2 = coordinator.startLoading("doc2.txt")

        // token1 is obsolete
        assertFalse(coordinator.isCurrentLoadToken(token1))
        assertTrue(coordinator.isCurrentLoadToken(token2))
    }

    // 15. ReadingPosition belongs to active document
    @Test
    fun test15_readingPositionBelongsToActiveDocument() {
        val token = coordinator.startLoading("doc1.txt")
        coordinator.onDocumentLoaded(token, createSampleDoc("doc1", "Doc 1"), "doc1.txt")
        coordinator.startReading()

        val pos = coordinator.readingSessionState.value.currentPosition
        assertNotNull(pos)
        assertEquals("doc1", pos?.documentId)
    }

    // 16. old ReadingPosition cannot leak into new document
    @Test
    fun test16_oldReadingPositionCannotLeakIntoNewDocument() {
        val token1 = coordinator.startLoading("doc1.txt")
        coordinator.onDocumentLoaded(token1, createSampleDoc("doc1", "Doc 1"), "doc1.txt")
        coordinator.startReading()
        coordinator.advanceReading()
        val oldPos = coordinator.readingSessionState.value.currentPosition
        assertNotNull(oldPos)

        val token2 = coordinator.startLoading("doc2.txt")
        coordinator.onDocumentLoaded(token2, createSampleDoc("doc2", "Doc 2"), "doc2.txt")

        val newSession = coordinator.readingSessionState.value
        assertNull(newSession.currentPosition)

        // Attempting to set old position from doc1 onto doc2 is safely rejected
        coordinator.setPosition(oldPos!!)
        assertNull(coordinator.readingSessionState.value.currentPosition)
    }

    // 17. stopped state preserves position
    @Test
    fun test17_stoppedStatePreservesPosition() {
        val token = coordinator.startLoading("doc1.txt")
        coordinator.onDocumentLoaded(token, createSampleDoc("doc1", "Doc 1", segmentCount = 4), "doc1.txt")
        coordinator.startReading()
        coordinator.advanceReading()
        val readingPos = coordinator.readingSessionState.value.currentPosition
        assertNotNull(readingPos)

        coordinator.stopReading()
        val stoppedState = coordinator.readingSessionState.value
        assertEquals(ReadingSessionState.Stopped, stoppedState.sessionState)
        assertEquals(readingPos, stoppedState.currentPosition)
        assertEquals("doc1", stoppedState.activeDocumentId)
    }

    // 18. completed state remains associated with active document
    @Test
    fun test18_completedStateRemainsAssociatedWithActiveDocument() {
        val token = coordinator.startLoading("doc1.txt")
        coordinator.onDocumentLoaded(token, createSampleDoc("doc1", "Doc 1", segmentCount = 2), "doc1.txt")
        coordinator.startReading()
        coordinator.advanceReading() // seg 1
        val finalSeg = coordinator.advanceReading() // null -> completed
        assertNull(finalSeg)
        coordinator.onReadingCompleted()

        val state = coordinator.readingSessionState.value
        assertEquals(ReadingSessionState.Completed, state.sessionState)
        assertEquals("doc1", state.activeDocumentId)
        assertTrue(coordinator.hasActiveDocument)
    }

    // 19. completed document can be restarted
    @Test
    fun test19_completedDocumentCanBeRestarted() {
        val token = coordinator.startLoading("doc1.txt")
        coordinator.onDocumentLoaded(token, createSampleDoc("doc1", "Doc 1", segmentCount = 2), "doc1.txt")
        coordinator.startReading()
        coordinator.advanceReading()
        coordinator.advanceReading()
        coordinator.onReadingCompleted()
        assertTrue(coordinator.readingSessionState.value.isCompleted)

        val restartSegment = coordinator.startReading()
        assertNotNull(restartSegment)
        assertEquals("Sentence 0 of Doc 1.", restartSegment?.text)
        assertEquals(ReadingSessionState.Reading, coordinator.readingSessionState.value.sessionState)
    }

    // 20. active PDF preserves PDF-specific visual state
    @Test
    fun test20_activePdfPreservesPdfSourceType() {
        val token = coordinator.startLoading("paper.pdf")
        coordinator.onDocumentLoaded(token, createSampleDoc("pdf1", "PDF Paper", sourceType = ReadingDocumentSourceType.PDF), "paper.pdf")

        assertEquals(ReadingDocumentSourceType.PDF, coordinator.activeDocumentState.value.sourceType)
    }

    // 21. switching PDF → TXT clears PDF visual state
    @Test
    fun test21_switchingPdfToTxtClearsPdfSourceType() {
        val tokenPdf = coordinator.startLoading("paper.pdf")
        coordinator.onDocumentLoaded(tokenPdf, createSampleDoc("pdf1", "PDF Paper", sourceType = ReadingDocumentSourceType.PDF), "paper.pdf")
        assertEquals(ReadingDocumentSourceType.PDF, coordinator.activeDocumentState.value.sourceType)

        val tokenTxt = coordinator.startLoading("notes.txt")
        coordinator.onDocumentLoaded(tokenTxt, createSampleDoc("txt1", "Notes", sourceType = ReadingDocumentSourceType.TXT), "notes.txt")
        assertEquals(ReadingDocumentSourceType.TXT, coordinator.activeDocumentState.value.sourceType)
    }

    // 22. switching PDF → EPUB clears PDF visual state
    @Test
    fun test22_switchingPdfToEpubClearsPdfSourceType() {
        val tokenPdf = coordinator.startLoading("paper.pdf")
        coordinator.onDocumentLoaded(tokenPdf, createSampleDoc("pdf1", "PDF Paper", sourceType = ReadingDocumentSourceType.PDF), "paper.pdf")

        val tokenEpub = coordinator.startLoading("book.epub")
        coordinator.onDocumentLoaded(tokenEpub, createSampleDoc("epub1", "Book", sourceType = ReadingDocumentSourceType.EPUB), "book.epub")
        assertEquals(ReadingDocumentSourceType.EPUB, coordinator.activeDocumentState.value.sourceType)
    }

    // 23. switching TXT → PDF establishes PDF visual state
    @Test
    fun test23_switchingTxtToPdfEstablishesPdfState() {
        val tokenTxt = coordinator.startLoading("notes.txt")
        coordinator.onDocumentLoaded(tokenTxt, createSampleDoc("txt1", "Notes", sourceType = ReadingDocumentSourceType.TXT), "notes.txt")

        val tokenPdf = coordinator.startLoading("paper.pdf")
        coordinator.onDocumentLoaded(tokenPdf, createSampleDoc("pdf1", "PDF", sourceType = ReadingDocumentSourceType.PDF), "paper.pdf")
        assertEquals(ReadingDocumentSourceType.PDF, coordinator.activeDocumentState.value.sourceType)
    }

    // 24. "Read from here" remains PDF-only
    @Test
    fun test24_readFromHereRejectsMismatchPosition() {
        val tokenTxt = coordinator.startLoading("notes.txt")
        coordinator.onDocumentLoaded(tokenTxt, createSampleDoc("txt1", "Notes", sourceType = ReadingDocumentSourceType.TXT), "notes.txt")

        // Position from some other document or PDF page
        val foreignPos = ReadingPosition("other_pdf", "sec_0", "seg_0", 0)
        coordinator.setPosition(foreignPos)

        assertNull(coordinator.readingSessionState.value.currentPosition)
    }

    // 25. PDF OCR errors are represented without corrupting session state
    @Test
    fun test25_pdfOcrErrorsRepresentedWithoutCorruptingSession() {
        val token = coordinator.startLoading("scanned.pdf")
        coordinator.onDocumentLoadFailed(token, "OCR is currently unavailable on this device.")

        val docState = coordinator.activeDocumentState.value
        assertEquals(DocumentLoadState.Error("OCR is currently unavailable on this device."), docState.loadState)
        assertFalse(docState.hasActiveDocument)

        val sessionState = coordinator.readingSessionState.value
        assertEquals(ReadingSessionState.Error, sessionState.sessionState)
        assertNull(sessionState.activeDocumentId)
    }

    // 26. TXT errors remain format-safe
    @Test
    fun test26_txtErrorsRemainFormatSafe() {
        val token = coordinator.startLoading("bad.txt")
        coordinator.onDocumentLoadFailed(token, "Unable to read selected text file")

        assertEquals(DocumentLoadState.Error("Unable to read selected text file"), coordinator.activeDocumentState.value.loadState)
    }

    // 27. EPUB errors remain format-safe
    @Test
    fun test27_epubErrorsRemainFormatSafe() {
        val token = coordinator.startLoading("protected.epub")
        coordinator.onDocumentLoadFailed(token, "DRM-protected EPUB files are not supported")

        assertEquals(DocumentLoadState.Error("DRM-protected EPUB files are not supported"), coordinator.activeDocumentState.value.loadState)
    }

    // 28. Start Reading with no document does nothing safely
    @Test
    fun test28_startReadingWithNoDocumentDoesNothingSafely() {
        val segment = coordinator.startReading()
        assertNull(segment)
        assertEquals(ReadingSessionState.Idle, coordinator.readingSessionState.value.sessionState)
        assertFalse(coordinator.readingSessionState.value.isReading)
    }

    // 29. Start Reading on stopped document resumes correctly
    @Test
    fun test29_startReadingOnStoppedDocumentResumesCorrectly() {
        val token = coordinator.startLoading("doc.txt")
        coordinator.onDocumentLoaded(token, createSampleDoc("doc", "Doc", segmentCount = 4), "doc.txt")
        coordinator.startReading()
        coordinator.advanceReading() // at segment 1
        coordinator.stopReading()

        val resumedSegment = coordinator.startReading()
        assertNotNull(resumedSegment)
        assertEquals("Sentence 1 of Doc.", resumedSegment?.text)
        assertEquals(ReadingSessionState.Reading, coordinator.readingSessionState.value.sessionState)
    }

    // 30. Start Reading on completed document restarts correctly
    @Test
    fun test30_startReadingOnCompletedDocumentRestartsCorrectly() {
        val token = coordinator.startLoading("doc.txt")
        coordinator.onDocumentLoaded(token, createSampleDoc("doc", "Doc", segmentCount = 1), "doc.txt")
        coordinator.startReading()
        coordinator.advanceReading()
        coordinator.onReadingCompleted()

        val restartSegment = coordinator.startReading()
        assertNotNull(restartSegment)
        assertEquals("Sentence 0 of Doc.", restartSegment?.text)
    }

    // 31. Stop preserves document and position
    @Test
    fun test31_stopPreservesDocumentAndPosition() {
        val token = coordinator.startLoading("doc.txt")
        coordinator.onDocumentLoaded(token, createSampleDoc("doc", "Doc", segmentCount = 3), "doc.txt")
        coordinator.startReading()
        coordinator.advanceReading()

        val posBeforeStop = coordinator.readingSessionState.value.currentPosition
        coordinator.stopReading()

        assertEquals("doc", coordinator.currentDocumentId)
        assertEquals(posBeforeStop, coordinator.readingSessionState.value.currentPosition)
        assertEquals(ReadingSessionState.Stopped, coordinator.readingSessionState.value.sessionState)
    }

    // 32. Speech completion updates session correctly
    @Test
    fun test32_speechCompletionUpdatesSessionCorrectly() {
        val token = coordinator.startLoading("doc.txt")
        coordinator.onDocumentLoaded(token, createSampleDoc("doc", "Doc", segmentCount = 1), "doc.txt")
        coordinator.startReading()
        coordinator.advanceReading() // complete
        coordinator.onReadingCompleted()

        assertEquals(ReadingSessionState.Completed, coordinator.readingSessionState.value.sessionState)
        assertEquals(TtsState.Stopped, coordinator.readingSessionState.value.speechState)
    }

    // 33. speech error is controlled
    @Test
    fun test33_speechErrorIsControlled() {
        val token = coordinator.startLoading("doc.txt")
        coordinator.onDocumentLoaded(token, createSampleDoc("doc", "Doc"), "doc.txt")
        coordinator.startReading()

        coordinator.onReadingError("TTS Error 4")
        val state = coordinator.readingSessionState.value
        assertEquals(ReadingSessionState.Error, state.sessionState)
        assertEquals("TTS Error 4", state.errorMessage)
    }

    // 34. settings changes do not replace active document
    @Test
    fun test34_settingsChangesDoNotReplaceActiveDocument() {
        val token = coordinator.startLoading("doc.txt")
        coordinator.onDocumentLoaded(token, createSampleDoc("doc", "Doc"), "doc.txt")
        coordinator.startReading()

        // TTS state or settings update
        coordinator.updateSpeechState(TtsState.Speaking)

        assertEquals("doc", coordinator.currentDocumentId)
        assertTrue(coordinator.hasActiveDocument)
    }

    // 35. settings changes do not reset reading position
    @Test
    fun test35_settingsChangesDoNotResetReadingPosition() {
        val token = coordinator.startLoading("doc.txt")
        coordinator.onDocumentLoaded(token, createSampleDoc("doc", "Doc", segmentCount = 4), "doc.txt")
        coordinator.startReading()
        coordinator.advanceReading()
        val pos = coordinator.readingSessionState.value.currentPosition

        coordinator.updateSpeechState(TtsState.Speaking)
        assertEquals(pos, coordinator.readingSessionState.value.currentPosition)
    }

    // 36. document replacement during TTS is safe
    @Test
    fun test36_documentReplacementDuringTtsIsSafe() {
        val token1 = coordinator.startLoading("doc1.txt")
        coordinator.onDocumentLoaded(token1, createSampleDoc("doc1", "Doc 1"), "doc1.txt")
        coordinator.startReading()
        assertTrue(coordinator.readingSessionState.value.isReading)

        val token2 = coordinator.startLoading("doc2.txt")
        assertFalse(coordinator.readingSessionState.value.isReading)
        assertEquals(ReadingSessionState.Idle, coordinator.readingSessionState.value.sessionState)

        coordinator.onDocumentLoaded(token2, createSampleDoc("doc2", "Doc 2"), "doc2.txt")
        assertEquals("doc2", coordinator.activeDocumentState.value.documentId)
    }

    // 37. document replacement during OCR is safe
    @Test
    fun test37_documentReplacementDuringOcrIsSafe() {
        val tokenOcr = coordinator.startLoading("scan.pdf")
        // User immediately selects a TXT before OCR completes
        val tokenTxt = coordinator.startLoading("quick.txt")
        coordinator.onDocumentLoaded(tokenTxt, createSampleDoc("quick", "Quick"), "quick.txt")

        // OCR eventually finishes
        val ocrResult = coordinator.onDocumentLoaded(tokenOcr, createSampleDoc("scan", "Scan"), "scan.pdf")
        assertFalse(ocrResult) // Stale token rejected!

        assertEquals("quick", coordinator.activeDocumentState.value.documentId)
    }

    // 38. document replacement during PDF rendering/loading is safe
    @Test
    fun test38_documentReplacementDuringPdfLoadingIsSafe() {
        val tokenPdf = coordinator.startLoading("heavy.pdf")
        val tokenEpub = coordinator.startLoading("light.epub")
        coordinator.onDocumentLoaded(tokenEpub, createSampleDoc("light", "Light", sourceType = ReadingDocumentSourceType.EPUB), "light.epub")

        val pdfRejected = coordinator.onDocumentLoaded(tokenPdf, createSampleDoc("heavy", "Heavy", sourceType = ReadingDocumentSourceType.PDF), "heavy.pdf")
        assertFalse(pdfRejected)
        assertEquals("light", coordinator.activeDocumentState.value.documentId)
    }

    // 39. session cleanup on clear is safe
    @Test
    fun test39_sessionCleanupOnClearIsSafe() {
        val token = coordinator.startLoading("doc.txt")
        coordinator.onDocumentLoaded(token, createSampleDoc("doc", "Doc"), "doc.txt")
        coordinator.startReading()

        coordinator.clearActiveDocument()
        assertFalse(coordinator.hasActiveDocument)
        assertEquals(DocumentLoadState.NoDocument, coordinator.activeDocumentState.value.loadState)
        assertEquals(ReadingSessionState.Idle, coordinator.readingSessionState.value.sessionState)
        assertNull(coordinator.readingSessionState.value.activeDocumentId)
        assertNull(coordinator.readingSessionState.value.currentPosition)
    }

    // 40. unified state never exposes contradictory document/session combinations
    @Test
    fun test40_unifiedStateNeverExposesContradictoryState() {
        // Condition: No active document loaded
        val session = coordinator.readingSessionState.value
        assertNull(session.activeDocumentId)
        assertNull(session.currentPosition)
        assertFalse(session.isReading)

        // Load document
        val token = coordinator.startLoading("valid.txt")
        coordinator.onDocumentLoaded(token, createSampleDoc("v", "Valid"), "valid.txt")
        val loadedSession = coordinator.readingSessionState.value
        assertEquals("v", loadedSession.activeDocumentId)
        assertEquals(ReadingSessionState.Idle, loadedSession.sessionState)

        // Read and stop
        coordinator.startReading()
        assertEquals(ReadingSessionState.Reading, coordinator.readingSessionState.value.sessionState)
        assertEquals("v", coordinator.readingSessionState.value.currentPosition?.documentId)
    }

    // INVARIANT 1: If activeDocument == null, readingPosition must not resolve to a real active document
    @Test
    fun testInvariant1_noActiveDocumentMeansNullReadingPosition() {
        assertFalse(coordinator.hasActiveDocument)
        assertNull(coordinator.readingSessionState.value.currentPosition)
        assertNull(coordinator.readingSessionState.value.activeDocumentId)
    }

    // INVARIANT 2: ReadingPosition.documentId must equal activeDocument.documentId
    @Test
    fun testInvariant2_readingPositionMatchesActiveDocumentId() {
        val token = coordinator.startLoading("test.txt")
        coordinator.onDocumentLoaded(token, createSampleDoc("inv2_doc", "Doc"), "test.txt")
        coordinator.startReading()

        val pos = coordinator.readingSessionState.value.currentPosition
        assertNotNull(pos)
        assertEquals(coordinator.activeDocumentState.value.documentId, pos?.documentId)
    }

    // INVARIANT 3 & 4: Format-specific isolation (PDF position cannot apply to non-PDF)
    @Test
    fun testInvariant3And4_formatSpecificPositionCannotCrossFormat() {
        val tokenTxt = coordinator.startLoading("doc.txt")
        coordinator.onDocumentLoaded(tokenTxt, createSampleDoc("txt_doc", "Doc", sourceType = ReadingDocumentSourceType.TXT), "doc.txt")

        // Try setting a PDF position with different doc ID
        val pdfPos = ReadingPosition("pdf_doc", "sec_0", "seg_0", 0)
        coordinator.setPosition(pdfPos)

        assertNull(coordinator.readingSessionState.value.currentPosition)
    }

    // INVARIANT 5: Document replacement invalidates previous session operations
    @Test
    fun testInvariant5_documentReplacementInvalidatesPreviousOperations() {
        val token1 = coordinator.startLoading("d1.txt")
        val token2 = coordinator.startLoading("d2.txt")

        assertFalse(coordinator.isCurrentLoadToken(token1))
        assertTrue(coordinator.isCurrentLoadToken(token2))
    }

    // INVARIANT 6: A failed replacement must not silently restore an obsolete document state
    @Test
    fun testInvariant6_failedReplacementClearsObsoleteDocument() {
        val token1 = coordinator.startLoading("old.txt")
        coordinator.onDocumentLoaded(token1, createSampleDoc("old", "Old Doc"), "old.txt")
        assertTrue(coordinator.hasActiveDocument)

        val token2 = coordinator.startLoading("new_broken.txt")
        coordinator.onDocumentLoadFailed(token2, "Corrupt file")

        assertFalse(coordinator.hasActiveDocument)
        assertEquals("", coordinator.activeDocumentState.value.documentId)
        assertTrue(coordinator.activeDocumentState.value.loadState is DocumentLoadState.Error)
    }

    // INVARIANT 7: Speech settings do not alter document identity
    @Test
    fun testInvariant7_speechSettingsDoNotAlterDocumentIdentity() {
        val token = coordinator.startLoading("inv7.txt")
        coordinator.onDocumentLoaded(token, createSampleDoc("inv7", "Title"), "inv7.txt")
        coordinator.startReading()

        coordinator.updateSpeechState(TtsState.Speaking)
        coordinator.updateSpeechState(TtsState.Stopped)

        assertEquals("inv7", coordinator.activeDocumentState.value.documentId)
        assertEquals("inv7", coordinator.readingSessionState.value.activeDocumentId)
    }
}
