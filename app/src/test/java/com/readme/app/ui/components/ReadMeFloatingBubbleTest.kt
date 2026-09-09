package com.readme.app.ui.components

import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState
import com.readme.app.reading.DocumentLoadState
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingSessionState
import com.readme.app.speech.TtsState
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadMeFloatingBubbleTest {

    private fun createDocState(
        hasDoc: Boolean = true,
        loadState: DocumentLoadState = DocumentLoadState.Loaded,
        sourceType: ReadingDocumentSourceType = ReadingDocumentSourceType.TXT
    ) = ActiveDocumentState(
        documentId = if (hasDoc) "doc1" else "",
        title = if (hasDoc) "Title" else "",
        author = null,
        displayName = if (hasDoc) "title.txt" else "",
        sourceType = sourceType,
        loadState = loadState
    )

    private fun createSessionState(
        sessionState: ReadingSessionState,
        activeDocId: String? = "doc1",
        ttsState: TtsState = TtsState.Stopped,
        errorMessage: String? = null
    ) = ActiveReadingSessionState(
        activeDocumentId = activeDocId,
        sessionState = sessionState,
        speechState = ttsState,
        currentPosition = null,
        errorMessage = errorMessage
    )

    @Test
    fun testNoDocument_isHidden() {
        val docState = createDocState(hasDoc = false, loadState = DocumentLoadState.NoDocument)
        val sessionState = createSessionState(ReadingSessionState.Idle, null)
        
        val bubbleState = getBubbleState(sessionState, docState)
        assertEquals(BubbleState.Hidden, bubbleState)
    }

    @Test
    fun testLoadingDocument_isHidden() {
        val docState = createDocState(loadState = DocumentLoadState.Loading("loading.txt"))
        val sessionState = createSessionState(ReadingSessionState.Idle, null)
        
        val bubbleState = getBubbleState(sessionState, docState)
        assertEquals(BubbleState.Hidden, bubbleState)
    }

    @Test
    fun testLoadedIdleDocument_isIdle() {
        val docState = createDocState()
        val sessionState = createSessionState(ReadingSessionState.Idle)
        
        val bubbleState = getBubbleState(sessionState, docState)
        assertEquals(BubbleState.Idle, bubbleState)
    }

    @Test
    fun testActiveReading_isReading() {
        val docState = createDocState()
        val sessionState = createSessionState(ReadingSessionState.Reading, ttsState = TtsState.Speaking)
        
        val bubbleState = getBubbleState(sessionState, docState)
        assertEquals(BubbleState.Reading, bubbleState)
    }

    @Test
    fun testStoppedSession_isStopped() {
        val docState = createDocState()
        val sessionState = createSessionState(ReadingSessionState.Stopped)
        
        val bubbleState = getBubbleState(sessionState, docState)
        assertEquals(BubbleState.Stopped, bubbleState)
    }

    @Test
    fun testCompletedSession_isCompleted() {
        val docState = createDocState()
        val sessionState = createSessionState(ReadingSessionState.Completed)
        
        val bubbleState = getBubbleState(sessionState, docState)
        assertEquals(BubbleState.Completed, bubbleState)
    }

    @Test
    fun testSessionError_isError() {
        val docState = createDocState()
        val sessionState = createSessionState(ReadingSessionState.Error, errorMessage = "Failed to read")
        
        val bubbleState = getBubbleState(sessionState, docState)
        assertEquals(BubbleState.Error, bubbleState)
    }

    @Test
    fun testTxtProducesSameBubbleModelAsEpub() {
        val txtState = createDocState(sourceType = ReadingDocumentSourceType.TXT)
        val epubState = createDocState(sourceType = ReadingDocumentSourceType.EPUB)
        
        val sessionState = createSessionState(ReadingSessionState.Reading)
        
        assertEquals(BubbleState.Reading, getBubbleState(sessionState, txtState))
        assertEquals(BubbleState.Reading, getBubbleState(sessionState, epubState))
    }

    @Test
    fun testEpubProducesSameBubbleModelAsPdf() {
        val epubState = createDocState(sourceType = ReadingDocumentSourceType.EPUB)
        val pdfState = createDocState(sourceType = ReadingDocumentSourceType.PDF)
        
        val sessionState = createSessionState(ReadingSessionState.Stopped)
        
        assertEquals(BubbleState.Stopped, getBubbleState(sessionState, epubState))
        assertEquals(BubbleState.Stopped, getBubbleState(sessionState, pdfState))
    }
}
