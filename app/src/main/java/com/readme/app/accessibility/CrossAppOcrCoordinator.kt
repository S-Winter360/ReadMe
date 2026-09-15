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
    data class SelectedAreaTooSmall(val width: Int, val height: Int) : CrossAppOcrAcquisitionResult()
    object SelectedAreaOutsideWindow : CrossAppOcrAcquisitionResult()
    data class CaptureUnavailable(val message: String) : CrossAppOcrAcquisitionResult()
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

        // Validate and normalize selectedRegion against target window bounds if supplied
        val normSelection = selectedRegion?.let { ScreenGeometryMapper.normalizeRect(it) }
        val winBounds = if (!target.windowBounds.isEmpty) {
            ScreenGeometryMapper.normalizeRect(target.windowBounds)
        } else {
            normSelection ?: android.graphics.Rect(0, 0, 1080, 1920)
        }

        val clampedRegion = if (normSelection != null) {
            // 1. Check if selection overlaps target window at all
            if (!ScreenGeometryMapper.doesSelectionOverlapWindow(normSelection, winBounds)) {
                ScreenOcrDiagnostics.record(
                    ScreenOcrDiagnosticRecord(
                        targetPackageName = target.packageName,
                        windowId = target.windowId,
                        windowBounds = winBounds,
                        screenshotWidth = 0,
                        screenshotHeight = 0,
                        selectionInUi = selectedRegion,
                        normalizedSelection = normSelection,
                        convertedCropRect = android.graphics.Rect(),
                        croppedBitmapWidth = 0,
                        croppedBitmapHeight = 0,
                        scaleX = 1f,
                        scaleY = 1f,
                        densityDpi = 0,
                        ocrUpscaleFactor = 1f,
                        ocrTextLength = 0,
                        ocrSentenceCount = 0,
                        errorReason = "SelectedAreaOutsideWindow"
                    )
                )
                return@withContext CrossAppOcrAcquisitionResult.SelectedAreaOutsideWindow
            }

            // 2. Clamp to window bounds and check minimum dimensions
            val clamped = ScreenGeometryMapper.clampRegion(
                normSelection,
                winBounds,
                minWidth = ScreenGeometryMapper.DEFAULT_MIN_SIZE_PX,
                minHeight = ScreenGeometryMapper.DEFAULT_MIN_SIZE_PX
            )
            if (clamped == null) {
                ScreenOcrDiagnostics.record(
                    ScreenOcrDiagnosticRecord(
                        targetPackageName = target.packageName,
                        windowId = target.windowId,
                        windowBounds = winBounds,
                        screenshotWidth = 0,
                        screenshotHeight = 0,
                        selectionInUi = selectedRegion,
                        normalizedSelection = normSelection,
                        convertedCropRect = android.graphics.Rect(),
                        croppedBitmapWidth = 0,
                        croppedBitmapHeight = 0,
                        scaleX = 1f,
                        scaleY = 1f,
                        densityDpi = 0,
                        ocrUpscaleFactor = 1f,
                        ocrTextLength = 0,
                        ocrSentenceCount = 0,
                        errorReason = "SelectedAreaTooSmall"
                    )
                )
                return@withContext CrossAppOcrAcquisitionResult.SelectedAreaTooSmall(
                    normSelection.right - normSelection.left,
                    normSelection.bottom - normSelection.top
                )
            }
            clamped
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
            is ScreenshotCaptureResult.Error -> {
                ScreenOcrDiagnostics.record(
                    ScreenOcrDiagnosticRecord(
                        targetPackageName = target.packageName,
                        windowId = target.windowId,
                        windowBounds = winBounds,
                        screenshotWidth = 0,
                        screenshotHeight = 0,
                        selectionInUi = selectedRegion ?: android.graphics.Rect(),
                        normalizedSelection = normSelection ?: android.graphics.Rect(),
                        convertedCropRect = android.graphics.Rect(),
                        croppedBitmapWidth = 0,
                        croppedBitmapHeight = 0,
                        scaleX = 1f,
                        scaleY = 1f,
                        densityDpi = 0,
                        ocrUpscaleFactor = 1f,
                        ocrTextLength = 0,
                        ocrSentenceCount = 0,
                        errorReason = "CaptureError: ${captureResult.message}"
                    )
                )
                CrossAppOcrAcquisitionResult.Error(captureResult.message)
            }
            is ScreenshotCaptureResult.Success -> {
                val snapshot = captureResult.snapshot
                val effectiveWinBounds = if (!target.windowBounds.isEmpty) target.windowBounds else ScreenGeometryMapper.makeRect(0, 0, snapshot.width, snapshot.height)
                val winW = (effectiveWinBounds.right - effectiveWinBounds.left).coerceAtLeast(1)
                val winH = (effectiveWinBounds.bottom - effectiveWinBounds.top).coerceAtLeast(1)
                val scaleX = snapshot.width.toFloat() / winW
                val scaleY = snapshot.height.toFloat() / winH

                val cropRect = if (clampedRegion != null) {
                    ScreenGeometryMapper.calculateCropRect(clampedRegion, effectiveWinBounds, snapshot.width, snapshot.height)
                } else {
                    ScreenGeometryMapper.makeRect(0, 0, snapshot.width, snapshot.height)
                }

                // Validate crop rect if user selected a sub-region
                if (clampedRegion != null && !ScreenGeometryMapper.isCropValid(cropRect, snapshot.width, snapshot.height)) {
                    snapshot.recycle()
                    ScreenOcrDiagnostics.record(
                        ScreenOcrDiagnosticRecord(
                            targetPackageName = target.packageName,
                            windowId = target.windowId,
                            windowBounds = effectiveWinBounds,
                            screenshotWidth = snapshot.width,
                            screenshotHeight = snapshot.height,
                            selectionInUi = selectedRegion ?: android.graphics.Rect(),
                            normalizedSelection = normSelection ?: android.graphics.Rect(),
                            convertedCropRect = cropRect,
                            croppedBitmapWidth = 0,
                            croppedBitmapHeight = 0,
                            scaleX = scaleX,
                            scaleY = scaleY,
                            densityDpi = snapshot.density,
                            ocrUpscaleFactor = 1f,
                            ocrTextLength = 0,
                            ocrSentenceCount = 0,
                            errorReason = "CropInvalid: ${(cropRect.right - cropRect.left)}x${(cropRect.bottom - cropRect.top)}"
                        )
                    )
                    return@withContext CrossAppOcrAcquisitionResult.SelectedAreaTooSmall(
                        cropRect.right - cropRect.left,
                        cropRect.bottom - cropRect.top
                    )
                }

                var croppedBitmap: android.graphics.Bitmap? = null
                var scaledBitmap: android.graphics.Bitmap? = null
                try {
                    val rawCrop = if (clampedRegion != null && (cropRect.width() < snapshot.width || cropRect.height() < snapshot.height)) {
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

                    // OCR Input Quality Check (Phase 9P Section 9):
                    // If the cropped area has small height (< 48px), upscale 2x with bilinear filtering
                    // to bring character size into ML Kit's optimal recognition threshold (~16px+).
                    val shouldUpscale = rawCrop.height < 48 || (rawCrop.width < 64 && rawCrop.height < 64)
                    val ocrUpscaleFactor = if (shouldUpscale && rawCrop.width <= 1024 && rawCrop.height <= 1024) {
                        2.0f
                    } else {
                        1.0f
                    }

                    val bitmapToProcess = if (ocrUpscaleFactor > 1.0f) {
                        scaledBitmap = android.graphics.Bitmap.createScaledBitmap(
                            rawCrop,
                            (rawCrop.width * ocrUpscaleFactor).toInt(),
                            (rawCrop.height * ocrUpscaleFactor).toInt(),
                            true
                        )
                        scaledBitmap
                    } else {
                        rawCrop
                    }

                    val ocrResult = ocrEngine.recognize(bitmapToProcess)

                    // Record comprehensive diagnostics in-memory
                    ScreenOcrDiagnostics.record(
                        ScreenOcrDiagnosticRecord(
                            targetPackageName = target.packageName,
                            windowId = target.windowId,
                            windowBounds = effectiveWinBounds,
                            screenshotWidth = snapshot.width,
                            screenshotHeight = snapshot.height,
                            selectionInUi = selectedRegion ?: android.graphics.Rect(),
                            normalizedSelection = normSelection ?: android.graphics.Rect(),
                            convertedCropRect = cropRect,
                            croppedBitmapWidth = rawCrop.width,
                            croppedBitmapHeight = rawCrop.height,
                            scaleX = scaleX,
                            scaleY = scaleY,
                            densityDpi = snapshot.density,
                            ocrUpscaleFactor = ocrUpscaleFactor,
                            ocrTextLength = ocrResult.text.length,
                            ocrSentenceCount = ocrResult.sentences.size,
                            errorReason = if (!ocrResult.hasText) ocrResult.errorMessage ?: "NoTextRecognized" else null
                        )
                    )

                    if (!ocrResult.hasText || ocrResult.text.isBlank()) {
                        return@withContext CrossAppOcrAcquisitionResult.NoTextRecognized
                    }

                    val mappedSentences = ocrResult.sentences.map {
                        ScreenGeometryMapper.mapSentenceGeometryToScreen(
                            sentence = it,
                            cropRect = cropRect,
                            scaleX = scaleX,
                            scaleY = scaleY,
                            windowLeft = effectiveWinBounds.left.toFloat(),
                            windowTop = effectiveWinBounds.top.toFloat(),
                            ocrUpscaleFactor = ocrUpscaleFactor
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
                        scaledBitmap?.recycle()
                    } catch (_: Throwable) {}
                    try {
                        croppedBitmap?.recycle()
                    } catch (_: Throwable) {}
                    snapshot.recycle()
                }
            }
        }
    }
}
