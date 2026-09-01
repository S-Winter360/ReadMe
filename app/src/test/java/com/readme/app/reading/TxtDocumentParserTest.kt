package com.readme.app.reading

import com.readme.app.reading.content.TxtDocumentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun parse_dialogueAndQuotes_segmentsNaturally() {
        val rawText = """"Are you coming?" he asked. "Yes! I'll be there soon." She smiled."""
        val doc = TxtDocumentParser.parse(title = "dialogue.txt", rawText = rawText)

        val segments = doc.allSegments()
        assertEquals(3, segments.size)
        assertEquals(""""Are you coming?" he asked.""", segments[0].text)
        assertEquals(""""Yes! I'll be there soon."""", segments[1].text)
        assertEquals("She smiled.", segments[2].text)
    }

    @Test
    fun parse_consecutiveDialogueLines_separatedAccurately() {
        val rawText = """"Stop!" "Why should I?" "Because it is dangerous.""""
        val doc = TxtDocumentParser.parse(title = "consecutive.txt", rawText = rawText)

        val segments = doc.allSegments()
        assertEquals(3, segments.size)
        assertEquals(""""Stop!"""", segments[0].text)
        assertEquals(""""Why should I?"""", segments[1].text)
        assertEquals(""""Because it is dangerous."""", segments[2].text)
    }

    @Test
    fun parse_commonAbbreviationsAndDecimals_doesNotSplitAwkwardly() {
        val rawText = "Dr. Smith arrived at 10.30 p.m. He spoke briefly with Mrs. Jones."
        val doc = TxtDocumentParser.parse(title = "abbreviations.txt", rawText = rawText)

        val segments = doc.allSegments()
        assertEquals(2, segments.size)
        assertEquals("Dr. Smith arrived at 10.30 p.m.", segments[0].text)
        assertEquals("He spoke briefly with Mrs. Jones.", segments[1].text)
    }

    @Test
    fun parse_titlesAndInitials_keptIntact() {
        val rawText = "Prof. Higgins met with J. K. Rowling and George W. Bush in St. Louis."
        val doc = TxtDocumentParser.parse(title = "initials.txt", rawText = rawText)

        val segments = doc.allSegments()
        assertEquals(1, segments.size)
        assertEquals("Prof. Higgins met with J. K. Rowling and George W. Bush in St. Louis.", segments[0].text)
    }

    @Test
    fun parse_ellipses_preservesSpeechFlow() {
        val rawText = """Wait... What was that? "I don't know..." whispered Tom."""
        val doc = TxtDocumentParser.parse(title = "ellipsis.txt", rawText = rawText)

        val segments = doc.allSegments()
        assertEquals(3, segments.size)
        assertEquals("Wait...", segments[0].text)
        assertEquals("What was that?", segments[1].text)
        assertEquals(""""I don't know..." whispered Tom.""", segments[2].text)
    }

    @Test
    fun parse_questionsAndExclamations_splitsCorrectly() {
        val rawText = "Are you sure?! Absolutely! Then let's proceed."
        val doc = TxtDocumentParser.parse(title = "exclamations.txt", rawText = rawText)

        val segments = doc.allSegments()
        assertEquals(3, segments.size)
        assertEquals("Are you sure?!", segments[0].text)
        assertEquals("Absolutely!", segments[1].text)
        assertEquals("Then let's proceed.", segments[2].text)
    }

    @Test
    fun parse_headingsWithoutTerminalPunctuation_preservesHeadings() {
        val rawText = """
            CHAPTER ONE
            
            The morning sun broke through the heavy mist.
        """.trimIndent()
        val doc = TxtDocumentParser.parse(title = "novel.txt", rawText = rawText)

        val segments = doc.allSegments()
        assertEquals(2, segments.size)
        assertEquals("CHAPTER ONE", segments[0].text)
        assertEquals("The morning sun broke through the heavy mist.", segments[1].text)
    }

    @Test
    fun parse_contractionsAndApostrophes_doesNotDisruptSegmentation() {
        val rawText = "Don't say you didn't know. We've been waiting for hours, hasn't he?"
        val doc = TxtDocumentParser.parse(title = "contractions.txt", rawText = rawText)

        val segments = doc.allSegments()
        assertEquals(2, segments.size)
        assertEquals("Don't say you didn't know.", segments[0].text)
        assertEquals("We've been waiting for hours, hasn't he?", segments[1].text)
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
    fun parse_deterministicSegmentIds_consistentAndNonEmpty() {
        val rawText = "Sentence one. Sentence two. Sentence three."
        val doc = TxtDocumentParser.parse(title = "book.txt", rawText = rawText, documentId = "doc_fixed")

        val segments = doc.allSegments()
        assertEquals(3, segments.size)
        assertEquals("doc_fixed_seg_0", segments[0].id)
        assertEquals("doc_fixed_seg_1", segments[1].id)
        assertEquals("doc_fixed_seg_2", segments[2].id)

        segments.forEach {
            assertFalse("Segment text should not be blank", it.text.isBlank())
        }
    }

    @Test
    fun parse_realisticNovelChapterProse_handlesAllNarrativeStructures() {
        val rawText = """
            CHAPTER THREE
            The Midnight Express
            
            Dr. Evelyn Vance checked her pocket watch at exactly 11.45 p.m. The train was running late, e.g. due to heavy snow along the northern ridge. She adjusted her woolen coat and glanced towards Prof. H. G. Wells, who was sitting quietly by the frosted window.
            
            "Do you think they'll arrive before midnight?" she asked softly.
            
            "I wouldn't count on it, Mrs. Vance," he replied with a faint smile. "However... we should remain patient."
            
            "Wait! Did you hear that?"
            "Yes! It came from car No. 4."
            "Let's investigate immediately!"
            
            The carriage door swung open. A chilly gust swept through the aisle, carrying with it the faint scent of coal smoke. It wasn't the first time strange sounds had been reported near Mt. Washington, but this felt different.
            
            "Don't worry," said Dr. Vance. "We've faced worse obstacles before."
        """.trimIndent()

        val doc = TxtDocumentParser.parse(title = "Chapter3.txt", rawText = rawText)

        assertEquals("Chapter3.txt", doc.title)
        val segments = doc.allSegments()

        assertEquals("CHAPTER THREE The Midnight Express", segments[0].text)
        assertEquals("Dr. Evelyn Vance checked her pocket watch at exactly 11.45 p.m.", segments[1].text)
        assertEquals("The train was running late, e.g. due to heavy snow along the northern ridge.", segments[2].text)
        assertEquals("She adjusted her woolen coat and glanced towards Prof. H. G. Wells, who was sitting quietly by the frosted window.", segments[3].text)
        assertEquals(""""Do you think they'll arrive before midnight?" she asked softly.""", segments[4].text)
        assertEquals(""""I wouldn't count on it, Mrs. Vance," he replied with a faint smile.""", segments[5].text)
        assertEquals(""""However... we should remain patient."""", segments[6].text)
        assertEquals(""""Wait! Did you hear that?"""", segments[7].text)
        assertEquals(""""Yes! It came from car No. 4."""", segments[8].text)
        assertEquals(""""Let's investigate immediately!"""", segments[9].text)
        assertEquals("The carriage door swung open.", segments[10].text)
        assertEquals("A chilly gust swept through the aisle, carrying with it the faint scent of coal smoke.", segments[11].text)
        assertEquals("It wasn't the first time strange sounds had been reported near Mt. Washington, but this felt different.", segments[12].text)
        assertEquals(""""Don't worry," said Dr. Vance.""", segments[13].text)
        assertEquals(""""We've faced worse obstacles before."""", segments[14].text)
        assertEquals(15, segments.size)
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

