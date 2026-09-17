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
    open class NoTextRecognized : CrossAppOcrAcquisitionResult() {
        companion object : NoTextRecognized()
    }
    object OcrReturnedEmpty : NoTextRecognized()
    object TextSegmentationEmpty : NoTextRecognized()
    data class OcrProviderUnavailable(val message: String) : CrossAppOcrAcquisitionResult()
    data class CropOutsideScreenshot(val details: String) : CrossAppOcrAcquisitionResult()
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
        selectedRegion: android.graphics.Rect? = null,
        displayWidth: Int = 0,
        displayHeight: Int = 0
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
                        targetWindowId = target.windowId,
                        windowBounds = winBounds,
                        screenshotWidth = 0,
                        screenshotHeight = 0,
                        displayWidth = displayWidth,
                        displayHeight = displayHeight,
                        selectionInUi = selectedRegion,
                        normalizedSelection = normSelection,
                        convertedCropRect = android.graphics.Rect(),
                        cropWidth = 0,
                        cropHeight = 0,
                        ocrInputWidth = 0,
                        ocrInputHeight = 0,
                        ocrBlockCount = 0,
                        ocrLineCount = 0,
                        ocrElementCount = 0,
                        scaleX = 1f,
                        scaleY = 1f,
                        densityDpi = 0,
                        ocrUpscaleFactor = 1f,
                        ocrTextLength = 0,
                        ocrSentenceCount = 0,
                        acquisitionResultType = "SelectedAreaOutsideWindow",
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
                        targetWindowId = target.windowId,
                        windowBounds = winBounds,
                        screenshotWidth = 0,
                        screenshotHeight = 0,
                        displayWidth = displayWidth,
                        displayHeight = displayHeight,
                        selectionInUi = selectedRegion,
                        normalizedSelection = normSelection,
                        convertedCropRect = android.graphics.Rect(),
                        cropWidth = 0,
                        cropHeight = 0,
                        ocrInputWidth = 0,
                        ocrInputHeight = 0,
                        ocrBlockCount = 0,
                        ocrLineCount = 0,
                        ocrElementCount = 0,
                        scaleX = 1f,
                        scaleY = 1f,
                        densityDpi = 0,
                        ocrUpscaleFactor = 1f,
                        ocrTextLength = 0,
                        ocrSentenceCount = 0,
                        acquisitionResultType = "SelectedAreaTooSmall",
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
                        targetWindowId = target.windowId,
                        windowBounds = winBounds,
                        screenshotWidth = 0,
                        screenshotHeight = 0,
                        displayWidth = displayWidth,
                        displayHeight = displayHeight,
                        selectionInUi = selectedRegion ?: android.graphics.Rect(),
                        normalizedSelection = normSelection ?: android.graphics.Rect(),
                        convertedCropRect = android.graphics.Rect(),
                        cropWidth = 0,
                        cropHeight = 0,
                        ocrInputWidth = 0,
                        ocrInputHeight = 0,
                        ocrBlockCount = 0,
                        ocrLineCount = 0,
                        ocrElementCount = 0,
                        scaleX = 1f,
                        scaleY = 1f,
                        densityDpi = 0,
                        ocrUpscaleFactor = 1f,
                        ocrTextLength = 0,
                        ocrSentenceCount = 0,
                        acquisitionResultType = "CaptureError",
                        errorReason = "CaptureError: ${captureResult.message}"
                    )
                )
                CrossAppOcrAcquisitionResult.Error(captureResult.message)
            }
            is ScreenshotCaptureResult.Success -> {
                val snapshot = captureResult.snapshot
                val effectiveWinBounds = if (!target.windowBounds.isEmpty) target.windowBounds else ScreenGeometryMapper.makeRect(0, 0, snapshot.width, snapshot.height)

                val originMode = ScreenGeometryMapper.determineOriginMode(
                    bitmapWidth = snapshot.width,
                    bitmapHeight = snapshot.height,
                    windowBounds = effectiveWinBounds,
                    displayWidth = displayWidth,
                    displayHeight = displayHeight
                )

                val effectiveDispW = if (displayWidth > 0) displayWidth else if (originMode == ScreenGeometryMapper.ScreenshotOriginMode.DISPLAY_ORIGIN) snapshot.width else effectiveWinBounds.width()
                val effectiveDispH = if (displayHeight > 0) displayHeight else if (originMode == ScreenGeometryMapper.ScreenshotOriginMode.DISPLAY_ORIGIN) snapshot.height else effectiveWinBounds.height()

                val scaleX = if (originMode == ScreenGeometryMapper.ScreenshotOriginMode.DISPLAY_ORIGIN) {
                    snapshot.width.toFloat() / effectiveDispW.coerceAtLeast(1)
                } else {
                    snapshot.width.toFloat() / effectiveWinBounds.width().coerceAtLeast(1)
                }

                val scaleY = if (originMode == ScreenGeometryMapper.ScreenshotOriginMode.DISPLAY_ORIGIN) {
                    snapshot.height.toFloat() / effectiveDispH.coerceAtLeast(1)
                } else {
                    snapshot.height.toFloat() / effectiveWinBounds.height().coerceAtLeast(1)
                }

                val cropRect = if (clampedRegion != null) {
                    ScreenGeometryMapper.calculateCropRect(
                        selectionOnScreen = clampedRegion,
                        windowBounds = effectiveWinBounds,
                        bitmapWidth = snapshot.width,
                        bitmapHeight = snapshot.height,
                        displayWidth = effectiveDispW,
                        displayHeight = effectiveDispH,
                        originMode = originMode
                    )
                } else {
                    ScreenGeometryMapper.makeRect(0, 0, snapshot.width, snapshot.height)
                }

                // Validate crop rect if user selected a sub-region
                if (clampedRegion != null && !ScreenGeometryMapper.isCropValid(cropRect, snapshot.width, snapshot.height)) {
                    val isOutside = cropRect.left < 0 || cropRect.top < 0 || cropRect.right > snapshot.width || cropRect.bottom > snapshot.height
                    snapshot.recycle()
                    ScreenOcrDiagnostics.record(
                        ScreenOcrDiagnosticRecord(
                            targetPackageName = target.packageName,
                            targetWindowId = target.windowId,
                            windowBounds = effectiveWinBounds,
                            screenshotWidth = snapshot.width,
                            screenshotHeight = snapshot.height,
                            displayWidth = effectiveDispW,
                            displayHeight = effectiveDispH,
                            selectionInUi = selectedRegion ?: android.graphics.Rect(),
                            normalizedSelection = normSelection ?: android.graphics.Rect(),
                            convertedCropRect = cropRect,
                            cropWidth = (cropRect.right - cropRect.left).coerceAtLeast(0),
                            cropHeight = (cropRect.bottom - cropRect.top).coerceAtLeast(0),
                            ocrInputWidth = 0,
                            ocrInputHeight = 0,
                            ocrBlockCount = 0,
                            ocrLineCount = 0,
                            ocrElementCount = 0,
                            scaleX = scaleX,
                            scaleY = scaleY,
                            densityDpi = snapshot.density,
                            ocrUpscaleFactor = 1f,
                            ocrTextLength = 0,
                            ocrSentenceCount = 0,
                            acquisitionResultType = if (isOutside) "CropOutsideScreenshot" else "SelectedAreaTooSmall",
                            errorReason = "CropInvalid: ${(cropRect.right - cropRect.left)}x${(cropRect.bottom - cropRect.top)}"
                        )
                    )
                    return@withContext if (isOutside) {
                        CrossAppOcrAcquisitionResult.CropOutsideScreenshot("${cropRect.width()}x${cropRect.height()} at (${cropRect.left},${cropRect.top})")
                    } else {
                        CrossAppOcrAcquisitionResult.SelectedAreaTooSmall(
                            cropRect.right - cropRect.left,
                            cropRect.bottom - cropRect.top
                        )
                    }
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

                    // Update DEBUG-only in-memory capture inspector
                    DebugOcrCaptureInspector.updateCaptures(snapshot.bitmap, rawCrop)

                    // OCR Input Quality Check (Phase 9R Section 9):
                    // If the cropped area has small height (< 48px), upscale 2x with bilinear filtering
                    // to bring character size into ML Kit's optimal recognition threshold (~16px+).
                    val shouldUpscale = (rawCrop.height in 1..47) || (rawCrop.width in 1..63 && rawCrop.height in 1..63)
                    val ocrUpscaleFactor = if (shouldUpscale && rawCrop.width in 1..1024 && rawCrop.height in 1..1024) {
                        2.0f
                    } else {
                        1.0f
                    }

                    val bitmapToProcess = if (ocrUpscaleFactor > 1.0f && rawCrop.width > 0 && rawCrop.height > 0) {
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

                    // Handle OCR provider unavailability
                    if (ocrResult.errorMessage != null) {
                        val errMsg = ocrResult.errorMessage
                        val isUnavailable = errMsg.contains("unavailable", ignoreCase = true) ||
                                errMsg.contains("download", ignoreCase = true) ||
                                errMsg.contains("uninitialized", ignoreCase = true)

                        val resultType = if (isUnavailable) "OcrProviderUnavailable" else if (!ocrResult.hasText) "OcrReturnedEmpty" else "Error"
                        ScreenOcrDiagnostics.record(
                            ScreenOcrDiagnosticRecord(
                                targetPackageName = target.packageName,
                                targetWindowId = target.windowId,
                                windowBounds = effectiveWinBounds,
                                screenshotWidth = snapshot.width,
                                screenshotHeight = snapshot.height,
                                displayWidth = effectiveDispW,
                                displayHeight = effectiveDispH,
                                selectionInUi = selectedRegion ?: android.graphics.Rect(),
                                normalizedSelection = normSelection ?: android.graphics.Rect(),
                                convertedCropRect = cropRect,
                                cropWidth = rawCrop.width,
                                cropHeight = rawCrop.height,
                                ocrInputWidth = bitmapToProcess.width,
                                ocrInputHeight = bitmapToProcess.height,
                                ocrBlockCount = ocrResult.blocks.size,
                                ocrLineCount = ocrResult.lines.size,
                                ocrElementCount = ocrResult.lines.sumOf { it.elements.size },
                                scaleX = scaleX,
                                scaleY = scaleY,
                                densityDpi = snapshot.density,
                                ocrUpscaleFactor = ocrUpscaleFactor,
                                ocrTextLength = ocrResult.text.length,
                                ocrSentenceCount = ocrResult.sentences.size,
                                acquisitionResultType = resultType,
                                errorReason = errMsg
                            )
                        )

                        if (isUnavailable) {
                            return@withContext CrossAppOcrAcquisitionResult.OcrProviderUnavailable(errMsg)
                        } else if (!ocrResult.hasText) {
                            return@withContext CrossAppOcrAcquisitionResult.OcrReturnedEmpty
                        }
                    }

                    // Check for empty recognition
                    if (!ocrResult.hasText || ocrResult.text.isBlank()) {
                        ScreenOcrDiagnostics.record(
                            ScreenOcrDiagnosticRecord(
                                targetPackageName = target.packageName,
                                targetWindowId = target.windowId,
                                windowBounds = effectiveWinBounds,
                                screenshotWidth = snapshot.width,
                                screenshotHeight = snapshot.height,
                                displayWidth = effectiveDispW,
                                displayHeight = effectiveDispH,
                                selectionInUi = selectedRegion ?: android.graphics.Rect(),
                                normalizedSelection = normSelection ?: android.graphics.Rect(),
                                convertedCropRect = cropRect,
                                cropWidth = rawCrop.width,
                                cropHeight = rawCrop.height,
                                ocrInputWidth = bitmapToProcess.width,
                                ocrInputHeight = bitmapToProcess.height,
                                ocrBlockCount = ocrResult.blocks.size,
                                ocrLineCount = ocrResult.lines.size,
                                ocrElementCount = ocrResult.lines.sumOf { it.elements.size },
                                scaleX = scaleX,
                                scaleY = scaleY,
                                densityDpi = snapshot.density,
                                ocrUpscaleFactor = ocrUpscaleFactor,
                                ocrTextLength = 0,
                                ocrSentenceCount = 0,
                                acquisitionResultType = "OcrReturnedEmpty",
                                errorReason = "OcrReturnedEmpty"
                            )
                        )
                        return@withContext CrossAppOcrAcquisitionResult.OcrReturnedEmpty
                    }

                    // Map sentence geometries back to screen display coordinates using originMode
                    val mappedSentences = ocrResult.sentences.map {
                        ScreenGeometryMapper.mapSentenceGeometryToScreen(
                            sentence = it,
                            cropRect = cropRect,
                            scaleX = scaleX,
                            scaleY = scaleY,
                            windowLeft = effectiveWinBounds.left.toFloat(),
                            windowTop = effectiveWinBounds.top.toFloat(),
                            ocrUpscaleFactor = ocrUpscaleFactor,
                            originMode = originMode
                        )
                    }

                    val finalOcrResult = ocrResult.copy(sentences = mappedSentences)
                    val document = CrossAppDocumentParser.parseOcrResult(
                        target = target,
                        ocrResult = finalOcrResult,
                        appLabel = appLabel
                    )

                    if (document.sections.isEmpty() || document.allSegments().isEmpty()) {
                        ScreenOcrDiagnostics.record(
                            ScreenOcrDiagnosticRecord(
                                targetPackageName = target.packageName,
                                targetWindowId = target.windowId,
                                windowBounds = effectiveWinBounds,
                                screenshotWidth = snapshot.width,
                                screenshotHeight = snapshot.height,
                                displayWidth = effectiveDispW,
                                displayHeight = effectiveDispH,
                                selectionInUi = selectedRegion ?: android.graphics.Rect(),
                                normalizedSelection = normSelection ?: android.graphics.Rect(),
                                convertedCropRect = cropRect,
                                cropWidth = rawCrop.width,
                                cropHeight = rawCrop.height,
                                ocrInputWidth = bitmapToProcess.width,
                                ocrInputHeight = bitmapToProcess.height,
                                ocrBlockCount = ocrResult.blocks.size,
                                ocrLineCount = ocrResult.lines.size,
                                ocrElementCount = ocrResult.lines.sumOf { it.elements.size },
                                scaleX = scaleX,
                                scaleY = scaleY,
                                densityDpi = snapshot.density,
                                ocrUpscaleFactor = ocrUpscaleFactor,
                                ocrTextLength = ocrResult.text.length,
                                ocrSentenceCount = 0,
                                acquisitionResultType = "TextSegmentationEmpty",
                                errorReason = "TextSegmentationEmpty"
                            )
                        )
                        return@withContext CrossAppOcrAcquisitionResult.TextSegmentationEmpty
                    }

                    // Record comprehensive diagnostics in-memory for success
                    ScreenOcrDiagnostics.record(
                        ScreenOcrDiagnosticRecord(
                            targetPackageName = target.packageName,
                            targetWindowId = target.windowId,
                            windowBounds = effectiveWinBounds,
                            screenshotWidth = snapshot.width,
                            screenshotHeight = snapshot.height,
                            displayWidth = effectiveDispW,
                            displayHeight = effectiveDispH,
                            selectionInUi = selectedRegion ?: android.graphics.Rect(),
                            normalizedSelection = normSelection ?: android.graphics.Rect(),
                            convertedCropRect = cropRect,
                            cropWidth = rawCrop.width,
                            cropHeight = rawCrop.height,
                            ocrInputWidth = bitmapToProcess.width,
                            ocrInputHeight = bitmapToProcess.height,
                            ocrBlockCount = ocrResult.blocks.size,
                            ocrLineCount = ocrResult.lines.size,
                            ocrElementCount = ocrResult.lines.sumOf { it.elements.size },
                            scaleX = scaleX,
                            scaleY = scaleY,
                            densityDpi = snapshot.density,
                            ocrUpscaleFactor = ocrUpscaleFactor,
                            ocrTextLength = ocrResult.text.length,
                            ocrSentenceCount = ocrResult.sentences.size,
                            acquisitionResultType = "Success",
                            errorReason = null
                        )
                    )

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
