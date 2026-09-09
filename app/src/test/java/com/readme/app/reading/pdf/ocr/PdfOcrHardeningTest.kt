package com.readme.app.reading.pdf.ocr

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.Size
import android.util.SparseArray
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import androidx.pdf.PdfFeature
import androidx.pdf.RenderParams
import androidx.pdf.annotation.content.KeyedPdfAnnotation
import androidx.pdf.annotation.content.KeyedPdfObject
import androidx.pdf.annotation.content.PdfObject
import androidx.pdf.content.PageMatchBounds
import androidx.pdf.content.PageSelection
import androidx.pdf.content.PdfPageTextContent
import androidx.pdf.content.SelectionBoundary
import androidx.pdf.models.FormWidgetInfo
import com.readme.app.reading.ReadingPosition
import com.readme.app.reading.ReadingSessionState
import com.readme.app.reading.content.TxtDocumentParser
import com.readme.app.reading.content.pdf.PdfDocumentParser
import com.readme.app.reading.content.pdf.PdfNoSelectableTextException
import com.readme.app.reading.content.pdf.PdfReadingPositionMapper
import com.readme.app.reading.content.pdf.ocr.PdfOcrEngine
import com.readme.app.reading.content.pdf.ocr.PdfOcrException
import com.readme.app.reading.content.pdf.ocr.PdfOcrInitializationException
import com.readme.app.reading.content.pdf.ocr.PdfOcrModelUnavailableException
import com.readme.app.reading.content.pdf.ocr.PdfOcrPageResult
import com.readme.app.reading.content.pdf.ocr.PdfOcrProcessingException
import com.readme.app.reading.content.pdf.ocr.PdfOcrReadinessState
import com.readme.app.reading.content.pdf.ocr.PdfOcrTextNormalizer
import com.readme.app.reading.content.pdf.ocr.PdfOcrUnavailableException
import com.readme.app.ui.pdf.PdfNavigationCoordinator
import com.readme.app.ui.pdf.PdfPageNavigator
import com.readme.app.ui.pdf.PdfPositionReconciler
import com.readme.app.ui.pdf.PdfViewportState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException

/**
 * Deterministic unit test suite covering the 35 Phase 8H requirements for
 * OCR readiness, mixed-PDF validation, and reading quality hardening.
 */
@OptIn(ExperimentalPdfApi::class)
class PdfOcrHardeningTest {

    private fun createDummyBitmap(): Bitmap {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(Bitmap::class.java) as Bitmap
    }

    private class RecordingFakeOcrEngine(
        private val pageResponses: Map<Int, String> = emptyMap(),
        private val pageExceptions: Map<Int, Throwable> = emptyMap(),
        override val readinessState: PdfOcrReadinessState = PdfOcrReadinessState.Ready
    ) : PdfOcrEngine {
        val recognizeCalls = mutableListOf<Int>()
        var isClosed = false

        override suspend fun recognize(bitmap: Bitmap, pageIndex: Int): PdfOcrPageResult {
            if (isClosed) throw PdfOcrException("Engine is closed")
            recognizeCalls.add(pageIndex)

            pageExceptions[pageIndex]?.let { throw it }

            val text = pageResponses[pageIndex] ?: ""
            return PdfOcrPageResult(
                pageIndex = pageIndex,
                text = text,
                hasText = text.isNotBlank()
            )
        }

        override fun close() {
            isClosed = true
        }
    }

    private class FakeBitmapSource(
        private val bitmap: Bitmap,
        override val pageNumber: Int = 0
    ) : PdfDocument.BitmapSource {
        var isClosed = false
        override suspend fun getBitmap(scaledPageSizePx: Size, tileRegion: Rect?): Bitmap = bitmap
        override fun close() {
            isClosed = true
        }
    }

