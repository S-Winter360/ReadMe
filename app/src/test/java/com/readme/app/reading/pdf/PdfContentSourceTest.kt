package com.readme.app.reading.pdf

import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingEngine
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.ReadingSessionState
import com.readme.app.reading.content.pdf.PdfContentSource
import com.readme.app.reading.content.pdf.PdfNotSupportedException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class PdfContentSourceTest {

    @Test
    fun pdfContentSource_requiresContext_onLoad() {
        val pdfSource = PdfContentSource(customDisplayName = "test_document.pdf")

        runBlocking {
            try {
                pdfSource.load()
                fail("Expected IOException")
            } catch (e: java.io.IOException) {
                assertEquals("Context is required for PDF loading", e.message)
            }
        }
    }

    @Test
    fun pdfContentSource_providesPdfSourceTypeMetadata() {
        val pdfSource = PdfContentSource(customDisplayName = "manual.pdf")
        val metadata = pdfSource.createMetadata("manual.pdf")

        assertEquals("manual.pdf", metadata.title)
        assertNull(metadata.author)
        assertEquals(ReadingDocumentSourceType.PDF, metadata.sourceType)
    }

    @Test
    fun readingEngine_withEmptyDocumentForPdf_doesNotInitiateReading() {
        val engine = ReadingEngine()
        val emptyPdfDoc = ReadingDocument(id = "", title = "document.pdf", sections = emptyList())
        engine.loadDocument(emptyPdfDoc)

        assertEquals(0, engine.totalSegments())
        assertEquals(ReadingSessionState.Idle, engine.readingState.value)

        val segment = engine.startSession()
        assertNull(segment)
        assertEquals(ReadingSessionState.Completed, engine.readingState.value)
    }

    @Test
    fun documentReplacement_fromTxtToPdf_resetsReadingEngineCleanly() {
        val engine = ReadingEngine()
        val txtDoc = ReadingDocument(
            id = "txt_1",
            title = "Notes.txt",
            sections = listOf(
                ReadingSection(
                    id = "s1",
                    title = "Sec 1",
                    segments = listOf(
                        ReadingSegment("seg1", "First sentence."),
                        ReadingSegment("seg2", "Second sentence.")
                    )
                )
            )
        )

        engine.loadDocument(txtDoc)
        assertEquals(2, engine.totalSegments())
        val firstSeg = engine.startSession()
        assertEquals("First sentence.", firstSeg?.text)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)

        // When PDF is selected, old reading is stopped and cleared
        engine.stop()
        val emptyPdfDoc = ReadingDocument(id = "", title = "report.pdf", sections = emptyList())
        engine.loadDocument(emptyPdfDoc)

        assertEquals(0, engine.totalSegments())
        assertEquals(ReadingSessionState.Idle, engine.readingState.value)
        assertNull(engine.startSession())
    }

    @Test
    fun documentReplacement_fromPdfToTxt_restoresReadingEngineCleanly() {
        val engine = ReadingEngine()
        val emptyPdfDoc = ReadingDocument(id = "", title = "report.pdf", sections = emptyList())
        engine.loadDocument(emptyPdfDoc)

        assertEquals(0, engine.totalSegments())
        assertNull(engine.startSession())

        // Subsequently selecting a TXT restores full reading functionality
        val txtDoc = ReadingDocument(
            id = "txt_2",
            title = "Chapter.txt",
            sections = listOf(
                ReadingSection(
                    id = "s1",
                    title = "Chapter 1",
                    segments = listOf(
                        ReadingSegment("s1", "Restored text reading.")
                    )
                )
            )
        )
        engine.loadDocument(txtDoc)

        assertEquals(1, engine.totalSegments())
        val seg = engine.startSession()
        assertEquals("Restored text reading.", seg?.text)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)
    }
}
