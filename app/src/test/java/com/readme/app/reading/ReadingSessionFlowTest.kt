package com.readme.app.reading

import com.readme.app.reading.content.ReadingContentSource
import com.readme.app.reading.content.SampleContentSource
import com.readme.app.reading.content.TxtDocumentParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the core Phase 5B ReadingEngine lifecycle, document switching,
 * continuous progression, empty handling, and session state transitions.
 */
class ReadingSessionFlowTest {

    @Test
    fun sampleFallback_loadsBuiltInDocumentWhenNoFileSelected() = runBlocking {
        val sampleSource: ReadingContentSource = SampleContentSource()
        val doc = sampleSource.load()
        val engine = ReadingEngine()
        engine.loadDocument(doc)

        assertEquals("readme_sample_doc", engine.currentDocument.id)
        assertEquals(3, engine.totalSegments())

        val first = engine.startSession()
        assertNotNull(first)
        assertEquals("Welcome to ReadMe.", first?.text)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)
    }

    @Test
    fun activeDocumentLoading_readsTxtDocumentSegmentsCorrectly() {
        val novelText = """
            Chapter 1: The Departure.
            The wind was howling across the moors. No one dared to venture outside.
            
            Chapter 2: The Return.
            By sunrise, the storm had cleared.
        """.trimIndent()

        val doc = TxtDocumentParser.parse("MyNovel.txt", novelText)
        val engine = ReadingEngine()
        engine.loadDocument(doc)

        assertEquals("MyNovel.txt", engine.currentDocument.title)
        assertEquals(5, engine.totalSegments())

        // Start session: Segment 0
        val seg0 = engine.startSession()
        assertNotNull(seg0)
        assertEquals("Chapter 1: The Departure.", seg0?.text)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)

        // Advance: Segment 1
        val seg1 = engine.advance()
        assertEquals("The wind was howling across the moors.", seg1?.text)

        // Advance: Segment 2
        val seg2 = engine.advance()
        assertEquals("No one dared to venture outside.", seg2?.text)

        // Advance: Segment 3
        val seg3 = engine.advance()
        assertEquals("Chapter 2: The Return.", seg3?.text)

        // Advance: Segment 4
        val seg4 = engine.advance()
        assertEquals("By sunrise, the storm had cleared.", seg4?.text)

        // Advance past end -> Completed
        val end = engine.advance()
        assertNull(end)
        assertEquals(ReadingSessionState.Completed, engine.readingState.value)
    }

    @Test
    fun documentReplacement_bookASwitchedToBookB_resetsAndReadsOnlyBookB() {
        val bookAText = "Book A Sentence 1. Book A Sentence 2."
        val bookBText = "Book B First Line. Book B Second Line."

        val docA = TxtDocumentParser.parse("BookA.txt", bookAText)
        val docB = TxtDocumentParser.parse("BookB.txt", bookBText)

        val engine = ReadingEngine()
        engine.loadDocument(docA)

        // Start reading Book A
        val aSeg0 = engine.startSession()
        assertEquals("Book A Sentence 1.", aSeg0?.text)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)

        // Switch to Book B while reading Book A
        engine.loadDocument(docB)
        assertEquals(ReadingSessionState.Idle, engine.readingState.value)
        assertNull(engine.currentSegment.value)
        assertEquals("BookB.txt", engine.currentDocument.title)

        // Start reading Book B
        val bSeg0 = engine.startSession()
        assertNotNull(bSeg0)
        assertEquals("Book B First Line.", bSeg0?.text)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)

        val bSeg1 = engine.advance()
        assertEquals("Book B Second Line.", bSeg1?.text)

        val bEnd = engine.advance()
        assertNull(bEnd)
        assertEquals(ReadingSessionState.Completed, engine.readingState.value)
    }

    @Test
    fun stopAndRestart_startsFromSegmentZero() {
        val rawText = "Sentence 1. Sentence 2. Sentence 3."
        val doc = TxtDocumentParser.parse("TestDoc.txt", rawText)
        val engine = ReadingEngine(doc)

        // Start reading
        engine.startSession()
        engine.advance() // at segment 1
        assertEquals("Sentence 2.", engine.currentSegment.value?.text)

        // User stops reading
        engine.stop()
        assertEquals(ReadingSessionState.Stopped, engine.readingState.value)
        assertNull(engine.currentSegment.value)

        // User presses Start Reading again
        val restarted = engine.startSession()
        assertNotNull(restarted)
        assertEquals("Sentence 1.", restarted?.text)
        assertEquals(0, engine.currentSegmentIndex.value)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)
    }

    @Test
    fun documentCompletionAndRestart_startsFromSegmentZero() {
        val rawText = "Only One Sentence."
        val doc = TxtDocumentParser.parse("Short.txt", rawText)
        val engine = ReadingEngine(doc)

        val seg = engine.startSession()
        assertEquals("Only One Sentence.", seg?.text)

        val done = engine.advance()
        assertNull(done)
        assertEquals(ReadingSessionState.Completed, engine.readingState.value)

        // Restarting completed session
        val restart = engine.startSession()
        assertNotNull(restart)
        assertEquals("Only One Sentence.", restart?.text)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)
    }

    @Test
    fun emptyDocument_doesNotProduceSpeechSegments() {
        val emptyDoc = TxtDocumentParser.parse("Empty.txt", "")
        val engine = ReadingEngine(emptyDoc)

        assertEquals(0, engine.totalSegments())
        val first = engine.startSession()
        assertNull(first)
        assertEquals(ReadingSessionState.Completed, engine.readingState.value)
    }

    @Test
    fun blankOnlyDocument_doesNotProduceSpeechSegments() {
        val blankDoc = TxtDocumentParser.parse("Blank.txt", "   \n\n\t   \n")
        val engine = ReadingEngine(blankDoc)

        assertEquals(0, engine.totalSegments())
        val first = engine.startSession()
        assertNull(first)
        assertEquals(ReadingSessionState.Completed, engine.readingState.value)
    }
}