    private class TestPdfDocument(
        private val pages: List<PageData>,
        private val dummyBitmap: Bitmap
    ) : PdfDocument {
        data class PageData(val nativeText: String?, val width: Int = 595, val height: Int = 842)

        override val uri: Uri get() = Uri.parse("content://fake/test.pdf")
        override val pageCount: Int get() = pages.size
        override val linearizationStatus: Int get() = 0
        override val renderParams: RenderParams get() = RenderParams(0, 0)
        override val formType: Int get() = 0

        override suspend fun getPageInfo(pageNumber: Int): PdfDocument.PageInfo {
            val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null) as sun.misc.Unsafe
            return unsafe.allocateInstance(PdfDocument.PageInfo::class.java) as PdfDocument.PageInfo
        }

        override suspend fun getPageInfo(pageNumber: Int, pageInfoFlags: Long): PdfDocument.PageInfo = getPageInfo(pageNumber)
        override suspend fun getPageInfos(pageRange: IntRange): List<PdfDocument.PageInfo> = pageRange.map { getPageInfo(it) }
        override suspend fun getPageInfos(pageRange: IntRange, pageInfoFlags: Long): List<PdfDocument.PageInfo> = pageRange.map { getPageInfo(it) }

        override suspend fun getPageContent(pageNumber: Int): PdfDocument.PdfPageContent {
            val page = pages[pageNumber]
            val textContents = if (page.nativeText != null && page.nativeText.isNotBlank()) {
                listOf(PdfPageTextContent(listOf(RectF()), page.nativeText))
            } else {
                emptyList()
            }
            return PdfDocument.PdfPageContent(textContents, emptyList())
        }

        override fun getPageBitmapSource(pageNumber: Int): PdfDocument.BitmapSource {
            return FakeBitmapSource(dummyBitmap, pageNumber)
        }

