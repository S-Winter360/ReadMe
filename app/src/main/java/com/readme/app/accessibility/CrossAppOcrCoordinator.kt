package com.readme.app.accessibility

import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.content.CrossAppDocumentParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result of attempting an explicit cross-app window OCR acquisition.
 */
sealed class CrossAppOcrAcquisitionResult {
    data class Success(
        val document: ReadingDocument,
        val packageName: String,
        val appLabel: String?
    ) : CrossAppOcrAcquisitionResult()

    object ApiNotSupported : CrossAppOcrAcquisitionResult()
    object ServiceNotConnected : CrossAppOcrAcquisitionResult()
    object ReadMeSelfIgnored : CrossAppOcrAcquisitionResult()
    object InvalidTarget : CrossAppOcrAcquisitionResult()
    object SecureWindow : CrossAppOcrAcquisitionResult()
    object SensitiveContentBlocked : CrossAppOcrAcquisitionResult()
    object RateLimited : CrossAppOcrAcquisitionResult()
    object NoTextRecognized : CrossAppOcrAcquisitionResult()
    data class StaleAppSwitch(val expectedPackage: String, val actualPackage: String) : CrossAppOcrAcquisitionResult()
    data class Error(val message: String) : CrossAppOcrAcquisitionResult()
}

/**
 * Orchestrates the explicit cross-app visual window screenshot capture and OCR flow.
 *
 * Enforces:
 * - Direct user-action triggered only
 * - Ephemeral bitmap handling with guaranteed recycling in finally block
 * - Security field filtering and FLAG_SECURE window handling
 * - App-switch race detection
 * - Zero persistence of bitmap or recognized text
 */
object CrossAppOcrCoordinator {

    suspend fun executeOcrAcquisition(
        capturer: CrossAppScreenshotCapturer?,
        ocrEngine: CrossAppOcrEngine,
        target: CrossAppWindowTarget?,
        appLabel: String? = null,
        selectedRegion: android.graphics.Rect? = null
    ): CrossAppOcrAcquisitionResult = withContext(Dispatchers.Default) {
        if (capturer == null) {
            return@withContext CrossAppOcrAcquisitionResult.ServiceNotConnected
        }

        if (!capturer.isSupported) {
            return@withContext CrossAppOcrAcquisitionResult.ApiNotSupported
        }

        if (target == null || target.windowId <= 0 || target.packageName.isBlank()) {
            return@withContext CrossAppOcrAcquisitionResult.InvalidTarget
        }

        if (target.packageName == "com.readme.app") {
            return@withContext CrossAppOcrAcquisitionResult.ReadMeSelfIgnored
        }

        if (target.isSensitiveOrPassword) {
            return@withContext CrossAppOcrAcquisitionResult.SensitiveContentBlocked
        }

        // Validate selectedRegion against target window bounds if supplied
        val clampedRegion = if (selectedRegion != null) {
            val winBounds = if (!target.windowBounds.isEmpty) target.windowBounds else selectedRegion
            ScreenGeometryMapper.clampRegion(selectedRegion, winBounds) ?: return@withContext CrossAppOcrAcquisitionResult.NoTextRecognized
        } else {
            null
        }

        val captureResult = capturer.captureWindow(target)
        when (captureResult) {
            is ScreenshotCaptureResult.ApiNotSupported -> CrossAppOcrAcquisitionResult.ApiNotSupported
            is ScreenshotCaptureResult.ServiceNotConnected -> CrossAppOcrAcquisitionResult.ServiceNotConnected
            is ScreenshotCaptureResult.ReadMeSelfIgnored -> CrossAppOcrAcquisitionResult.ReadMeSelfIgnored
            is ScreenshotCaptureResult.InvalidTarget -> CrossAppOcrAcquisitionResult.InvalidTarget
            is ScreenshotCaptureResult.SecureWindow -> CrossAppOcrAcquisitionResult.SecureWindow
            is ScreenshotCaptureResult.SensitiveContentBlocked -> CrossAppOcrAcquisitionResult.SensitiveContentBlocked
            is ScreenshotCaptureResult.RateLimited -> CrossAppOcrAcquisitionResult.RateLimited
            is ScreenshotCaptureResult.StaleAppSwitch -> CrossAppOcrAcquisitionResult.StaleAppSwitch(
                expectedPackage = captureResult.expectedPackage,
                actualPackage = captureResult.actualPackage
            )
            is ScreenshotCaptureResult.Error -> CrossAppOcrAcquisitionResult.Error(captureResult.message)
            is ScreenshotCaptureResult.Success -> {
                val snapshot = captureResult.snapshot
                val winBounds = if (!target.windowBounds.isEmpty) target.windowBounds else android.graphics.Rect(0, 0, snapshot.width, snapshot.height)
                val winW = winBounds.width().coerceAtLeast(1)
                val winH = winBounds.height().coerceAtLeast(1)
                val scaleX = snapshot.width.toFloat() / winW
                val scaleY = snapshot.height.toFloat() / winH

                val cropRect = if (clampedRegion != null) {
                    ScreenGeometryMapper.calculateCropRect(clampedRegion, winBounds, snapshot.width, snapshot.height)
                } else {
                    android.graphics.Rect(0, 0, snapshot.width, snapshot.height)
                }

                var croppedBitmap: android.graphics.Bitmap? = null
                try {
                    val bitmapToProcess = if (clampedRegion != null && (cropRect.width() < snapshot.width || cropRect.height() < snapshot.height)) {
                        croppedBitmap = android.graphics.Bitmap.createBitmap(
                            snapshot.bitmap,
                            cropRect.left,
                            cropRect.top,
                            cropRect.width(),
                            cropRect.height()
                        )
                        croppedBitmap
                    } else {
                        snapshot.bitmap
                    }

                    val ocrResult = ocrEngine.recognize(bitmapToProcess)
                    if (!ocrResult.hasText || ocrResult.text.isBlank()) {
                        return@withContext CrossAppOcrAcquisitionResult.NoTextRecognized
                    }

                    val mappedSentences = ocrResult.sentences.map {
                        ScreenGeometryMapper.mapSentenceGeometryToScreen(
                            sentence = it,
                            cropRect = cropRect,
                            scaleX = scaleX,
                            scaleY = scaleY,
                            windowLeft = winBounds.left.toFloat(),
                            windowTop = winBounds.top.toFloat()
                        )
                    }

                    val finalOcrResult = ocrResult.copy(sentences = mappedSentences)
                    val document = CrossAppDocumentParser.parseOcrResult(
                        target = target,
                        ocrResult = finalOcrResult,
                        appLabel = appLabel
                    )

                    if (document.sections.isEmpty() || document.allSegments().isEmpty()) {
                        return@withContext CrossAppOcrAcquisitionResult.NoTextRecognized
                    }

                    CrossAppOcrAcquisitionResult.Success(
                        document = document,
                        packageName = target.packageName,
                        appLabel = appLabel
                    )
                } catch (e: Throwable) {
                    CrossAppOcrAcquisitionResult.Error(e.message ?: "OCR recognition failed")
                } finally {
                    try {
                        croppedBitmap?.recycle()
                    } catch (_: Throwable) {}
                    snapshot.recycle()
                }
            }
        }
    }
}
