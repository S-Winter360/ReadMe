package com.readme.app.accessibility

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScreenOcrDiagnosticPipelineTest {

    @Before
    fun setup() {
        ScreenOcrDiagnostics.clear()
    }

    private class FakeCapturer(
        override val isSupported: Boolean = true,
        var resultToReturn: ScreenshotCaptureResult? = null
    ) : CrossAppScreenshotCapturer {
        override fun identifyTargetWindow(): CrossAppWindowTarget? = null
        override suspend fun captureWindow(target: CrossAppWindowTarget): ScreenshotCaptureResult {
            return resultToReturn ?: ScreenshotCaptureResult.Error("No capture result configured")
        }
    }

    private class FakeEngine(
        var text: String = "",
        var sentences: List<CrossAppOcrSentence> = emptyList()
    ) : CrossAppOcrEngine {
        override suspend fun recognize(bitmap: Bitmap): CrossAppOcrResult {
            return CrossAppOcrResult(
                text = text,
                hasText = text.isNotBlank(),
                sentences = sentences
            )
        }
    }

    @Test
    fun `ScreenGeometryMapper normalizes inverted rects correctly`() {
        val inverted = ScreenGeometryMapper.makeRect(300, 500, 100, 200)
        val normalized = ScreenGeometryMapper.normalizeRect(inverted)
        assertEquals(100, normalized.left)
        assertEquals(200, normalized.top)
        assertEquals(300, normalized.right)
        assertEquals(500, normalized.bottom)
    }

    @Test
    fun `ScreenGeometryMapper calculates crop rect with aspect and scale mapping`() {
        // Window bounds: 0, 0, 1080, 2400. Screenshot: 1080x2400 (scale 1.0)
        val windowBounds = ScreenGeometryMapper.makeRect(0, 0, 1080, 2400)
        val selection = ScreenGeometryMapper.makeRect(100, 200, 500, 800)
        val crop = ScreenGeometryMapper.calculateCropRect(selection, windowBounds, 1080, 2400)

        assertEquals(100, crop.left)
        assertEquals(200, crop.top)
        assertEquals(500, crop.right)
        assertEquals(800, crop.bottom)
    }

    @Test
    fun `ScreenGeometryMapper calculates scaled crop rect when screenshot resolution differs from window points`() {
        // Window points: 540x1200, Screenshot pixels: 1080x2400 (scale 2.0)
        val windowBounds = ScreenGeometryMapper.makeRect(0, 0, 540, 1200)
        val selection = ScreenGeometryMapper.makeRect(50, 100, 250, 400)
        val crop = ScreenGeometryMapper.calculateCropRect(selection, windowBounds, 1080, 2400)

        assertEquals(100, crop.left)
        assertEquals(200, crop.top)
        assertEquals(500, crop.right)
        assertEquals(800, crop.bottom)
    }

    @Test
    fun `ScreenGeometryMapper detects overlap and clamping correctly`() {
        val win = ScreenGeometryMapper.makeRect(0, 100, 1080, 2000)
        val outside = ScreenGeometryMapper.makeRect(0, 2100, 500, 2300)
        assertFalse(ScreenGeometryMapper.doesSelectionOverlapWindow(outside, win))

        val overlapping = ScreenGeometryMapper.makeRect(50, 50, 400, 300)
        assertTrue(ScreenGeometryMapper.doesSelectionOverlapWindow(overlapping, win))

        val clamped = ScreenGeometryMapper.clampRegion(overlapping, win, minWidth = 10, minHeight = 10)
        assertNotNull(clamped)
        assertEquals(50, clamped!!.left)
        assertEquals(100, clamped.top) // clamped to window top
        assertEquals(400, clamped.right)
        assertEquals(300, clamped.bottom)
    }

    @Test
    fun `ScreenGeometryMapper clampRegion rejects regions smaller than minimum size`() {
        val win = ScreenGeometryMapper.makeRect(0, 0, 1080, 2000)
        val tiny = ScreenGeometryMapper.makeRect(100, 100, 105, 105) // 5x5 px
        val clamped = ScreenGeometryMapper.clampRegion(tiny, win, minWidth = 16, minHeight = 16)
        assertNull(clamped)
    }

    @Test
    fun `ScreenGeometryMapper maps sentence geometry correctly with upscale factor`() {
        val sentence = CrossAppOcrSentence(
            text = "Hello Android",
            bounds = ScreenGeometryMapper.makeRectF(20f, 40f, 220f, 80f),
            lineBounds = listOf(ScreenGeometryMapper.makeRectF(20f, 40f, 220f, 80f))
        )
        val cropRect = ScreenGeometryMapper.makeRect(100, 200, 500, 600)

        // scale 1.0, upscale factor 2.0 (OCR ran on 2x image)
        val mapped = ScreenGeometryMapper.mapSentenceGeometryToScreen(
            sentence = sentence,
            cropRect = cropRect,
            scaleX = 1.0f,
            scaleY = 1.0f,
            windowLeft = 0f,
            windowTop = 0f,
            ocrUpscaleFactor = 2.0f
        )

        // (20 / 2) + 100 = 110
        assertEquals(110f, mapped.bounds.left, 0.01f)
        // (40 / 2) + 200 = 220
        assertEquals(220f, mapped.bounds.top, 0.01f)
        // (220 / 2) + 100 = 210
        assertEquals(210f, mapped.bounds.right, 0.01f)
        // (80 / 2) + 200 = 240
        assertEquals(240f, mapped.bounds.bottom, 0.01f)
    }

    @Test
    fun `ScreenOcrDiagnostics records and manages diagnostic traces`() {
        val record = ScreenOcrDiagnosticRecord(
            targetPackageName = "com.sample.app",
            windowId = 42,
            windowBounds = ScreenGeometryMapper.makeRect(0, 0, 1080, 2400),
            screenshotWidth = 1080,
            screenshotHeight = 2400,
            selectionInUi = ScreenGeometryMapper.makeRect(100, 200, 500, 600),
            normalizedSelection = ScreenGeometryMapper.makeRect(100, 200, 500, 600),
            convertedCropRect = ScreenGeometryMapper.makeRect(100, 200, 500, 600),
            croppedBitmapWidth = 400,
            croppedBitmapHeight = 400,
            scaleX = 1f,
            scaleY = 1f,
            densityDpi = 480,
            ocrUpscaleFactor = 1f,
            ocrTextLength = 25,
            ocrSentenceCount = 2,
            errorReason = null
        )

        ScreenOcrDiagnostics.record(record)
        val records = ScreenOcrDiagnostics.getRecentRecords()
        assertEquals(1, records.size)
        assertEquals("com.sample.app", records[0].targetPackageName)
        assertEquals(42, records[0].windowId)
        assertEquals(25, records[0].ocrTextLength)

        ScreenOcrDiagnostics.clear()
        assertTrue(ScreenOcrDiagnostics.getRecentRecords().isEmpty())
    }

    @Test
    fun `CrossAppOcrCoordinator reports SelectedAreaOutsideWindow when selection does not overlap target`() = runBlocking {
        val capturer = FakeCapturer()
        val engine = FakeEngine()
        val target = CrossAppWindowTarget(
            packageName = "com.sample.target",
            windowId = 10,
            windowBounds = ScreenGeometryMapper.makeRect(0, 0, 1080, 1000)
        )
        val selectionOutside = ScreenGeometryMapper.makeRect(0, 1200, 500, 1500)

        val result = CrossAppOcrCoordinator.executeOcrAcquisition(
            capturer = capturer,
            ocrEngine = engine,
            target = target,
            selectedRegion = selectionOutside
        )

        assertTrue(result is CrossAppOcrAcquisitionResult.SelectedAreaOutsideWindow)
        val recent = ScreenOcrDiagnostics.getRecentRecords()
        assertEquals(1, recent.size)
        assertEquals("SelectedAreaOutsideWindow", recent[0].errorReason)
    }

    @Test
    fun `CrossAppOcrCoordinator reports SelectedAreaTooSmall when selection is below minimum size`() = runBlocking {
        val capturer = FakeCapturer()
        val engine = FakeEngine()
        val target = CrossAppWindowTarget(
            packageName = "com.sample.target",
            windowId = 10,
            windowBounds = ScreenGeometryMapper.makeRect(0, 0, 1080, 1000)
        )
        val tinySelection = ScreenGeometryMapper.makeRect(100, 100, 108, 108) // 8x8 px

        val result = CrossAppOcrCoordinator.executeOcrAcquisition(
            capturer = capturer,
            ocrEngine = engine,
            target = target,
            selectedRegion = tinySelection
        )

        assertTrue(result is CrossAppOcrAcquisitionResult.SelectedAreaTooSmall)
        val recent = ScreenOcrDiagnostics.getRecentRecords()
        assertEquals(1, recent.size)
        assertEquals("SelectedAreaTooSmall", recent[0].errorReason)
    }

    @Test
    fun `CrossAppOcrCoordinator reports Error when capturer fails with error`() = runBlocking {
        val capturer = FakeCapturer(
            resultToReturn = ScreenshotCaptureResult.Error("Hardware buffer allocation failed")
        )
        val engine = FakeEngine()
        val target = CrossAppWindowTarget(
            packageName = "com.sample.target",
            windowId = 10,
            windowBounds = ScreenGeometryMapper.makeRect(0, 0, 1080, 1000)
        )

        val result = CrossAppOcrCoordinator.executeOcrAcquisition(
            capturer = capturer,
            ocrEngine = engine,
            target = target
        )

        assertTrue(result is CrossAppOcrAcquisitionResult.Error)
        val captureErr = result as CrossAppOcrAcquisitionResult.Error
        assertTrue(captureErr.message.contains("Hardware buffer"))
        val recent = ScreenOcrDiagnostics.getRecentRecords()
        assertEquals(1, recent.size)
        assertTrue(recent[0].errorReason?.contains("Hardware buffer") == true)
    }
}
