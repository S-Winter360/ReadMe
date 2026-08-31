package com.readme.app.reading

import com.readme.app.reading.content.TxtDocumentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtDocumentParserTest {

    @Test
    fun parse_normalContent_createsOrderedSegments() {
        val rawText = "First sentence. Second sentence! Third sentence?"
        val doc = TxtDocumentParser.parse(title = "test.txt", rawText = rawText)

        assertEquals("test.txt", doc.title)
        assertEquals(1, doc.sections.size)

        val segments = doc.allSegments()
        assertEquals(3, segments.size)
        assertEquals("First sentence.", segments[0].text)
        assertEquals("Second sentence!", segments[1].text)
        assertEquals("Third sentence?", segments[2].text)
    }

    @Test
    fun parse_multipleParagraphsWithExtraWhitespace_normalizesAndPreservesOrder() {
        val rawText = """
            Paragraph 1 sentence 1.   Paragraph 1 sentence 2.
            
            
            Paragraph 2 sentence 1!
            
            Paragraph 3 sentence 1? Paragraph 3 sentence 2.
        """.trimIndent()

        val doc = TxtDocumentParser.parse(title = "Chapter 1", rawText = rawText)

        assertEquals("Chapter 1", doc.title)
        val segments = doc.allSegments()
        assertEquals(5, segments.size)
        assertEquals("Paragraph 1 sentence 1.", segments[0].text)
        assertEquals("Paragraph 1 sentence 2.", segments[1].text)
        assertEquals("Paragraph 2 sentence 1!", segments[2].text)
        assertEquals("Paragraph 3 sentence 1?", segments[3].text)
        assertEquals("Paragraph 3 sentence 2.", segments[4].text)
    }

    @Test
    fun parse_emptyOrBlankText_returnsEmptyDocumentSafely() {
        val docEmpty = TxtDocumentParser.parse(title = "empty.txt", rawText = "")
        assertEquals("empty.txt", docEmpty.title)
        assertTrue(docEmpty.allSegments().isEmpty())

        val docBlank = TxtDocumentParser.parse(title = "blank.txt", rawText = "   \n\n\t  \n ")
        assertEquals("blank.txt", docBlank.title)
        assertTrue(docBlank.allSegments().isEmpty())
    }

    @Test
    fun parse_emptyTitle_fallsBackToUntitledDocument() {
        val doc = TxtDocumentParser.parse(title = "", rawText = "Some text.")
        assertEquals("Untitled Document", doc.title)
        assertEquals(1, doc.allSegments().size)
    }

    @Test
    fun parse_windowsAndMacLineEndings_normalizedCorrectly() {
        val rawText = "Line one.\r\nLine two.\rLine three.\n"
        val doc = TxtDocumentParser.parse(title = "endings.txt", rawText = rawText)

        val segments = doc.allSegments()
        assertEquals(3, segments.size)
        assertEquals("Line one.", segments[0].text)
        assertEquals("Line two.", segments[1].text)
        assertEquals("Line three.", segments[2].text)
    }

    @Test
    fun parse_textWithoutTerminalPunctuation_treatedAsValidSegment() {
        val rawText = "Chapter One\nThe Beginning"
        val doc = TxtDocumentParser.parse(title = "novel.txt", rawText = rawText)

        val segments = doc.allSegments()
        assertEquals(1, segments.size)
        assertEquals("Chapter One The Beginning", segments[0].text)
    }

    @Test
    fun parse_integrationWithReadingEngine_progressesCorrectly() {
        val rawText = "Sentence A. Sentence B."
        val doc = TxtDocumentParser.parse(title = "Integration Test", rawText = rawText)

        val engine = ReadingEngine()
        engine.loadDocument(doc)

        assertEquals(2, engine.totalSegments())
        assertEquals(ReadingSessionState.Idle, engine.readingState.value)

        val first = engine.startSession()
        assertNotNull(first)
        assertEquals("Sentence A.", first?.text)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)

        val second = engine.advance()
        assertNotNull(second)
        assertEquals("Sentence B.", second?.text)

        val end = engine.advance()
        assertEquals(null, end)
        assertEquals(ReadingSessionState.Completed, engine.readingState.value)
    }
}
