package com.readme.app.accessibility

import android.graphics.Bitmap
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.content.CrossAppDocumentParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossAppOcrAcquisitionTest {

    private class FakeScreenshotCapturer(
        override val isSupported: Boolean = true,
        var targetToReturn: CrossAppWindowTarget? = null,
        var resultToReturn: ScreenshotCaptureResult? = null
    ) : CrossAppScreenshotCapturer {
        var captureCalledWithTarget: CrossAppWindowTarget? = null

        override fun identifyTargetWindow(): CrossAppWindowTarget? = targetToReturn

        override suspend fun captureWindow(target: CrossAppWindowTarget): ScreenshotCaptureResult {
            captureCalledWithTarget = target
            return resultToReturn ?: ScreenshotCaptureResult.Error("No result set")
        }
    }

    private class FakeOcrEngine(
        var recognizedTextToReturn: String = "",
        var shouldThrow: Boolean = false
    ) : CrossAppOcrEngine {
        var isClosed: Boolean = false

        override suspend fun recognize(bitmap: Bitmap): CrossAppOcrResult {
            if (shouldThrow) {
                throw RuntimeException("Simulated OCR failure")
            }
            return CrossAppOcrResult(text = recognizedTextToReturn)
        }

        override fun close() {
            isClosed = true
        }
    }

    @Test
    fun `null capturer returns ServiceNotConnected`() = runBlocking {
        val ocrEngine = FakeOcrEngine()
        val result = CrossAppOcrCoordinator.executeOcrAcquisition(
            capturer = null,
            ocrEngine = ocrEngine,
            target = CrossAppWindowTarget(packageName = "com.external.app", windowId = 1)
        )
        assertTrue(result is CrossAppOcrAcquisitionResult.ServiceNotConnected)
    }

    @Test
    fun `null target returns InvalidTarget`() = runBlocking {
        val capturer = FakeScreenshotCapturer()
        val ocrEngine = FakeOcrEngine()
        val result = CrossAppOcrCoordinator.executeOcrAcquisition(
            capturer = capturer,
            ocrEngine = ocrEngine,
            target = null
        )
        assertTrue(result is CrossAppOcrAcquisitionResult.InvalidTarget)
    }

    @Test
    fun `blank package target returns InvalidTarget`() = runBlocking {
        val capturer = FakeScreenshotCapturer()
        val ocrEngine = FakeOcrEngine()
        val result = CrossAppOcrCoordinator.executeOcrAcquisition(
            capturer = capturer,
            ocrEngine = ocrEngine,
            target = CrossAppWindowTarget(packageName = "   ", windowId = 1)
        )
        assertTrue(result is CrossAppOcrAcquisitionResult.InvalidTarget)
    }

    @Test
    fun `readme self package returns ReadMeSelfIgnored`() = runBlocking {
        val capturer = FakeScreenshotCapturer()
        val ocrEngine = FakeOcrEngine()
        val result = CrossAppOcrCoordinator.executeOcrAcquisition(
            capturer = capturer,
            ocrEngine = ocrEngine,
            target = CrossAppWindowTarget(packageName = "com.readme.app", windowId = 1)
        )
        assertTrue(result is CrossAppOcrAcquisitionResult.ReadMeSelfIgnored)
    }

    @Test
    fun `sensitive target returns SensitiveContentBlocked`() = runBlocking {
        val capturer = FakeScreenshotCapturer()
        val ocrEngine = FakeOcrEngine()
        val result = CrossAppOcrCoordinator.executeOcrAcquisition(
            capturer = capturer,
            ocrEngine = ocrEngine,
            target = CrossAppWindowTarget(
                packageName = "com.bank.app",
                windowId = 1,
                isSensitiveOrPassword = true
            )
        )
        assertTrue(result is CrossAppOcrAcquisitionResult.SensitiveContentBlocked)
    }

    @Test
    fun `secure window failure translates to SecureWindow result`() = runBlocking {
        val capturer = FakeScreenshotCapturer(
            resultToReturn = ScreenshotCaptureResult.SecureWindow
        )
        val ocrEngine = FakeOcrEngine()
        val result = CrossAppOcrCoordinator.executeOcrAcquisition(
            capturer = capturer,
            ocrEngine = ocrEngine,
            target = CrossAppWindowTarget(packageName = "com.streaming.app", windowId = 1)
        )
        assertTrue(result is CrossAppOcrAcquisitionResult.SecureWindow)
    }

    @Test
    fun `rate limited failure translates to RateLimited result`() = runBlocking {
        val capturer = FakeScreenshotCapturer(
            resultToReturn = ScreenshotCaptureResult.RateLimited
        )
        val ocrEngine = FakeOcrEngine()
        val result = CrossAppOcrCoordinator.executeOcrAcquisition(
            capturer = capturer,
            ocrEngine = ocrEngine,
            target = CrossAppWindowTarget(packageName = "com.external.app", windowId = 1)
        )
        assertTrue(result is CrossAppOcrAcquisitionResult.RateLimited)
    }

    private fun createDummyBitmap(): Bitmap {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(Bitmap::class.java) as Bitmap
    }

    @Test
    fun `blank OCR output returns NoTextRecognized and recycles bitmap`() = runBlocking {
        val bitmap = createDummyBitmap()
        val snapshot = CrossAppImageSnapshot(
            packageName = "com.external.app",
            windowId = 1,
            requestId = 100L,
            generation = 1L,
            bitmap = bitmap
        )
        val capturer = FakeScreenshotCapturer(
            resultToReturn = ScreenshotCaptureResult.Success(snapshot)
        )
        val ocrEngine = FakeOcrEngine(recognizedTextToReturn = "   \n\n  ")
        val result = CrossAppOcrCoordinator.executeOcrAcquisition(
            capturer = capturer,
            ocrEngine = ocrEngine,
            target = CrossAppWindowTarget(packageName = "com.external.app", windowId = 1)
        )
        assertTrue(result is CrossAppOcrAcquisitionResult.NoTextRecognized)
    }

    @Test
    fun `successful OCR produces ephemeral ReadingDocument and recycles bitmap`() = runBlocking {
        val bitmap = createDummyBitmap()
        val snapshot = CrossAppImageSnapshot(
            packageName = "com.news.reader",
            windowId = 2,
            requestId = 200L,
            generation = 1L,
            bitmap = bitmap
        )
        val capturer = FakeScreenshotCapturer(
            resultToReturn = ScreenshotCaptureResult.Success(snapshot)
        )
        val ocrText = "Breaking News Headline\n\nFirst paragraph of visual article content."
        val ocrEngine = FakeOcrEngine(recognizedTextToReturn = ocrText)
        val target = CrossAppWindowTarget(packageName = "com.news.reader", windowId = 2)
        val result = CrossAppOcrCoordinator.executeOcrAcquisition(
            capturer = capturer,
            ocrEngine = ocrEngine,
            target = target,
            appLabel = "News Daily"
        )
        assertTrue(result is CrossAppOcrAcquisitionResult.Success)
        val success = result as CrossAppOcrAcquisitionResult.Success
        assertEquals("com.news.reader", success.packageName)
        assertEquals("Text from News Daily", success.document.metadata.title)
        assertEquals(ReadingDocumentSourceType.OTHER, success.document.metadata.sourceType)
        assertFalse(success.document.allSegments().isEmpty())
    }

    @Test
    fun `OCR exception recycles bitmap and returns Error result`() = runBlocking {
        val bitmap = createDummyBitmap()
        val snapshot = CrossAppImageSnapshot(
            packageName = "com.news.reader",
            windowId = 2,
            requestId = 200L,
            generation = 1L,
            bitmap = bitmap
        )
        val capturer = FakeScreenshotCapturer(
            resultToReturn = ScreenshotCaptureResult.Success(snapshot)
        )
        val ocrEngine = FakeOcrEngine(shouldThrow = true)
        val target = CrossAppWindowTarget(packageName = "com.news.reader", windowId = 2)
        val result = CrossAppOcrCoordinator.executeOcrAcquisition(
            capturer = capturer,
            ocrEngine = ocrEngine,
            target = target
        )
        assertTrue(result is CrossAppOcrAcquisitionResult.Error)
    }

    @Test
    fun `parseOcrText creates clean segments from multiline OCR text`() {
        val ocrText = "Line one of heading.\n\nParagraph two with some text.\n\nParagraph three concluding."
        val target = CrossAppWindowTarget(packageName = "com.test.app", windowId = 1)
        val doc = CrossAppDocumentParser.parseOcrText(
            target = target,
            ocrText = ocrText,
            appLabel = "TestApp"
        )
        assertEquals("Text from TestApp", doc.metadata.title)
        val segments = doc.allSegments()
        assertEquals(3, segments.size)
        assertEquals("Line one of heading.", segments[0].text)
        assertEquals("Paragraph two with some text.", segments[1].text)
        assertEquals("Paragraph three concluding.", segments[2].text)
        assertEquals(ReadingDocumentSourceType.OTHER, doc.metadata.sourceType)
    }
}
