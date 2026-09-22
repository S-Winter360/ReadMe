package com.readme.app.diagnostics

import android.graphics.Rect
import com.readme.app.accessibility.*
import com.readme.app.reading.content.DocumentFormatDetector
import com.readme.app.reading.content.DetectedFormat
import com.readme.app.reading.content.TxtDocumentParser
import com.readme.app.reading.content.epub.EpubContainerException
import com.readme.app.reading.content.epub.EpubDocumentParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

class ReadMeCrashSafetyTest {

    @Before
    fun setUp() {
        ReadMeCrashLogger.clearLastCrash()
    }

    @Test
    fun `crash logger captures uncaught exception details with stack trace and memory info`() {
        val testException = IllegalStateException("Test crash for diagnosis")
        val testThread = Thread.currentThread()

        val info = ReadMeCrashLogger.recordCrash(testThread, testException)

        assertNotNull(info)
        assertEquals("java.lang.IllegalStateException", info.exceptionClass)
        assertEquals("Test crash for diagnosis", info.message)
        assertEquals(testThread.name, info.threadName)
        assertTrue(info.stackTrace.contains("ReadMeCrashSafetyTest"))
        assertTrue(info.maxMemoryBytes > 0)
        assertEquals(info, ReadMeCrashLogger.lastCrash)
    }

    @Test
    fun `DocumentFormatDetector handles unknown and null formats safely without crashing`() {
        assertEquals(DetectedFormat.UNKNOWN, DocumentFormatDetector.detect(null, "somefile.xyz"))
        assertEquals(DetectedFormat.UNKNOWN, DocumentFormatDetector.detect(null, ""))
        assertEquals(DetectedFormat.PDF, DocumentFormatDetector.detect("application/pdf", "doc.bin"))
        assertEquals(DetectedFormat.EPUB, DocumentFormatDetector.detect("application/epub+zip", "book"))
        assertEquals(DetectedFormat.TXT, DocumentFormatDetector.detect("text/plain", "notes"))
    }

    @Test
    fun `EpubDocumentParser safely throws structured exception on empty or malformed container`() {
        try {
            EpubDocumentParser.parse(ByteArrayInputStream(ByteArray(0)), "Empty")
            fail("Expected EpubContainerException")
        } catch (e: EpubContainerException) {
            assertTrue(e.message?.contains("empty") == true)
        }

        try {
            EpubDocumentParser.parse(ByteArrayInputStream("Not a zip file".toByteArray()), "Corrupt")
            fail("Expected Exception")
        } catch (_: Exception) {
            // Safely caught structured exception
        }
    }

    @Test
    fun `TxtDocumentParser safely parses blank and irregular text without crashing`() {
        val blankDoc = TxtDocumentParser.parse("Blank", "", "doc_blank")
        assertTrue(blankDoc.sections.isEmpty())

        val punctuationOnly = TxtDocumentParser.parse("Punct", "... !? -- ", "doc_punct")
        assertNotNull(punctuationOnly)
    }

    @Test
    fun `CrossAppReadingCoordinator handles null target and invalid window safely`() = runBlocking {
        val result = CrossAppReadingCoordinator.acquire(
            mode = CrossAppAcquisitionMode.SCREEN_OCR,
            textAcquirer = null,
            screenshotCapturer = null,
            ocrEngine = null,
            request = CrossAppAcquisitionRequest(targetPackageName = "com.other.app"),
            target = null,
            appLabel = null,
            selectedRegion = null,
            displayWidth = 1080,
            displayHeight = 1920
        )

        assertTrue(
            result is UnifiedCrossAppAcquisitionResult.InvalidTarget ||
            result is UnifiedCrossAppAcquisitionResult.ServiceUnavailable ||
            result is UnifiedCrossAppAcquisitionResult.CaptureUnavailable
        )
    }

    @Test
    fun `CrossAppReadingCoordinator handles empty selection region safely`() = runBlocking {
        val fakeTarget = CrossAppWindowTarget(
            packageName = "com.other.app",
            windowId = 1,
            windowBounds = Rect(0, 0, 1080, 1920)
        )

        val result = CrossAppReadingCoordinator.acquire(
            mode = CrossAppAcquisitionMode.SCREEN_OCR,
            textAcquirer = null,
            screenshotCapturer = null,
            ocrEngine = null,
            request = CrossAppAcquisitionRequest(targetPackageName = "com.other.app"),
            target = fakeTarget,
            appLabel = "Other App",
            selectedRegion = Rect(100, 100, 100, 100), // Zero size
            displayWidth = 1080,
            displayHeight = 1920
        )

        assertTrue(
            result is UnifiedCrossAppAcquisitionResult.SelectedAreaTooSmall ||
            result is UnifiedCrossAppAcquisitionResult.CaptureUnavailable ||
            result is UnifiedCrossAppAcquisitionResult.ServiceUnavailable
        )
    }
}
