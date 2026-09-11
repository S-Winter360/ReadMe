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
    object ServiceUnavailable : UnifiedCrossAppAcquisitionResult()
    object ApiNotSupported : UnifiedCrossAppAcquisitionResult()
    object ReadMeSelfIgnored : UnifiedCrossAppAcquisitionResult()
    object InvalidTarget : UnifiedCrossAppAcquisitionResult()
    object SecureWindow : UnifiedCrossAppAcquisitionResult()
    object SensitiveContentBlocked : UnifiedCrossAppAcquisitionResult()
    object RateLimited : UnifiedCrossAppAcquisitionResult()
    object NoTextAvailable : UnifiedCrossAppAcquisitionResult()
    object AppSwitched : UnifiedCrossAppAcquisitionResult()
    object Cancelled : UnifiedCrossAppAcquisitionResult()
    data class UnknownError(val details: String) : UnifiedCrossAppAcquisitionResult()
}
