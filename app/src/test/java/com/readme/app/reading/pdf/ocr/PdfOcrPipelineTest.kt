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
import com.readme.app.reading.content.TxtDocumentParser
import com.readme.app.reading.content.pdf.PdfDocumentParser
import com.readme.app.reading.content.pdf.PdfNoSelectableTextException
import com.readme.app.reading.content.pdf.PdfReadingPositionMapper
import com.readme.app.reading.content.pdf.ocr.PdfOcrEngine
import com.readme.app.reading.content.pdf.ocr.PdfOcrException
import com.readme.app.reading.content.pdf.ocr.PdfOcrModelUnavailableException
import com.readme.app.reading.content.pdf.ocr.PdfOcrPageResult
import com.readme.app.reading.content.pdf.ocr.PdfOcrUnavailableException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalPdfApi::class)
class PdfOcrPipelineTest {

    private fun createDummyBitmap(): Bitmap {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(Bitmap::class.java) as Bitmap
    }

    /**
     * Fake OCR Engine with controllable behavior per page.
     */
    class FakePdfOcrEngine(
        private val pageResponses: Map<Int, String> = emptyMap(),
        private val shouldThrow: Throwable? = null
    ) : PdfOcrEngine {
        val recognizedPages = mutableListOf<Int>()
        var isClosed = false

        override suspend fun recognize(bitmap: Bitmap, pageIndex: Int): PdfOcrPageResult {
            if (isClosed) throw PdfOcrException("Engine closed")
            shouldThrow?.let { throw it }
            recognizedPages.add(pageIndex)
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

    /**
     * Fake BitmapSource that releases cleanly.
     */
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

    /**
     * Fake PdfDocument with configurable native text and dimensions per page.
     */
    private class FakeOcrPdfDocument(
        private val pages: List<PageData>,
        private val dummyBitmap: Bitmap
    ) : PdfDocument {
        data class PageData(
            val nativeText: String?,
            val width: Int = 595,
            val height: Int = 842
        )

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

    // 1. OCR text normalisation in pipeline
    @Test
    fun pipeline_normalizesHyphenationAndLinebreaksInOcrText() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val fakeDoc = FakeOcrPdfDocument(
            pages = listOf(
                FakeOcrPdfDocument.PageData(nativeText = null) // Scanned page
            ),
            dummyBitmap = dummyBitmap
        )
        val ocrEngine = FakePdfOcrEngine(
            pageResponses = mapOf(
                0 to "This is a demon-\nstration of dehyphen-\nation. It works well."
            )
        )

        val parser = PdfDocumentParser(ocrEngine = ocrEngine)
        val document = parser.parse(fakeDoc, documentId = "doc1", title = "OCR Doc")

        assertEquals(1, document.sections.size)
        val section = document.sections[0]
        assertEquals("page:0", section.id)
        assertEquals("Page 1", section.title)
        assertEquals(2, section.segments.size)
        assertEquals("This is a demonstration of dehyphenation.", section.segments[0].text)
        assertEquals("It works well.", section.segments[1].text)
    }

    // 2. Empty OCR result handling
    @Test
    fun pipeline_emptyOcrResult_throwsNoSelectableTextWhenAllPagesEmpty() {
        val dummyBitmap = createDummyBitmap()
        val fakeDoc = FakeOcrPdfDocument(
            pages = listOf(
                FakeOcrPdfDocument.PageData(nativeText = null)
            ),
            dummyBitmap = dummyBitmap
        )
        val ocrEngine = FakePdfOcrEngine(
            pageResponses = mapOf(0 to "   \n\t  ") // Empty OCR text
        )

        val parser = PdfDocumentParser(ocrEngine = ocrEngine)
        assertThrows(PdfNoSelectableTextException::class.java) {
            runBlocking {
                parser.parse(fakeDoc, documentId = "doc1", title = "Empty Doc")
            }
        }
    }

    // 3. Whitespace normalization
    @Test
    fun pipeline_collapsesRepeatedSpacesAndTabs() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val fakeDoc = FakeOcrPdfDocument(
            pages = listOf(FakeOcrPdfDocument.PageData(nativeText = null)),
            dummyBitmap = dummyBitmap
        )
        val ocrEngine = FakePdfOcrEngine(
            pageResponses = mapOf(0 to "Word1   \t   Word2 \t\t  Word3.")
        )

        val parser = PdfDocumentParser(ocrEngine = ocrEngine)
        val document = parser.parse(fakeDoc, documentId = "doc1", title = "Spaces")

        assertEquals("Word1 Word2 Word3.", document.sections[0].segments[0].text)
    }

    // 4. Punctuation preservation
    @Test
    fun pipeline_preservesPunctuationAndQuotes() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val fakeDoc = FakeOcrPdfDocument(
            pages = listOf(FakeOcrPdfDocument.PageData(nativeText = null)),
            dummyBitmap = dummyBitmap
        )
        val ocrEngine = FakePdfOcrEngine(
            pageResponses = mapOf(0 to "\"She said, 'Look at section 4.2!'\" (100% accurate, no?)")
        )

        val parser = PdfDocumentParser(ocrEngine = ocrEngine)
        val document = parser.parse(fakeDoc, documentId = "doc1", title = "Punctuation")

        assertEquals(2, document.sections[0].segments.size)
        assertEquals("\"She said, 'Look at section 4.2!'\"", document.sections[0].segments[0].text)
        assertEquals("(100% accurate, no?)", document.sections[0].segments[1].text)
    }

