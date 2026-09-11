package com.readme.app.accessibility

/**
 * Controlled result of a window screenshot capture attempt.
 */
sealed class ScreenshotCaptureResult {
    data class Success(val snapshot: CrossAppImageSnapshot) : ScreenshotCaptureResult()
    object ApiNotSupported : ScreenshotCaptureResult()
    object ServiceNotConnected : ScreenshotCaptureResult()
    object ReadMeSelfIgnored : ScreenshotCaptureResult()
    object InvalidTarget : ScreenshotCaptureResult()
    object SecureWindow : ScreenshotCaptureResult()
    object SensitiveContentBlocked : ScreenshotCaptureResult()
    object RateLimited : ScreenshotCaptureResult()
    data class StaleAppSwitch(val expectedPackage: String, val actualPackage: String) : ScreenshotCaptureResult()
    data class Error(val message: String, val errorCode: Int? = null) : ScreenshotCaptureResult()
}

/**
 * Focused interface for acquiring a single window screenshot from the active accessibility service.
 *
 * Exposes no platform ScreenshotResult or AccessibilityNodeInfo objects outside the accessibility boundary.
 */
interface CrossAppScreenshotCapturer {
    val isSupported: Boolean
    fun identifyTargetWindow(): CrossAppWindowTarget?
    suspend fun captureWindow(target: CrossAppWindowTarget): ScreenshotCaptureResult
}
