package com.readme.app.accessibility

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentMetadata
import com.readme.app.reading.content.CrossAppDocumentParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase9JUnifiedCrossAppReadingTest {

    private val dummyDocument = ReadingDocument(
        id = "test_doc",
        metadata = ReadingDocumentMetadata(title = "Test", author = null),
        sections = emptyList()
    )

    @Test
    fun `Test 1 Verify Read Current Text explicitly triggers accessibility path and maps to UnifiedCrossAppAcquisitionResult`() = runBlocking {
        val mockAcquirer = object : CrossAppTextAcquirer {
            override fun acquireCurrentText(): CrossAppTextSnapshot {
                return CrossAppTextSnapshot(
                    sourcePackageName = "com.example.app",
                    blocks = listOf(CrossAppTextBlock("Hello Accessibility", 0))
                )
            }
        }

        val result = CrossAppReadingCoordinator.acquire(
            mode = CrossAppAcquisitionMode.ACCESSIBILITY_TEXT,
            textAcquirer = mockAcquirer,
            screenshotCapturer = null,
            ocrEngine = null
        )

        assertTrue(result is UnifiedCrossAppAcquisitionResult.Success)
        val success = result as UnifiedCrossAppAcquisitionResult.Success
        assertEquals(CrossAppAcquisitionMode.ACCESSIBILITY_TEXT, success.mode)
        assertEquals("com.example.app", success.sourcePackageName)
    }

    @Test
    fun `Test 2 Verify Read Screen explicitly triggers OCR path and maps to UnifiedCrossAppAcquisitionResult`() = runBlocking {
        val mockCapturer = object : CrossAppScreenshotCapturer {
            override val isSupported = true
            override suspend fun captureWindow(target: CrossAppWindowTarget): ScreenshotCaptureResult {
                return ScreenshotCaptureResult.Error("mock")
            }
            override fun identifyTargetWindow(): CrossAppWindowTarget {
                return CrossAppWindowTarget("com.example.app", 1)
            }
        }
        val mockEngine = object : CrossAppOcrEngine {
            override suspend fun recognize(bitmap: Bitmap): CrossAppOcrResult {
                return CrossAppOcrResult()
            }
            override fun close() {}
        }

        val result = CrossAppReadingCoordinator.acquire(
            mode = CrossAppAcquisitionMode.SCREEN_OCR,
            textAcquirer = null,
            screenshotCapturer = mockCapturer,
            ocrEngine = mockEngine,
            target = CrossAppWindowTarget("com.example.app", 1)
        )

        // It will fail at OCR or screenshot since we return failure
        assertTrue(result is UnifiedCrossAppAcquisitionResult.UnknownError)
    }

    @Test
    fun `Test 4 Verify no automatic fallback occurs if one method fails`() = runBlocking {
        // If ACCESSIBILITY_TEXT fails, it shouldn't magically return SCREEN_OCR results
        val nullAcquirer = object : CrossAppTextAcquirer {
            override fun acquireCurrentText(): CrossAppTextSnapshot? = null
        }
        
        val result = CrossAppReadingCoordinator.acquire(
            mode = CrossAppAcquisitionMode.ACCESSIBILITY_TEXT,
            textAcquirer = nullAcquirer,
            screenshotCapturer = null, // Will fail if it tries to use this
            ocrEngine = null
        )
        
        // Fails as NoTextAvailable, does not try OCR
        assertTrue(result is UnifiedCrossAppAcquisitionResult.NoTextAvailable)
    }
}
