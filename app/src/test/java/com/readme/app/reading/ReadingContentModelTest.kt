package com.readme.app.reading

import com.readme.app.reading.content.TxtDocumentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingContentModelTest {

    @Test
    fun document_preservesMetadataExplicitly() {
        val metadata = ReadingDocumentMetadata(
            title = "Pride and Prejudice",
            author = "Jane Austen",
            sourceType = ReadingDocumentSourceType.OTHER
        )
        val document = ReadingDocument(
            id = "doc_custom",
            metadata = metadata,
            sections = emptyList()
        )

        assertEquals("doc_custom", document.id)
        assertEquals("Pride and Prejudice", document.title)
        assertEquals("Pride and Prejudice", document.metadata.title)
        assertEquals("Jane Austen", document.author)
        assertEquals("Jane Austen", document.metadata.author)
        assertEquals(ReadingDocumentSourceType.OTHER, document.sourceType)
        assertEquals(ReadingDocumentSourceType.OTHER, document.metadata.sourceType)
    }

    @Test
    fun document_convenienceConstructor_setsMetadataDefaults() {
        val doc = ReadingDocument(
            id = "doc_quick",
            title = "Quick Read"
        )

        assertEquals("doc_quick", doc.id)
        assertEquals("Quick Read", doc.title)
        assertEquals("Quick Read", doc.metadata.title)
        assertNull(doc.author)
        assertNull(doc.metadata.author)
        assertEquals(ReadingDocumentSourceType.OTHER, doc.sourceType)
        assertEquals(ReadingDocumentSourceType.OTHER, doc.metadata.sourceType)
    }

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
    fun sampleContent_isCorrectlyStructured_withSampleSourceType() {
        val sampleDoc = SampleContent.sampleDocument
        assertEquals("readme_sample_doc", sampleDoc.id)
        assertEquals("ReadMe Sample", sampleDoc.title)
        assertEquals("ReadMe Sample", sampleDoc.metadata.title)
        assertNull(sampleDoc.author)
        assertNull(sampleDoc.metadata.author)
        assertEquals(ReadingDocumentSourceType.SAMPLE, sampleDoc.sourceType)
        assertEquals(ReadingDocumentSourceType.SAMPLE, sampleDoc.metadata.sourceType)
        assertEquals(1, sampleDoc.sections.size)

        val segments = sampleDoc.allSegments()
        assertEquals(3, segments.size)
        assertEquals("Welcome to ReadMe.", segments[0].text)
        assertEquals("This is a test of your selected voice, speech speed, pitch, and volume.", segments[1].text)
        assertEquals("ReadMe is ready to read.", segments[2].text)
    }

    @Test
    fun txtParser_setsTxtSourceTypeAndNullAuthor() {
        val doc = TxtDocumentParser.parse(title = "War and Peace.txt", rawText = "It was a dark and stormy night.")
        assertEquals("War and Peace.txt", doc.title)
        assertEquals("War and Peace.txt", doc.metadata.title)
        assertNull(doc.author)
        assertNull(doc.metadata.author)
        assertEquals(ReadingDocumentSourceType.TXT, doc.sourceType)
        assertEquals(ReadingDocumentSourceType.TXT, doc.metadata.sourceType)
        assertEquals(1, doc.allSegments().size)
    }

    @Test
    fun readingEngine_handlesFutureSourceTypesWithoutModification() {
        val epubDoc = ReadingDocument(
            id = "epub_1",
            metadata = ReadingDocumentMetadata(
                title = "Moby Dick",
                author = "Herman Melville",
                sourceType = ReadingDocumentSourceType.EPUB
            ),
            sections = listOf(
                ReadingSection(
                    id = "epub_sec_1",
                    title = "Chapter 1",
                    segments = listOf(ReadingSegment(id = "seg_1", text = "Call me Ishmael."))
                )
            )
        )

        val pdfDoc = ReadingDocument(
            id = "pdf_1",
            metadata = ReadingDocumentMetadata(
                title = "Research Paper",
                author = "Dr. Aris",
                sourceType = ReadingDocumentSourceType.PDF
            ),
            sections = listOf(
                ReadingSection(
                    id = "pdf_sec_1",
                    title = "Abstract",
                    segments = listOf(ReadingSegment(id = "seg_2", text = "This paper explores audio reading."))
                )
            )
        )

        val engine = ReadingEngine()
        engine.loadDocument(epubDoc)
        assertEquals("Moby Dick", engine.currentDocument.title)
        assertEquals(ReadingDocumentSourceType.EPUB, engine.currentDocument.sourceType)
        assertEquals("Herman Melville", engine.currentDocument.author)
        assertEquals(1, engine.totalSegments())

        engine.loadDocument(pdfDoc)
        assertEquals("Research Paper", engine.currentDocument.title)
        assertEquals(ReadingDocumentSourceType.PDF, engine.currentDocument.sourceType)
        assertEquals("Dr. Aris", engine.currentDocument.author)
        assertEquals(1, engine.totalSegments())
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
