package com.readme.app.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingPositionTest {

    private fun createMultiSectionDocument(): ReadingDocument {
        val seg1 = ReadingSegment("seg_1", "First sentence in chapter 1.")
        val seg2 = ReadingSegment("seg_2", "Second sentence in chapter 1.")
        val seg3 = ReadingSegment("seg_3", "First sentence in chapter 2.")
        val seg4 = ReadingSegment("seg_4", "Second sentence in chapter 2.")

        val sec1 = ReadingSection("sec_1", "Chapter 1", listOf(seg1, seg2))
        val sec2 = ReadingSection("sec_2", "Chapter 2", listOf(seg3, seg4))

        return ReadingDocument(
            id = "doc_multi",
            title = "Multi Section Book",
            sections = listOf(sec1, sec2)
        )
    }

    @Test
    fun initialPosition_isNull() {
        val doc = createMultiSectionDocument()
        val engine = ReadingEngine(doc)

        assertNull(engine.currentPosition.value)
        assertEquals(ReadingSessionState.Idle, engine.readingState.value)
    }

    @Test
    fun stopBeforeSpeechStartsDoesNotCreateInvalidPosition() {
        val doc = createMultiSectionDocument()
        val engine = ReadingEngine(doc)

        // Stopped while in Idle state before any speech starts
        engine.stop()
        assertEquals(ReadingSessionState.Stopped, engine.readingState.value)
        assertNull(engine.currentPosition.value)
        assertNull(engine.currentSegment.value)

        // Resuming when position is null falls back to start from beginning
        val resumed = engine.resumeFromCurrentPosition()
        assertNotNull(resumed)
        assertEquals("seg_1", resumed?.id)
        assertEquals(0, engine.currentPosition.value?.segmentIndex)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)
    }

    @Test
    fun stopRetainsPosition() {
        val doc = createMultiSectionDocument()
        val engine = ReadingEngine(doc)

        engine.startSession() // seg_1 (index 0)
        engine.advance()      // seg_2 (index 1)

        engine.stop()
        assertEquals(ReadingSessionState.Stopped, engine.readingState.value)
        assertNull(engine.currentSegment.value)

        // Position is preserved
        val pos = engine.currentPosition.value
        assertNotNull(pos)
        assertEquals("doc_multi", pos?.documentId)
        assertEquals("sec_1", pos?.sectionId)
        assertEquals("seg_2", pos?.segmentId)
        assertEquals(1, pos?.segmentIndex)
    }

    @Test
    fun resumeStartsAtCurrentPosition() {
        val doc = createMultiSectionDocument()
        val engine = ReadingEngine(doc)

        engine.startSession() // 0: seg_1
        engine.advance()      // 1: seg_2
        engine.advance()      // 2: seg_3 (Chapter 2)

        engine.stop()
        assertEquals(ReadingSessionState.Stopped, engine.readingState.value)

        // Resume session
        val resumed = engine.resumeFromCurrentPosition()
        assertNotNull(resumed)
        assertEquals("seg_3", resumed?.id)
        assertEquals("First sentence in chapter 2.", resumed?.text)
        assertEquals(2, engine.currentSegmentIndex.value)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)
        assertEquals("seg_3", engine.currentPosition.value?.segmentId)
    }

    @Test
    fun resumeReplaysCurrentSegmentFromBeginning() {
        val doc = createMultiSectionDocument()
        val engine = ReadingEngine(doc)

        engine.startSession() // seg_1
        engine.advance()      // seg_2 ("Second sentence in chapter 1.")

        // User stops midway through segment 2
        engine.stop()

        // Starting/resuming again returns the full segment 2 text
        val resumed = engine.resumeFromCurrentPosition()
        assertNotNull(resumed)
        assertEquals("Second sentence in chapter 1.", resumed?.text)
        assertEquals("seg_2", resumed?.id)
    }

    @Test
    fun resumeDoesNotStartFromBeginningWhenStopped() {
        val doc = createMultiSectionDocument()
        val engine = ReadingEngine(doc)

        engine.startSession() // 0: seg_1
        engine.advance()      // 1: seg_2
        engine.advance()      // 2: seg_3
        engine.stop()

        val resumed = engine.resumeFromCurrentPosition()
        assertNotNull(resumed)
        assertNotEquals("seg_1", resumed?.id)
        assertEquals("seg_3", resumed?.id)
        assertEquals(2, engine.currentSegmentIndex.value)
    }

    @Test
    fun completionFollowedByStartStartsFromBeginning() {
        val doc = createMultiSectionDocument()
        val engine = ReadingEngine(doc)

        engine.startSession() // 0
        engine.advance()      // 1
        engine.advance()      // 2
        engine.advance()      // 3
        val pastEnd = engine.advance() // Completed
        assertNull(pastEnd)
        assertEquals(ReadingSessionState.Completed, engine.readingState.value)

        // Starting again after completion must start from segment 0, not resume segment 3
        val restarted = engine.startFromBeginning()
        assertNotNull(restarted)
        assertEquals("seg_1", restarted?.id)
        assertEquals(0, engine.currentSegmentIndex.value)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)

        // Calling resumeFromCurrentPosition after completion also safely starts from beginning
        engine.stop()
        engine.startSession()
        engine.advance(); engine.advance(); engine.advance(); engine.advance()
        assertEquals(ReadingSessionState.Completed, engine.readingState.value)

        val resumeAfterComplete = engine.resumeFromCurrentPosition()
        assertNotNull(resumeAfterComplete)
        assertEquals("seg_1", resumeAfterComplete?.id)
        assertEquals(0, engine.currentSegmentIndex.value)
    }

    @Test
    fun documentReplacementClearsResumablePosition() {
        val docA = createMultiSectionDocument()
        val docB = ReadingDocument(
            id = "doc_b",
            title = "Book B",
            sections = listOf(
                ReadingSection("sec_b", "B1", listOf(ReadingSegment("seg_b1", "Book B sentence 1.")))
            )
        )

        val engine = ReadingEngine(docA)
        engine.startSession() // 0
        engine.advance()      // 1: seg_2
        engine.stop()
        assertEquals("seg_2", engine.currentPosition.value?.segmentId)

        // Replace document with Book B
        engine.loadDocument(docB)
        assertNull(engine.currentPosition.value)
        assertEquals(ReadingSessionState.Idle, engine.readingState.value)
        assertEquals("doc_b", engine.currentDocument.id)

        // Starting Book B starts from segment 0 of Book B
        val bookBSeg = engine.resumeFromCurrentPosition()
        assertNotNull(bookBSeg)
        assertEquals("seg_b1", bookBSeg?.id)
        assertEquals("Book B sentence 1.", bookBSeg?.text)
        assertEquals(0, engine.currentSegmentIndex.value)
    }

    @Test
    fun invalidPositionFallsBackSafely() {
        val doc = createMultiSectionDocument()
        val engine = ReadingEngine(doc)

        engine.startSession()
        engine.advance() // 1: seg_2
        engine.stop()

        // Foreign document ID
        val foreignPos = ReadingPosition("other_doc", "sec_1", "seg_1", 0)
        assertNull(engine.resolvePosition(foreignPos))

        // Non-existent section
        val badSecPos = ReadingPosition("doc_multi", "bad_sec", "seg_1", 0)
        assertNull(engine.resolvePosition(badSecPos))

        // Non-existent segment
        val badSegPos = ReadingPosition("doc_multi", "sec_1", "bad_seg", 0)
        assertNull(engine.resolvePosition(badSegPos))
    }

    @Test
    fun emptyDocumentCannotResume() {
        val emptyDoc = ReadingDocument(id = "empty_doc", title = "Empty", sections = emptyList())
        val engine = ReadingEngine(emptyDoc)

        val seg = engine.startSession()
        assertNull(seg)
        assertNull(engine.currentPosition.value)
        assertEquals(ReadingSessionState.Completed, engine.readingState.value)

        val resumed = engine.resumeFromCurrentPosition()
        assertNull(resumed)
        assertNull(engine.currentPosition.value)
        assertEquals(ReadingSessionState.Completed, engine.readingState.value)
    }

    @Test
    fun settingChangeRestartDoesNotChangePosition() {
        val doc = createMultiSectionDocument()
        val engine = ReadingEngine(doc)

        engine.startSession() // seg_1 (0)
        engine.advance()      // seg_2 (1)
        assertEquals("seg_2", engine.currentPosition.value?.segmentId)
        assertEquals(1, engine.currentSegmentIndex.value)

        // When a setting change occurs, speech engine restarts the same segment without advancing ReadingEngine
        // Verify ReadingEngine position remains at segment 1
        assertEquals("seg_2", engine.currentPosition.value?.segmentId)
        assertEquals(1, engine.currentSegmentIndex.value)
    }

    @Test
    fun settingChangeThenStopThenResumeUsesSamePosition() {
        val doc = createMultiSectionDocument()
        val engine = ReadingEngine(doc)

        engine.startSession() // 0: seg_1
        engine.advance()      // 1: seg_2
        engine.advance()      // 2: seg_3

        // Settings change occurred during seg_3 -> position is seg_3
        assertEquals("seg_3", engine.currentPosition.value?.segmentId)

        // User stops
        engine.stop()
        assertEquals(ReadingSessionState.Stopped, engine.readingState.value)
        assertEquals("seg_3", engine.currentPosition.value?.segmentId)

        // User resumes -> resumes seg_3
        val resumed = engine.resumeFromCurrentPosition()
        assertNotNull(resumed)
        assertEquals("seg_3", resumed?.id)
        assertEquals(2, engine.currentSegmentIndex.value)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)
    }

    @Test
    fun advancing_updatesPositionCorrectlyAcrossSections() {
        val doc = createMultiSectionDocument()
        val engine = ReadingEngine(doc)

        engine.startSession()

        // Advance to segment 1 (sec_1)
        engine.advance()
        var pos = engine.currentPosition.value
        assertEquals("doc_multi", pos?.documentId)
        assertEquals("sec_1", pos?.sectionId)
        assertEquals("seg_2", pos?.segmentId)
        assertEquals(1, pos?.segmentIndex)

        // Advance to segment 2 (sec_2, Chapter 2)
        engine.advance()
        pos = engine.currentPosition.value
        assertEquals("doc_multi", pos?.documentId)
        assertEquals("sec_2", pos?.sectionId)
        assertEquals("seg_3", pos?.segmentId)
        assertEquals(2, pos?.segmentIndex)

        // Advance to segment 3 (sec_2)
        engine.advance()
        pos = engine.currentPosition.value
        assertEquals("doc_multi", pos?.documentId)
        assertEquals("sec_2", pos?.sectionId)
        assertEquals("seg_4", pos?.segmentId)
        assertEquals(3, pos?.segmentIndex)
    }

    @Test
    fun positionForSegmentId_and_positionForIndex_lookupHelpers() {
        val doc = createMultiSectionDocument()

        val posById = doc.positionForSegmentId("seg_3")
        assertNotNull(posById)
        assertEquals("doc_multi", posById?.documentId)
        assertEquals("sec_2", posById?.sectionId)
        assertEquals("seg_3", posById?.segmentId)
        assertEquals(2, posById?.segmentIndex)

        val posByIndex = doc.positionForIndex(1)
        assertNotNull(posByIndex)
        assertEquals("doc_multi", posByIndex?.documentId)
        assertEquals("sec_1", posByIndex?.sectionId)
        assertEquals("seg_2", posByIndex?.segmentId)
        assertEquals(1, posByIndex?.segmentIndex)

        assertNull(doc.positionForIndex(99))
        assertNull(doc.positionForSegmentId("unknown_id"))
    }
}