        override suspend fun searchDocument(query: String, pageRange: IntRange): SparseArray<List<PageMatchBounds>> = SparseArray()
        override suspend fun getSelectionBounds(pageNumber: Int, start: PointF, end: PointF): PageSelection = TODO()
        override suspend fun getSelectionBounds(pageNumber: Int, start: SelectionBoundary, end: SelectionBoundary): PageSelection = TODO()
        override suspend fun getSelectAllSelectionBounds(pageNumber: Int): PageSelection = TODO()
        override suspend fun getPageLinks(pageNumber: Int): PdfDocument.PdfPageLinks = TODO()
        override suspend fun getAnnotationsForPage(pageNumber: Int): List<KeyedPdfAnnotation> = emptyList()
        override suspend fun getPageObjects(pageNumber: Int, types: Long): List<KeyedPdfObject> = emptyList()
        override suspend fun getFormWidgetInfos(pageNumber: Int, types: Long): List<FormWidgetInfo> = emptyList()
        override suspend fun getTopPageObjectAtPosition(pageNumber: Int, point: PointF): PdfObject = TODO()
        override fun addOnPdfContentInvalidatedListener(executor: Executor, listener: PdfDocument.OnPdfContentInvalidatedListener) {}
        override fun removeOnPdfContentInvalidatedListener(listener: PdfDocument.OnPdfContentInvalidatedListener) {}
        override fun isFeatureSupported(feature: PdfFeature): Boolean = true
        override fun close() {}
    }

    private class RecordingNavigator : PdfPageNavigator {
        var lastNavigatedPage: Int? = null
        override fun navigateToPage(pageIndex: Int) {
            lastNavigatedPage = pageIndex
        }
    }

    // 1. Native page takes precedence over OCR
    @Test
    fun test01_nativePageTakesPrecedenceOverOcr() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(TestPdfDocument.PageData(nativeText = "Native page text.")),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(pageResponses = mapOf(0 to "OCR text should not be called."))
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "doc1", title = "Precedence")

        assertEquals(1, document.sections.size)
        assertEquals("Native page text.", document.sections[0].segments[0].text)
        assertFalse("OCR must not be called when native text exists", engine.recognizeCalls.contains(0))
    }

    // 2. OCR fallback occurs when native text is empty
    @Test
    fun test02_ocrFallbackOccursWhenNativeTextIsEmpty() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(TestPdfDocument.PageData(nativeText = null)),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(pageResponses = mapOf(0 to "Fallback OCR text."))
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "doc1", title = "Fallback")

        assertEquals(1, document.sections.size)
        assertEquals("Fallback OCR text.", document.sections[0].segments[0].text)
        assertTrue("OCR must be invoked when native text is empty", engine.recognizeCalls.contains(0))
    }

    // 3. Mixed native/OCR page sequence preserves order
    @Test
    fun test03_mixedNativeAndOcrPageSequencePreservesOrder() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(
                TestPdfDocument.PageData(nativeText = "Page 0 native."),
                TestPdfDocument.PageData(nativeText = null), // Page 1 OCR
                TestPdfDocument.PageData(nativeText = "Page 2 native."),
                TestPdfDocument.PageData(nativeText = null)  // Page 3 OCR
            ),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(
            pageResponses = mapOf(
                1 to "Page 1 OCR.",
                3 to "Page 3 OCR."
            )
        )
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "mixed", title = "Mixed Order")

        assertEquals(4, document.sections.size)
        assertEquals("page:0", document.sections[0].id)
        assertEquals("page:1", document.sections[1].id)
        assertEquals("page:2", document.sections[2].id)
        assertEquals("page:3", document.sections[3].id)
    }

    // 4. Physical page indexes remain unchanged
    @Test
    fun test04_physicalPageIndexesRemainUnchanged() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(
                TestPdfDocument.PageData(nativeText = "Page 0"),
                TestPdfDocument.PageData(nativeText = null) // Page 1
            ),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(pageResponses = mapOf(1 to "Page 1 OCR"))
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "idxTest", title = "Indexes")
        val mapper = PdfReadingPositionMapper.fromDocument(document)!!

        assertEquals(0, mapper.getPageForSectionId("page:0"))
        assertEquals(1, mapper.getPageForSectionId("page:1"))
    }

    // 5. Skipped empty pages preserve physical page numbering
    @Test
    fun test05_skippedEmptyPagesPreservePhysicalPageNumbering() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(
                TestPdfDocument.PageData(nativeText = null), // Page 0 OCR
                TestPdfDocument.PageData(nativeText = null), // Page 1 Empty
                TestPdfDocument.PageData(nativeText = null)  // Page 2 OCR
            ),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(
            pageResponses = mapOf(
                0 to "Page 0 text.",
                1 to "", // Empty page
                2 to "Page 2 text."
            )
        )
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "skipped", title = "Skipped")
        val mapper = PdfReadingPositionMapper.fromDocument(document)!!

        assertEquals(2, document.sections.size)
        assertEquals("page:0", document.sections[0].id)
        assertEquals("page:2", document.sections[1].id) // NOT renumbered to page 1!
        assertEquals("Page 3", document.sections[1].title)

        assertEquals(0, mapper.getPageForSectionId("page:0"))
        assertEquals(2, mapper.getPageForSectionId("page:2"))
        assertNull(mapper.getPageForSectionId("page:1"))
    }

    // 6. OCR segment IDs are deterministic
    @Test
    fun test06_ocrSegmentIdsAreDeterministic() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(TestPdfDocument.PageData(nativeText = null)),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(pageResponses = mapOf(0 to "Sentence one. Sentence two."))
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "detDoc", title = "Deterministic")

        assertEquals("pdf:detDoc:page:0:ocr:0", document.sections[0].segments[0].id)
        assertEquals("pdf:detDoc:page:0:ocr:1", document.sections[0].segments[1].id)
    }

    // 7. Native segment IDs remain unchanged
    @Test
    fun test07_nativeSegmentIdsRemainUnchanged() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(TestPdfDocument.PageData(nativeText = "Native sent one. Native sent two.")),
            dummyBitmap = dummyBitmap
        )
        val parser = PdfDocumentParser(ocrEngine = null)
        val document = parser.parse(doc, documentId = "nativeDoc", title = "Native IDs")

        assertEquals("page:0:segment:0", document.sections[0].segments[0].id)
        assertEquals("page:0:segment:1", document.sections[0].segments[1].id)
    }

    // 8. OCR normalizer preserves legitimate hyphenation
    @Test
    fun test08_ocrNormalizerPreservesLegitimateHyphenation() {
        assertEquals("well-known", PdfOcrTextNormalizer.normalize("well-known"))
        assertEquals("well-known", PdfOcrTextNormalizer.normalize("well-\nknown"))
        assertEquals("self-respect", PdfOcrTextNormalizer.normalize("self-\nrespect"))
        assertEquals("Anglo-American", PdfOcrTextNormalizer.normalize("Anglo-\nAmerican"))
    }

    // 9. OCR dehyphenates obvious wrapped words
    @Test
    fun test09_ocrDehyphenatesObviousWrappedWords() {
        assertEquals("reading", PdfOcrTextNormalizer.normalize("read-\ning"))
        assertEquals("connection", PdfOcrTextNormalizer.normalize("connec-\ntion"))
    }

    // 10. OCR normalizer preserves punctuation
    @Test
    fun test10_ocrNormalizerPreservesPunctuation() {
        val input = "\"She said, 'Look at section 4.2!'\" (100% accurate, no?)"
        assertEquals(input, PdfOcrTextNormalizer.normalize(input))
    }

    // 11. OCR normalizer removes control characters safely
    @Test
    fun test11_ocrNormalizerRemovesControlCharactersSafely() {
        val input = "\u0000Hello\u0007 \u001FWorld\u007F!"
        assertEquals("Hello World!", PdfOcrTextNormalizer.normalize(input))
    }

    // 12. OCR sentence segmentation uses TxtDocumentParser
    @Test
    fun test12_ocrSentenceSegmentationUsesTxtDocumentParser() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val text = "First sentence here. Second sentence starts now! Is this third? Yes."
        val doc = TestPdfDocument(
            pages = listOf(TestPdfDocument.PageData(nativeText = null)),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(pageResponses = mapOf(0 to text))
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "doc12", title = "TxtParser")

        val expected = TxtDocumentParser.splitIntoSentences(text)
        val actual = document.sections[0].segments.map { it.text }
        assertEquals(expected, actual)
    }

    // 13. OCR empty result creates no fake segment
    @Test
    fun test13_ocrEmptyResultCreatesNoFakeSegment() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(
                TestPdfDocument.PageData(nativeText = null), // Page 0 empty OCR
                TestPdfDocument.PageData(nativeText = "Valid text on page 1")
            ),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(pageResponses = mapOf(0 to "   \n\t  "))
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "emptyResult", title = "Empty")

        assertEquals(1, document.sections.size)
        assertEquals("page:1", document.sections[0].id)
        assertFalse(document.sections.any { it.id == "page:0" })
    }

    // 14. OCR page failure does not create fake text
    @Test
    fun test14_ocrPageFailureDoesNotCreateFakeText() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(
                TestPdfDocument.PageData(nativeText = "Valid page 0"),
                TestPdfDocument.PageData(nativeText = null) // Page 1 fails
            ),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(
            pageResponses = emptyMap(),
            pageExceptions = mapOf(1 to PdfOcrProcessingException("Failed page 1"))
        )
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "failPage", title = "Fail")

        assertEquals(1, document.sections.size)
        assertEquals("Valid page 0", document.sections[0].segments[0].text)
        assertEquals(1, document.allSegments().size)
    }

    // 15. Page-level OCR failure preserves physical page identity
    @Test
    fun test15_pageLevelOcrFailurePreservesPhysicalPageIdentity() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(
                TestPdfDocument.PageData(nativeText = "Page 0 native"),
                TestPdfDocument.PageData(nativeText = null), // Page 1 fails OCR
                TestPdfDocument.PageData(nativeText = "Page 2 native")
            ),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(
            pageResponses = emptyMap(),
            pageExceptions = mapOf(1 to RuntimeException("Corrupted scan"))
        )
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "pageFail", title = "Recovery")
        val mapper = PdfReadingPositionMapper.fromDocument(document)!!

        assertEquals(2, document.sections.size)
        assertEquals("page:0", document.sections[0].id)
        assertEquals("page:2", document.sections[1].id)
        assertEquals(0, mapper.getPageForSectionId("page:0"))
        assertEquals(2, mapper.getPageForSectionId("page:2"))
        assertNull(mapper.getPageForSectionId("page:1"))
    }

    // 16. Document ID mismatch rejects stale OCR output
    @Test
    fun test16_documentIdMismatchRejectsStaleOcrOutput() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(TestPdfDocument.PageData(nativeText = null)),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(pageResponses = mapOf(0 to "Text content."))
        val parser = PdfDocumentParser(ocrEngine = engine)

        val docA = parser.parse(doc, documentId = "DOC_A", title = "Doc A")
        val docB = parser.parse(doc, documentId = "DOC_B", title = "Doc B")

        assertEquals("pdf:DOC_A:page:0:ocr:0", docA.sections[0].segments[0].id)
        assertEquals("pdf:DOC_B:page:0:ocr:0", docB.sections[0].segments[0].id)
        assertFalse(docA.sections[0].segments[0].id == docB.sections[0].segments[0].id)
    }

    // 17. Cancellation stops obsolete OCR processing
    @Test
    fun test17_cancellationStopsObsoleteOcrProcessing() {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = (0 until 5).map { TestPdfDocument.PageData(nativeText = null) },
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(
            pageResponses = (0 until 5).associateWith { "Page $it text." }
        )
        val parser = PdfDocumentParser(ocrEngine = engine)

        assertThrows(CancellationException::class.java) {
            runBlocking {
                cancel() // Cancel coroutine before parsing finishes
                parser.parse(doc, documentId = "cancelDoc", title = "Cancelled")
            }
        }
    }

    // 18. Newer document invalidates older OCR result
    @Test
    fun test18_newerDocumentInvalidatesOlderOcrResult() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc1 = TestPdfDocument(
            pages = listOf(TestPdfDocument.PageData(nativeText = null)),
            dummyBitmap = dummyBitmap
        )
        val doc2 = TestPdfDocument(
            pages = listOf(TestPdfDocument.PageData(nativeText = null)),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(pageResponses = mapOf(0 to "Result text."))
        val parser = PdfDocumentParser(ocrEngine = engine)

        val res1 = parser.parse(doc1, documentId = "id_1", title = "Doc 1")
        val res2 = parser.parse(doc2, documentId = "id_2", title = "Doc 2")

        assertEquals("pdf:id_1", res1.id)
        assertEquals("pdf:id_2", res2.id)
        assertTrue(res1.id != res2.id)
    }

    // 19. PDF page -> ReadingSection mapping works for OCR pages
    @Test
    fun test19_pdfPageToReadingSectionMappingWorksForOcrPages() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(TestPdfDocument.PageData(nativeText = null)),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(pageResponses = mapOf(0 to "OCR content."))
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "map19", title = "Map 19")
        val mapper = PdfReadingPositionMapper.fromDocument(document)!!

        val section = mapper.getSectionForPage(0)
        assertNotNull(section)
        assertEquals("page:0", section!!.id)
    }

    // 20. ReadingSegment -> physical PDF page works for OCR segments
    @Test
    fun test20_readingSegmentToPhysicalPdfPageWorksForOcrSegments() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(
                TestPdfDocument.PageData(nativeText = "Native page 0."),
                TestPdfDocument.PageData(nativeText = null) // OCR page 1
            ),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(pageResponses = mapOf(1 to "OCR segment text."))
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "segMap", title = "Segment Map")
        val mapper = PdfReadingPositionMapper.fromDocument(document)!!

        val ocrSegmentId = document.sections[1].segments[0].id
        val physicalPage = mapper.getPageForSegmentId(ocrSegmentId)
        assertEquals(1, physicalPage)
    }

    // 21. ReadingPosition -> physical PDF page works for OCR segments
    @Test
    fun test21_readingPositionToPhysicalPdfPageWorksForOcrSegments() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(
                TestPdfDocument.PageData(nativeText = "Page 0"),
                TestPdfDocument.PageData(nativeText = null)
            ),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(pageResponses = mapOf(1 to "OCR position text."))
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "posMap", title = "Position Map")
        val mapper = PdfReadingPositionMapper.fromDocument(document)!!

        val ocrSegmentId = document.sections[1].segments[0].id
        val position = document.positionForSegmentId(ocrSegmentId)!!
        val page = mapper.getPageForPosition(position)
        assertEquals(1, page)
    }

    // 22. Phase 8E navigation mapping works for OCR pages
    @Test
    fun test22_phase8eNavigationMappingWorksForOcrPages() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(
                TestPdfDocument.PageData(nativeText = "Native page 0."),
                TestPdfDocument.PageData(nativeText = null) // OCR page 1
            ),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(pageResponses = mapOf(1 to "OCR speech text."))
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "navDoc", title = "Nav")
        val mapper = PdfReadingPositionMapper.fromDocument(document)!!

        val navigator = RecordingNavigator()
        val coordinator = PdfNavigationCoordinator(navigator)
        coordinator.setPdfDocument(mapper, isActive = true)
        coordinator.setReadingState(ReadingSessionState.Reading)

        // Viewport is currently showing page 0
        coordinator.onViewportChanged(PdfViewportState(firstVisiblePage = 0, visiblePagesCount = 1))

        // Speech reaches OCR page 1: not visible -> navigation triggered!
        val ocrSegmentId = document.sections[1].segments[0].id
        val position = document.positionForSegmentId(ocrSegmentId)
        coordinator.evaluateNavigation(position)
        assertEquals(1, navigator.lastNavigatedPage)

        // Reset and update viewport to show page 1
        navigator.lastNavigatedPage = null
        coordinator.onViewportChanged(PdfViewportState(firstVisiblePage = 1, visiblePagesCount = 1))

        // Speech on OCR page 1 while visible -> no navigation request (no loop!)
        coordinator.evaluateNavigation(position)
        assertNull("Should not navigate when page is already visible", navigator.lastNavigatedPage)
    }

    // 23. Phase 8F page reconciliation works for OCR pages
    @Test
    fun test23_phase8fPageReconciliationWorksForOcrPages() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(
                TestPdfDocument.PageData(nativeText = "Page 0."),
                TestPdfDocument.PageData(nativeText = null) // Page 1 OCR
            ),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(pageResponses = mapOf(1 to "First OCR sentence."))
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "reconcileDoc", title = "Reconcile")
        val mapper = PdfReadingPositionMapper.fromDocument(document)!!

        // Viewport scrolled to OCR page 1 -> Read from here
        val viewport = PdfViewportState(firstVisiblePage = 1, visiblePagesCount = 1)
        val reconciled = PdfPositionReconciler.reconcile(
            document = document,
            mapper = mapper,
            viewportState = viewport,
            isPdfActive = true
        )

        assertNotNull(reconciled)
        assertEquals("page:1", reconciled!!.sectionId)
    }

    // 24. Empty OCR page resolves to next readable page where supported
    @Test
    fun test24_emptyOcrPageResolvesToNextReadablePageWhereSupported() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(
                TestPdfDocument.PageData(nativeText = "Page 0"),
                TestPdfDocument.PageData(nativeText = null), // Page 1 Empty OCR
                TestPdfDocument.PageData(nativeText = null)  // Page 2 OCR text
            ),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(
            pageResponses = mapOf(
                1 to "", // Empty page
                2 to "Readable OCR text on page 2."
            )
        )
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "reconcileEmpty", title = "Empty Resolution")
        val mapper = PdfReadingPositionMapper.fromDocument(document)!!

        // User viewport is at empty page 1 -> reconciles to next readable page (page 2)
        val viewport = PdfViewportState(firstVisiblePage = 1, visiblePagesCount = 1)
        val reconciled = PdfPositionReconciler.reconcile(
            document = document,
            mapper = mapper,
            viewportState = viewport,
            isPdfActive = true
        )

        assertNotNull(reconciled)
        assertEquals("page:2", reconciled!!.sectionId)
    }

    // 25. All-OCR PDF preserves page order
    @Test
    fun test25_allOcrPdfPreservesPageOrder() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = (0 until 4).map { TestPdfDocument.PageData(nativeText = null) },
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(
            pageResponses = (0 until 4).associateWith { "Page $it OCR text." }
        )
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "allOcr", title = "All OCR")

        assertEquals(4, document.sections.size)
        assertEquals("page:0", document.sections[0].id)
        assertEquals("page:1", document.sections[1].id)
        assertEquals("page:2", document.sections[2].id)
        assertEquals("page:3", document.sections[3].id)
    }

    // 26. All-native PDF remains native
    @Test
    fun test26_allNativePdfRemainsNative() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(
                TestPdfDocument.PageData(nativeText = "Native page 0."),
                TestPdfDocument.PageData(nativeText = "Native page 1.")
            ),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine()
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "allNative", title = "All Native")

        assertEquals(2, document.sections.size)
        assertEquals("page:0:segment:0", document.sections[0].segments[0].id)
        assertEquals("page:1:segment:0", document.sections[1].segments[0].id)
        assertEquals(0, engine.recognizeCalls.size)
    }

    // 27. Mixed PDF remains mixed internally
    @Test
    fun test27_mixedPdfRemainsMixedInternally() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(
                TestPdfDocument.PageData(nativeText = "Native content."),
                TestPdfDocument.PageData(nativeText = null)
            ),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(pageResponses = mapOf(1 to "OCR content."))
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "mixedInternal", title = "Mixed Internal")

        assertTrue(document.sections[0].segments[0].id.startsWith("page:0:segment:"))
        assertTrue(document.sections[1].segments[0].id.startsWith("pdf:mixedInternal:page:1:ocr:"))
    }

    // 28. No-text PDF remains safely unreadable
    @Test
    fun test28_noTextPdfRemainsSafelyUnreadable() {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(
                TestPdfDocument.PageData(nativeText = null),
                TestPdfDocument.PageData(nativeText = null)
            ),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(pageResponses = emptyMap())
        val parser = PdfDocumentParser(ocrEngine = engine)

        assertThrows(PdfNoSelectableTextException::class.java) {
            runBlocking {
                parser.parse(doc, documentId = "noTextDoc", title = "No Text")
            }
        }
    }

    // 29. OCR unavailable state is handled
    @Test
    fun test29_ocrUnavailableStateIsHandled() {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(TestPdfDocument.PageData(nativeText = null)),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(
            pageExceptions = mapOf(0 to PdfOcrUnavailableException("OCR unavailable"))
        )
        val parser = PdfDocumentParser(ocrEngine = engine)

        assertThrows(PdfOcrUnavailableException::class.java) {
            runBlocking {
                parser.parse(doc, documentId = "unavailDoc", title = "Unavailable")
            }
        }
    }

    // 30. OCR model unavailable state is handled
    @Test
    fun test30_ocrModelUnavailableStateIsHandled() {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(TestPdfDocument.PageData(nativeText = null)),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(
            pageExceptions = mapOf(0 to PdfOcrModelUnavailableException("Model not downloaded"))
        )
        val parser = PdfDocumentParser(ocrEngine = engine)

        assertThrows(PdfOcrModelUnavailableException::class.java) {
            runBlocking {
                parser.parse(doc, documentId = "modelUnavailDoc", title = "Model Unavailable")
            }
        }
    }

    // 31. OCR initialization failure is handled
    @Test
    fun test31_ocrInitializationFailureIsHandled() {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(TestPdfDocument.PageData(nativeText = null)),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(
            pageExceptions = mapOf(0 to PdfOcrInitializationException("Init failure"))
        )
        val parser = PdfDocumentParser(ocrEngine = engine)

        assertThrows(PdfOcrInitializationException::class.java) {
            runBlocking {
                parser.parse(doc, documentId = "initFailDoc", title = "Init Fail")
            }
        }
    }

    // 32. OCR processing failure is handled
    @Test
    fun test32_ocrProcessingFailureIsHandled() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(
                TestPdfDocument.PageData(nativeText = "Page 0 succeeds."),
                TestPdfDocument.PageData(nativeText = null) // Page 1 fails processing
            ),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(
            pageExceptions = mapOf(1 to PdfOcrProcessingException("Failed processing page 1"))
        )
        val parser = PdfDocumentParser(ocrEngine = engine)

        // Document parsing should succeed using the valid page 0
        val document = parser.parse(doc, documentId = "procFailDoc", title = "Processing Fail")
        assertEquals(1, document.sections.size)
        assertEquals("page:0", document.sections[0].id)
    }

    // 33. Repeated OCR of same page is avoided
    @Test
    fun test33_repeatedOcrOfSamePageIsAvoided() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(
                TestPdfDocument.PageData(nativeText = null),
                TestPdfDocument.PageData(nativeText = null)
            ),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(
            pageResponses = mapOf(
                0 to "Page 0 text.",
                1 to "Page 1 text."
            )
        )
        val parser = PdfDocumentParser(ocrEngine = engine)
        parser.parse(doc, documentId = "singlePassDoc", title = "Single Pass")

        // Each page must be recognized exactly once
        assertEquals(listOf(0, 1), engine.recognizeCalls)
    }

    // 34. Final OCR page maps correctly
    @Test
    fun test34_finalOcrPageMapsCorrectly() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(
                TestPdfDocument.PageData(nativeText = "Page 0"),
                TestPdfDocument.PageData(nativeText = null) // Page 1 is the final page
            ),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(pageResponses = mapOf(1 to "Final page OCR text."))
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "finalPageDoc", title = "Final Page")
        val mapper = PdfReadingPositionMapper.fromDocument(document)!!

        assertEquals(1, mapper.getPageForSectionId("page:1"))
        val lastSection = mapper.getSectionForPage(1)
        assertNotNull(lastSection)
        assertEquals("Page 2", lastSection!!.title)

        val viewport = PdfViewportState(firstVisiblePage = 1, visiblePagesCount = 1)
        val reconciled = PdfPositionReconciler.reconcile(
            document = document,
            mapper = mapper,
            viewportState = viewport,
            isPdfActive = true
        )
        assertNotNull(reconciled)
        assertEquals("page:1", reconciled!!.sectionId)
    }

    // 35. Zoom state has no effect on OCR page identity
    @Test
    fun test35_zoomStateHasNoEffectOnOcrPageIdentity() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val doc = TestPdfDocument(
            pages = listOf(
                TestPdfDocument.PageData(nativeText = "Page 0"),
                TestPdfDocument.PageData(nativeText = null)
            ),
            dummyBitmap = dummyBitmap
        )
        val engine = RecordingFakeOcrEngine(pageResponses = mapOf(1 to "OCR page 1 text."))
        val parser = PdfDocumentParser(ocrEngine = engine)
        val document = parser.parse(doc, documentId = "zoomDoc", title = "Zoom")
        val mapper = PdfReadingPositionMapper.fromDocument(document)!!

        val normalViewport = PdfViewportState(firstVisiblePage = 1, visiblePagesCount = 1, zoom = 1.0f)
        val zoomedViewport = PdfViewportState(firstVisiblePage = 1, visiblePagesCount = 1, zoom = 3.5f)

        val reconciledNormal = PdfPositionReconciler.reconcile(
            document = document,
            mapper = mapper,
            viewportState = normalViewport,
            isPdfActive = true
        )
        val reconciledZoomed = PdfPositionReconciler.reconcile(
            document = document,
            mapper = mapper,
            viewportState = zoomedViewport,
            isPdfActive = true
        )

        assertEquals(reconciledNormal, reconciledZoomed)
        assertEquals(1, mapper.getPageForPosition(reconciledZoomed!!))
    }
}
