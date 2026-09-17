package com.readme.app.accessibility

import com.readme.app.reading.ReadingDocument

sealed class UnifiedCrossAppAcquisitionResult {
    data class Success(
        val document: ReadingDocument,
        val mode: CrossAppAcquisitionMode,
        val sourcePackageName: String,
        val sourceAppLabel: String?
    ) : UnifiedCrossAppAcquisitionResult()

    // Distinct controlled error states instead of raw exceptions
    object FeatureDisabled : UnifiedCrossAppAcquisitionResult()
    object ServiceUnavailable : UnifiedCrossAppAcquisitionResult()
    object ApiNotSupported : UnifiedCrossAppAcquisitionResult()
    object ReadMeSelfIgnored : UnifiedCrossAppAcquisitionResult()
    object InvalidTarget : UnifiedCrossAppAcquisitionResult()
    object SecureWindow : UnifiedCrossAppAcquisitionResult()
    object SensitiveContentBlocked : UnifiedCrossAppAcquisitionResult()
    object RateLimited : UnifiedCrossAppAcquisitionResult()
    object NoTextAvailable : UnifiedCrossAppAcquisitionResult()
    object OcrReturnedEmpty : UnifiedCrossAppAcquisitionResult()
    object TextSegmentationEmpty : UnifiedCrossAppAcquisitionResult()
    data class OcrProviderUnavailable(val details: String) : UnifiedCrossAppAcquisitionResult()
    data class CropOutsideScreenshot(val details: String) : UnifiedCrossAppAcquisitionResult()
    data class SelectedAreaTooSmall(val width: Int, val height: Int) : UnifiedCrossAppAcquisitionResult()
    object SelectedAreaOutsideWindow : UnifiedCrossAppAcquisitionResult()
    data class CaptureUnavailable(val details: String) : UnifiedCrossAppAcquisitionResult()
    object AppSwitched : UnifiedCrossAppAcquisitionResult()
    object Cancelled : UnifiedCrossAppAcquisitionResult()
    data class UnknownError(val details: String) : UnifiedCrossAppAcquisitionResult()
}
