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
        appLabel: String? = null
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
                try {
                    val ocrResult = ocrEngine.recognize(snapshot.bitmap)
                    if (!ocrResult.hasText || ocrResult.text.isBlank()) {
                        return@withContext CrossAppOcrAcquisitionResult.NoTextRecognized
                    }

                    val document = CrossAppDocumentParser.parseOcrText(
                        target = target,
                        ocrText = ocrResult.text,
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
                    snapshot.recycle()
                }
            }
        }
    }
}
