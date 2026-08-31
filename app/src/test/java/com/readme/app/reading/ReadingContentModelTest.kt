package com.readme.app.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingContentModelTest {

    @Test
    fun document_preservesOrderedSectionsAndSegments() {
        val segment1 = ReadingSegment(id = "s1", text = "First sentence.")
        val segment2 = ReadingSegment(id = "s2", text = "Second sentence.")
        val segment3 = ReadingSegment(id = "s3", text = "Third sentence.")

        val section1 = ReadingSection(
            id = "sec1",
            title = "Section 1",
            segments = listOf(segment1, segment2)
        )
        val section2 = ReadingSection(
            id = "sec2",
            title = "Section 2",
            segments = listOf(segment3)
        )

        val document = ReadingDocument(
            id = "doc1",
            title = "Test Document",
            sections = listOf(section1, section2)
        )

        assertEquals("doc1", document.id)
        assertEquals("Test Document", document.title)
        assertEquals(2, document.sections.size)
        assertEquals(section1, document.sections[0])
        assertEquals(section2, document.sections[1])

        val allSegments = document.allSegments()
        assertEquals(3, allSegments.size)
        assertEquals("First sentence.", allSegments[0].text)
        assertEquals("Second sentence.", allSegments[1].text)
        assertEquals("Third sentence.", allSegments[2].text)
    }

    @Test
    fun sampleContent_isCorrectlyStructured() {
        val sampleDoc = SampleContent.sampleDocument
        assertEquals("readme_sample_doc", sampleDoc.id)
        assertEquals("ReadMe Sample", sampleDoc.title)
        assertEquals(1, sampleDoc.sections.size)

        val segments = sampleDoc.allSegments()
        assertEquals(3, segments.size)
        assertEquals("Welcome to ReadMe.", segments[0].text)
        assertEquals("This is a test of your selected voice, speech speed, pitch, and volume.", segments[1].text)
        assertEquals("ReadMe is ready to read.", segments[2].text)
    }

    @Test
    fun readingEngine_exposesSegmentsInOrder() {
        val engine = ReadingEngine(SampleContent.sampleDocument)
        assertEquals(3, engine.totalSegments())

        val first = engine.getSegment(0)
        assertNotNull(first)
        assertEquals("Welcome to ReadMe.", first?.text)

        val second = engine.getSegment(1)
        assertNotNull(second)
        assertEquals("This is a test of your selected voice, speech speed, pitch, and volume.", second?.text)

        val third = engine.getSegment(2)
        assertNotNull(third)
        assertEquals("ReadMe is ready to read.", third?.text)

        val outOfBounds = engine.getSegment(3)
        assertNull(outOfBounds)
    }
}