    // 5. Sentence segmentation uses existing TxtDocumentParser
    @Test
    fun pipeline_usesExistingTxtDocumentParserForSegmentation() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val ocrRaw = "First sentence here. Second sentence starts now! Is this third? Yes."
        val fakeDoc = FakeOcrPdfDocument(
            pages = listOf(FakeOcrPdfDocument.PageData(nativeText = null)),
            dummyBitmap = dummyBitmap
        )
        val ocrEngine = FakePdfOcrEngine(pageResponses = mapOf(0 to ocrRaw))

        val parser = PdfDocumentParser(ocrEngine = ocrEngine)
        val document = parser.parse(fakeDoc, documentId = "doc1", title = "Segmentation")

        val expectedSentences = TxtDocumentParser.splitIntoSentences(ocrRaw)
        val actualSentences = document.sections[0].segments.map { it.text }
        assertEquals(expectedSentences, actualSentences)
    }

    // 6. OCR page -> ReadingSection mapping
    @Test
    fun pipeline_mapsOcrPageToReadingSectionWithExpectedStructure() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val fakeDoc = FakeOcrPdfDocument(
            pages = listOf(FakeOcrPdfDocument.PageData(nativeText = null)),
            dummyBitmap = dummyBitmap
        )
        val ocrEngine = FakePdfOcrEngine(pageResponses = mapOf(0 to "Scanned page text."))

        val parser = PdfDocumentParser(ocrEngine = ocrEngine)
        val document = parser.parse(fakeDoc, documentId = "doc42", title = "Page Map")

        val section = document.sections[0]
        assertEquals("page:0", section.id)
        assertEquals("Page 1", section.title)
    }

    // 7. OCR segment IDs are deterministic
    @Test
    fun pipeline_ocrSegmentIdsFollowDeterministicPattern() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val fakeDoc = FakeOcrPdfDocument(
            pages = listOf(FakeOcrPdfDocument.PageData(nativeText = null)),
            dummyBitmap = dummyBitmap
        )
        val ocrEngine = FakePdfOcrEngine(pageResponses = mapOf(0 to "Sentence A. Sentence B."))

        val parser = PdfDocumentParser(ocrEngine = ocrEngine)
        val document = parser.parse(fakeDoc, documentId = "alphaDoc", title = "Deterministic IDs")

        assertEquals("pdf:alphaDoc:page:0:ocr:0", document.sections[0].segments[0].id)
        assertEquals("pdf:alphaDoc:page:0:ocr:1", document.sections[0].segments[1].id)
    }

    // 8. Physical page index is preserved
    // 9. Empty page does not shift physical page numbering
    @Test
    fun pipeline_emptyPageDoesNotShiftPhysicalPageIndices() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        // Page 0: Native text
        // Page 1: Scanned text (OCR)
        // Page 2: Empty page (e.g. blank scan or photograph)
        // Page 3: Scanned text (OCR)
        val fakeDoc = FakeOcrPdfDocument(
            pages = listOf(
                FakeOcrPdfDocument.PageData(nativeText = "Native page 0 content."),
                FakeOcrPdfDocument.PageData(nativeText = null),
                FakeOcrPdfDocument.PageData(nativeText = null),
                FakeOcrPdfDocument.PageData(nativeText = null)
            ),
            dummyBitmap = dummyBitmap
        )
        val ocrEngine = FakePdfOcrEngine(
            pageResponses = mapOf(
                1 to "Scanned page 1 content.",
                2 to "", // Empty page
                3 to "Scanned page 3 content."
            )
        )

        val parser = PdfDocumentParser(ocrEngine = ocrEngine)
        val document = parser.parse(fakeDoc, documentId = "preservedPages", title = "Physical Map")

        assertEquals(3, document.sections.size)
        // Section 0 corresponds to physical page 0
        assertEquals("page:0", document.sections[0].id)
        assertEquals("Page 1", document.sections[0].title)

        // Section 1 corresponds to physical page 1
        assertEquals("page:1", document.sections[1].id)
        assertEquals("Page 2", document.sections[1].title)

        // Section 2 corresponds to physical page 3 (page 2 was skipped, NEVER renumber page 3 as 2!)
        assertEquals("page:3", document.sections[2].id)
        assertEquals("Page 4", document.sections[2].title)

        // Verify with PdfReadingPositionMapper
        val mapper = PdfReadingPositionMapper.fromDocument(document)
        assertNotNull(mapper)
        assertEquals(0, mapper!!.getPageForSectionId("page:0"))
        assertEquals(1, mapper.getPageForSectionId("page:1"))
        assertEquals(3, mapper.getPageForSectionId("page:3"))
        assertEquals(null, mapper.getPageForSectionId("page:2"))
    }

    // 10. Mixed native / OCR page mapping in a single document
    @Test
    fun pipeline_mixedNativeAndOcrPageMapping() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val fakeDoc = FakeOcrPdfDocument(
            pages = listOf(
                FakeOcrPdfDocument.PageData(nativeText = "Native text on page zero."),
                FakeOcrPdfDocument.PageData(nativeText = null) // Scanned on page 1
            ),
            dummyBitmap = dummyBitmap
        )
        val ocrEngine = FakePdfOcrEngine(
            pageResponses = mapOf(1 to "OCR text on page one.")
        )

        val parser = PdfDocumentParser(ocrEngine = ocrEngine)
        val document = parser.parse(fakeDoc, documentId = "mixedDoc", title = "Mixed Document")

        assertEquals(2, document.sections.size)
        // Native page uses native segment ID scheme
        assertEquals("page:0:segment:0", document.sections[0].segments[0].id)
        assertEquals("Native text on page zero.", document.sections[0].segments[0].text)

        // OCR page uses OCR segment ID scheme
        assertEquals("pdf:mixedDoc:page:1:ocr:0", document.sections[1].segments[0].id)
        assertEquals("OCR text on page one.", document.sections[1].segments[0].text)

        // Native page did NOT trigger OCR recognize call on page 0!
        assertFalse(ocrEngine.recognizedPages.contains(0))
        assertTrue(ocrEngine.recognizedPages.contains(1))
    }

    // 11. OCR page with multiple segments
    @Test
    fun pipeline_ocrPageWithMultipleSentencesProducesMultipleSegments() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val fakeDoc = FakeOcrPdfDocument(
            pages = listOf(FakeOcrPdfDocument.PageData(nativeText = null)),
            dummyBitmap = dummyBitmap
        )
        val ocrEngine = FakePdfOcrEngine(
            pageResponses = mapOf(
                0 to "First sentence. Second sentence. Third sentence. Fourth sentence!"
            )
        )

        val parser = PdfDocumentParser(ocrEngine = ocrEngine)
        val document = parser.parse(fakeDoc, documentId = "multiSeg", title = "Multi Seg")

        assertEquals(4, document.sections[0].segments.size)
        assertEquals("pdf:multiSeg:page:0:ocr:0", document.sections[0].segments[0].id)
        assertEquals("pdf:multiSeg:page:0:ocr:1", document.sections[0].segments[1].id)
        assertEquals("pdf:multiSeg:page:0:ocr:2", document.sections[0].segments[2].id)
        assertEquals("pdf:multiSeg:page:0:ocr:3", document.sections[0].segments[3].id)
    }

    // 12. No-text OCR result throws PdfNoSelectableTextException
    @Test
    fun pipeline_noTextInEntirePdf_throwsException() {
        val dummyBitmap = createDummyBitmap()
        val fakeDoc = FakeOcrPdfDocument(
            pages = listOf(
                FakeOcrPdfDocument.PageData(nativeText = null),
                FakeOcrPdfDocument.PageData(nativeText = null)
            ),
            dummyBitmap = dummyBitmap
        )
        val ocrEngine = FakePdfOcrEngine(pageResponses = emptyMap())

        val parser = PdfDocumentParser(ocrEngine = ocrEngine)
        val exception = assertThrows(PdfNoSelectableTextException::class.java) {
            runBlocking {
                parser.parse(fakeDoc, documentId = "docEmpty", title = "Empty")
            }
        }
        assertEquals("No selectable text was found in this PDF.", exception.message)
    }

    // 13. OCR error mapping
    @Test
    fun pipeline_ocrUnavailableAndModelExceptions() {
        val exc1 = PdfOcrUnavailableException()
        assertEquals("OCR is currently unavailable on this device.", exc1.message)

        val exc2 = PdfOcrModelUnavailableException()
        assertEquals("OCR model is currently unavailable on this device.", exc2.message)
    }

    // 14. Document ID validation / namespacing
    @Test
    fun pipeline_documentIdIsNamespacedInReadingDocumentAndSegments() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val fakeDoc = FakeOcrPdfDocument(
            pages = listOf(FakeOcrPdfDocument.PageData(nativeText = null)),
            dummyBitmap = dummyBitmap
        )
        val ocrEngine = FakePdfOcrEngine(pageResponses = mapOf(0 to "Content text."))

        val parser = PdfDocumentParser(ocrEngine = ocrEngine)
        val docA = parser.parse(fakeDoc, documentId = "DOC_AAA", title = "Doc A")
        val docB = parser.parse(fakeDoc, documentId = "DOC_BBB", title = "Doc B")

        assertEquals("pdf:DOC_AAA", docA.id)
        assertEquals("pdf:DOC_BBB", docB.id)
        assertEquals("pdf:DOC_AAA:page:0:ocr:0", docA.sections[0].segments[0].id)
        assertEquals("pdf:DOC_BBB:page:0:ocr:0", docB.sections[0].segments[0].id)
    }

    // 15. Stale OCR result rejected / cancelled on document change
    @Test
    fun pipeline_cancellationHaltsProcessingImmediately() {
        val dummyBitmap = createDummyBitmap()
        val fakeDoc = FakeOcrPdfDocument(
            pages = (0 until 10).map { FakeOcrPdfDocument.PageData(nativeText = null) },
            dummyBitmap = dummyBitmap
        )
        val ocrEngine = FakePdfOcrEngine(
            pageResponses = (0 until 10).associateWith { "Page $it OCR text." }
        )

        val parser = PdfDocumentParser(ocrEngine = ocrEngine)
        assertThrows(CancellationException::class.java) {
            runBlocking {
                // Cancel current coroutine scope immediately
                cancel()
                parser.parse(fakeDoc, documentId = "cancelledDoc", title = "Cancelled")
            }
        }
    }

    // 16. Page-order preservation
    @Test
    fun pipeline_preservesPageOrderAcrossMultiplePages() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val fakeDoc = FakeOcrPdfDocument(
            pages = listOf(
                FakeOcrPdfDocument.PageData(nativeText = null),
                FakeOcrPdfDocument.PageData(nativeText = null),
                FakeOcrPdfDocument.PageData(nativeText = null),
                FakeOcrPdfDocument.PageData(nativeText = null)
            ),
            dummyBitmap = dummyBitmap
        )
        val ocrEngine = FakePdfOcrEngine(
            pageResponses = mapOf(
                0 to "Page 0 content.",
                1 to "Page 1 content.",
                2 to "Page 2 content.",
                3 to "Page 3 content."
            )
        )

        val parser = PdfDocumentParser(ocrEngine = ocrEngine)
        val document = parser.parse(fakeDoc, documentId = "orderedDoc", title = "Ordered")

        assertEquals(4, document.sections.size)
        assertEquals("page:0", document.sections[0].id)
        assertEquals("page:1", document.sections[1].id)
        assertEquals("page:2", document.sections[2].id)
        assertEquals("page:3", document.sections[3].id)
    }

    // 17. Final OCR page mapping through PdfReadingPositionMapper
    @Test
    fun pipeline_finalOcrPageMappingThroughPositionMapper() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val fakeDoc = FakeOcrPdfDocument(
            pages = listOf(
                FakeOcrPdfDocument.PageData(nativeText = null),
                FakeOcrPdfDocument.PageData(nativeText = null)
            ),
            dummyBitmap = dummyBitmap
        )
        val ocrEngine = FakePdfOcrEngine(
            pageResponses = mapOf(
                0 to "First page.",
                1 to "Second page."
            )
        )

        val parser = PdfDocumentParser(ocrEngine = ocrEngine)
        val document = parser.parse(fakeDoc, documentId = "navDoc", title = "Navigation")
        val mapper = PdfReadingPositionMapper.fromDocument(document)

        assertNotNull(mapper)
        assertEquals(0, mapper!!.getPageForSectionId("page:0"))
        assertEquals(1, mapper.getPageForSectionId("page:1"))
        assertEquals("Page 1", mapper.getSectionForPage(0)?.title)
        assertEquals("Page 2", mapper.getSectionForPage(1)?.title)
    }

    // 18. Malformed OCR result handling (stray control characters, broken lines)
    @Test
    fun pipeline_malformedOcrResultHandling() = runBlocking {
        val dummyBitmap = createDummyBitmap()
        val fakeDoc = FakeOcrPdfDocument(
            pages = listOf(FakeOcrPdfDocument.PageData(nativeText = null)),
            dummyBitmap = dummyBitmap
        )
        val malformedText = "\u0000Some  \t  noisy-\ntext with \u0007control codes."
        val ocrEngine = FakePdfOcrEngine(pageResponses = mapOf(0 to malformedText))

        val parser = PdfDocumentParser(ocrEngine = ocrEngine)
        val document = parser.parse(fakeDoc, documentId = "malformedDoc", title = "Malformed")

        assertEquals(1, document.sections[0].segments.size)
        assertEquals("Some noisytext with control codes.", document.sections[0].segments[0].text)
    }

    // 19. Cancellation state handling
    @Test
    fun pipeline_cancellationDuringMultiPageProcessing() {
        val dummyBitmap = createDummyBitmap()
        val fakeDoc = FakeOcrPdfDocument(
            pages = listOf(
                FakeOcrPdfDocument.PageData(nativeText = null),
                FakeOcrPdfDocument.PageData(nativeText = null)
            ),
            dummyBitmap = dummyBitmap
        )
        // Custom engine that cancels current coroutine on page 0
        val cancellingEngine = object : PdfOcrEngine {
            override suspend fun recognize(bitmap: Bitmap, pageIndex: Int): PdfOcrPageResult {
                kotlin.coroutines.coroutineContext.cancel()
                throw CancellationException("Cancelled")
            }
            override fun close() {}
        }

        val parser = PdfDocumentParser(ocrEngine = cancellingEngine)
        assertThrows(CancellationException::class.java) {
            runBlocking {
                parser.parse(fakeDoc, documentId = "cancellingDoc", title = "Cancel")
            }
        }
    }

    // 20. OCR unavailable state handling (null ocrEngine falls back to native-only)
    @Test
    fun pipeline_ocrUnavailableFallbackToNativeOrThrows() {
        runBlocking {
            // If native text exists on some pages, parser succeeds without OCR engine
            val fakeDocWithNative = FakeOcrPdfDocument(
                pages = listOf(FakeOcrPdfDocument.PageData(nativeText = "Native page text.")),
                dummyBitmap = createDummyBitmap()
            )
            val parserWithoutOcr = PdfDocumentParser(ocrEngine = null)
            val document = parserWithoutOcr.parse(fakeDocWithNative, documentId = "noOcrDoc", title = "No OCR")
            assertEquals(1, document.sections.size)
            assertEquals("Native page text.", document.sections[0].segments[0].text)

            // If no native text and no OCR engine, throws PdfNoSelectableTextException
            val fakeDocScannedOnly = FakeOcrPdfDocument(
                pages = listOf(FakeOcrPdfDocument.PageData(nativeText = null)),
                dummyBitmap = createDummyBitmap()
            )
            assertThrows(PdfNoSelectableTextException::class.java) {
                runBlocking {
                    parserWithoutOcr.parse(fakeDocScannedOnly, documentId = "scannedDoc", title = "Scanned")
                }
            }
        }
    }
}
