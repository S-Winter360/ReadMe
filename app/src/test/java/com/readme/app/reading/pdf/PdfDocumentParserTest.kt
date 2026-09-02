package com.readme.app.reading.content.pdf

import android.graphics.RectF
import androidx.pdf.PdfDocument
import androidx.pdf.content.PdfPageTextContent
import androidx.pdf.PdfFeature
import androidx.pdf.ExperimentalPdfApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.coroutines.Continuation

@OptIn(ExperimentalPdfApi::class)
class PdfDocumentParserTest {

    // Fake PdfDocument for testing
    private class FakePdfDocument(
        private val pages: List<PdfDocument.PdfPageContent>
    ) : PdfDocument {
        override val uri: android.net.Uri get() = android.net.Uri.parse("content://fake")
        override val pageCount: Int get() = pages.size
        override val linearizationStatus: Int get() = 0
        override val renderParams: androidx.pdf.RenderParams get() = androidx.pdf.RenderParams(0, 0) // Passing dummy params
        override val formType: Int get() = 0

        override suspend fun getPageInfo(pageNum: Int): PdfDocument.PageInfo = TODO()
        override suspend fun getPageInfo(pageNum: Int, type: Long): PdfDocument.PageInfo = TODO()
        override suspend fun getPageInfos(pageRange: IntRange): List<PdfDocument.PageInfo> = TODO()
        override suspend fun getPageInfos(pageRange: IntRange, type: Long): List<PdfDocument.PageInfo> = TODO()
        override suspend fun searchDocument(query: String, pageRange: IntRange): android.util.SparseArray<List<androidx.pdf.content.PageMatchBounds>> = TODO()
        override suspend fun getSelectionBounds(pageNum: Int, start: android.graphics.PointF, end: android.graphics.PointF): androidx.pdf.content.PageSelection = TODO()
        override suspend fun getSelectionBounds(pageNum: Int, start: androidx.pdf.content.SelectionBoundary, end: androidx.pdf.content.SelectionBoundary): androidx.pdf.content.PageSelection = TODO()
        override suspend fun getSelectAllSelectionBounds(pageNum: Int): androidx.pdf.content.PageSelection = TODO()
        
        override suspend fun getPageContent(pageNum: Int): PdfDocument.PdfPageContent {
            return pages[pageNum]
        }
        
        override suspend fun getPageLinks(pageNum: Int): PdfDocument.PdfPageLinks = TODO()
        override suspend fun getAnnotationsForPage(pageNum: Int): List<androidx.pdf.annotation.content.KeyedPdfAnnotation> = TODO()
        override suspend fun getPageObjects(pageNum: Int, type: Long): List<androidx.pdf.annotation.content.KeyedPdfObject> = TODO()
        override fun getPageBitmapSource(pageNum: Int): PdfDocument.BitmapSource = TODO()
        override suspend fun getFormWidgetInfos(pageNum: Int, type: Long): List<androidx.pdf.models.FormWidgetInfo> = TODO()
        override suspend fun getTopPageObjectAtPosition(pageNum: Int, position: android.graphics.PointF): androidx.pdf.annotation.content.PdfObject = TODO()
        
        override fun addOnPdfContentInvalidatedListener(executor: java.util.concurrent.Executor, listener: PdfDocument.OnPdfContentInvalidatedListener) = TODO()
        override fun removeOnPdfContentInvalidatedListener(listener: PdfDocument.OnPdfContentInvalidatedListener) = TODO()
        override fun isFeatureSupported(feature: PdfFeature): Boolean = true
        override fun close() {}
    }

    @Test
    fun parse_basicOnePagePdf_extractsSuccessfully() {
        runBlocking {
            val parser = PdfDocumentParser()
            
            val textContent = PdfPageTextContent(listOf(RectF()), "Hello world. This is a PDF.")
            val pageContent = PdfDocument.PdfPageContent(listOf(textContent), emptyList())
            val fakeDoc = FakePdfDocument(listOf(pageContent))

            val result = parser.parse(fakeDoc, "doc1", "My PDF")
            
            assertEquals(1, result.sections.size)
            assertEquals("Page 1", result.sections[0].title)
            assertEquals("page:0", result.sections[0].id)
            
            val segments = result.sections[0].segments
            assertEquals(2, segments.size)
            assertEquals("Hello world.", segments[0].text)
            assertEquals("This is a PDF.", segments[1].text)
            assertEquals("page:0:segment:0", segments[0].id)
        }
    }

    @Test
    fun parse_multiPagePdf_preservesOrder() {
        runBlocking {
            val parser = PdfDocumentParser()
            val page1Text = PdfPageTextContent(listOf(RectF()), "Page one text.")
            val page2Text = PdfPageTextContent(listOf(RectF()), "Page two text.")
            val fakeDoc = FakePdfDocument(listOf(
                PdfDocument.PdfPageContent(listOf(page1Text), emptyList()),
                PdfDocument.PdfPageContent(listOf(page2Text), emptyList())
            ))

            val result = parser.parse(fakeDoc, "doc2", "Multi")
            
            assertEquals(2, result.sections.size)
            assertEquals("Page 1", result.sections[0].title)
            assertEquals("Page 2", result.sections[1].title)
            assertEquals("Page one text.", result.sections[0].segments[0].text)
            assertEquals("Page two text.", result.sections[1].segments[0].text)
        }
    }

    @Test
    fun parse_emptyPage_isSkipped() {
        runBlocking {
            val parser = PdfDocumentParser()
            val page1Text = PdfPageTextContent(listOf(RectF()), " ")
            val page2Text = PdfPageTextContent(listOf(RectF()), "Valid text.")
            val fakeDoc = FakePdfDocument(listOf(
                PdfDocument.PdfPageContent(listOf(page1Text), emptyList()),
                PdfDocument.PdfPageContent(listOf(page2Text), emptyList())
            ))

            val result = parser.parse(fakeDoc, "doc3", "Skipped")
            
            assertEquals(1, result.sections.size)
            assertEquals("Page 2", result.sections[0].title)
            assertEquals("page:1", result.sections[0].id)
        }
    }

    @Test
    fun parse_textlessPdf_throwsNoSelectableTextException() {
        runBlocking {
            val parser = PdfDocumentParser()
            val fakeDoc = FakePdfDocument(listOf(
                PdfDocument.PdfPageContent(emptyList(), emptyList())
            ))

            assertThrows(PdfNoSelectableTextException::class.java) {
                runBlocking {
                    parser.parse(fakeDoc, "doc4", "Image Only")
                }
            }
        }
    }

    @Test
    fun parse_whitespaceNormalization_removesArtifacts() {
        runBlocking {
            val parser = PdfDocumentParser()
            val textContent = PdfPageTextContent(listOf(RectF()), "Hyphen-\nated word.   Extra   spaces.")
            val fakeDoc = FakePdfDocument(listOf(
                PdfDocument.PdfPageContent(listOf(textContent), emptyList())
            ))

            val result = parser.parse(fakeDoc, "doc5", "Whitespace")
            
            val segments = result.sections[0].segments
            assertEquals("Hyphenated word.", segments[0].text)
            assertEquals("Extra spaces.", segments[1].text)
        }
    }
}
