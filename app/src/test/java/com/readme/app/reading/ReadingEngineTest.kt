package com.readme.app.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingEngineTest {

    @Test
    fun startSession_beginsAtFirstSegmentAndSetsReadingState() {
        val engine = ReadingEngine(SampleContent.sampleDocument)
        assertEquals(ReadingSessionState.Idle, engine.readingState.value)

        val firstSegment = engine.startSession()
        assertNotNull(firstSegment)
        assertEquals("Welcome to ReadMe.", firstSegment?.text)
        assertEquals(0, engine.currentSegmentIndex.value)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)
        assertEquals(firstSegment, engine.currentSegment.value)
    }

    @Test
    fun continuousProgression_advancesThroughAllSegmentsUntilCompleted() {
        val engine = ReadingEngine(SampleContent.sampleDocument)
        
        // Start: Segment 0
        val seg0 = engine.startSession()
        assertEquals("Welcome to ReadMe.", seg0?.text)
        assertEquals(0, engine.currentSegmentIndex.value)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)

        // Advance: Segment 1
        val seg1 = engine.advance()
        assertEquals("This is a test of your selected voice, speech speed, pitch, and volume.", seg1?.text)
        assertEquals(1, engine.currentSegmentIndex.value)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)

        // Advance: Segment 2
        val seg2 = engine.advance()
        assertEquals("ReadMe is ready to read.", seg2?.text)
        assertEquals(2, engine.currentSegmentIndex.value)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)

        // Advance past end: Completed
        val finished = engine.advance()
        assertNull(finished)
        assertEquals(ReadingSessionState.Completed, engine.readingState.value)
        assertNull(engine.currentSegment.value)
    }

    @Test
    fun startAfterCompletion_restartsAtSegmentZero() {
        val engine = ReadingEngine(SampleContent.sampleDocument)
        engine.startSession()
        engine.advance() // 1
        engine.advance() // 2
        engine.advance() // Completed
        assertEquals(ReadingSessionState.Completed, engine.readingState.value)

        val restartedSeg = engine.startSession()
        assertNotNull(restartedSeg)
        assertEquals("Welcome to ReadMe.", restartedSeg?.text)
        assertEquals(0, engine.currentSegmentIndex.value)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)
    }

    @Test
    fun stop_haltsReadingSessionAndPreventsFurtherAdvance() {
        val engine = ReadingEngine(SampleContent.sampleDocument)
        engine.startSession()
        engine.advance() // at segment 1
        assertEquals(1, engine.currentSegmentIndex.value)

        engine.stop()
        assertEquals(ReadingSessionState.Stopped, engine.readingState.value)
        assertNull(engine.currentSegment.value)

        val attemptAdvance = engine.advance()
        assertNull(attemptAdvance)
        assertEquals(ReadingSessionState.Stopped, engine.readingState.value)
    }

    @Test
    fun emptyDocument_transitionsDirectlyToCompleted() {
        val emptyDoc = ReadingDocument(id = "empty", title = "Empty", sections = emptyList())
        val engine = ReadingEngine(emptyDoc)

        val segment = engine.startSession()
        assertNull(segment)
        assertEquals(ReadingSessionState.Completed, engine.readingState.value)
    }

    @Test
    fun blankSegments_areSkippedAutomatically() {
        val docWithBlanks = ReadingDocument(
            id = "doc_blanks",
            title = "Blanks",
            sections = listOf(
                ReadingSection(
                    id = "sec",
                    segments = listOf(
                        ReadingSegment("s1", "  "),
                        ReadingSegment("s2", "Valid sentence one."),
                        ReadingSegment("s3", ""),
                        ReadingSegment("s4", "Valid sentence two."),
                        ReadingSegment("s5", "   ")
                    )
                )
            )
        )
        val engine = ReadingEngine(docWithBlanks)

        val first = engine.startSession()
        assertNotNull(first)
        assertEquals("Valid sentence one.", first?.text)
        assertEquals(1, engine.currentSegmentIndex.value)

        val second = engine.advance()
        assertNotNull(second)
        assertEquals("Valid sentence two.", second?.text)
        assertEquals(3, engine.currentSegmentIndex.value)

        val end = engine.advance()
        assertNull(end)
        assertEquals(ReadingSessionState.Completed, engine.readingState.value)
    }

    @Test
    fun setError_setsErrorStateAndClearsCurrentSegment() {
        val engine = ReadingEngine(SampleContent.sampleDocument)
        engine.startSession()
        engine.setError()

        assertEquals(ReadingSessionState.Error, engine.readingState.value)
        assertNull(engine.currentSegment.value)
    }
}
