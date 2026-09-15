package com.readme.app.accessibility

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Unified coordinator that manages both explicit text acquisition (accessibility tree) and
 * screen OCR acquisition. It ensures that only one acquisition happens at a time and maps
 * the specific underlying coordinators to a unified result.
 */
object CrossAppReadingCoordinator {
    
    // Ensure we do not process multiple concurrent acquisitions
    private val busyMutex = Mutex()

    suspend fun acquire(
        mode: CrossAppAcquisitionMode,
        textAcquirer: CrossAppTextAcquirer?,
        screenshotCapturer: CrossAppScreenshotCapturer?,
        ocrEngine: CrossAppOcrEngine?,
        request: CrossAppAcquisitionRequest? = null,
        target: CrossAppWindowTarget? = null,
        appLabel: String? = null,
        selectedRegion: android.graphics.Rect? = null
    ): UnifiedCrossAppAcquisitionResult {
        if (!busyMutex.tryLock()) {
            // Already busy. Either reject or cancel previous. We reject to keep it simple and safe.
            return UnifiedCrossAppAcquisitionResult.RateLimited
        }
        
        try {
            return when (mode) {
                CrossAppAcquisitionMode.ACCESSIBILITY_TEXT -> {
                    val req = request ?: CrossAppAcquisitionRequest()
                    val result = CrossAppAcquisitionCoordinator.executeAcquisition(
                        acquirer = textAcquirer,
                        request = req,
                        appLabel = appLabel
                    )
                    mapTextResult(result)
                }
                CrossAppAcquisitionMode.SCREEN_OCR -> {
                    if (ocrEngine == null) return UnifiedCrossAppAcquisitionResult.ServiceUnavailable
                    val result = CrossAppOcrCoordinator.executeOcrAcquisition(
                        capturer = screenshotCapturer,
                        ocrEngine = ocrEngine,
                        target = target,
                        appLabel = appLabel,
                        selectedRegion = selectedRegion
                    )
                    mapOcrResult(result)
                }
            }
        } finally {
            busyMutex.unlock()
        }
    }

    private fun mapTextResult(result: AcquisitionResult): UnifiedCrossAppAcquisitionResult {
        return when (result) {
            is AcquisitionResult.Success -> UnifiedCrossAppAcquisitionResult.Success(
                document = result.document,
                mode = CrossAppAcquisitionMode.ACCESSIBILITY_TEXT,
                sourcePackageName = result.snapshot.sourcePackageName,
                sourceAppLabel = null // Document title contains it, or we could pass it if we saved it
            )
            is AcquisitionResult.ServiceNotConnected -> UnifiedCrossAppAcquisitionResult.ServiceUnavailable
            is AcquisitionResult.NoTextAvailable -> UnifiedCrossAppAcquisitionResult.NoTextAvailable
            is AcquisitionResult.ReadMeSelfIgnored -> UnifiedCrossAppAcquisitionResult.ReadMeSelfIgnored
            is AcquisitionResult.StaleAppSwitch -> UnifiedCrossAppAcquisitionResult.AppSwitched
            is AcquisitionResult.StaleGeneration -> UnifiedCrossAppAcquisitionResult.AppSwitched
            is AcquisitionResult.Error -> UnifiedCrossAppAcquisitionResult.UnknownError(result.message)
        }
    }

    private fun mapOcrResult(result: CrossAppOcrAcquisitionResult): UnifiedCrossAppAcquisitionResult {
        return when (result) {
            is CrossAppOcrAcquisitionResult.Success -> UnifiedCrossAppAcquisitionResult.Success(
                document = result.document,
                mode = CrossAppAcquisitionMode.SCREEN_OCR,
                sourcePackageName = result.packageName,
                sourceAppLabel = result.appLabel
            )
            is CrossAppOcrAcquisitionResult.ApiNotSupported -> UnifiedCrossAppAcquisitionResult.ApiNotSupported
            is CrossAppOcrAcquisitionResult.ServiceNotConnected -> UnifiedCrossAppAcquisitionResult.ServiceUnavailable
            is CrossAppOcrAcquisitionResult.InvalidTarget -> UnifiedCrossAppAcquisitionResult.InvalidTarget
            is CrossAppOcrAcquisitionResult.NoTextRecognized -> UnifiedCrossAppAcquisitionResult.NoTextAvailable
            is CrossAppOcrAcquisitionResult.SelectedAreaTooSmall -> UnifiedCrossAppAcquisitionResult.SelectedAreaTooSmall(result.width, result.height)
            is CrossAppOcrAcquisitionResult.SelectedAreaOutsideWindow -> UnifiedCrossAppAcquisitionResult.SelectedAreaOutsideWindow
            is CrossAppOcrAcquisitionResult.CaptureUnavailable -> UnifiedCrossAppAcquisitionResult.CaptureUnavailable(result.message)
            is CrossAppOcrAcquisitionResult.RateLimited -> UnifiedCrossAppAcquisitionResult.RateLimited
            is CrossAppOcrAcquisitionResult.ReadMeSelfIgnored -> UnifiedCrossAppAcquisitionResult.ReadMeSelfIgnored
            is CrossAppOcrAcquisitionResult.SecureWindow -> UnifiedCrossAppAcquisitionResult.SecureWindow
            is CrossAppOcrAcquisitionResult.SensitiveContentBlocked -> UnifiedCrossAppAcquisitionResult.SensitiveContentBlocked
            is CrossAppOcrAcquisitionResult.StaleAppSwitch -> UnifiedCrossAppAcquisitionResult.AppSwitched
            is CrossAppOcrAcquisitionResult.Error -> UnifiedCrossAppAcquisitionResult.UnknownError(result.message)
        }
    }
}
