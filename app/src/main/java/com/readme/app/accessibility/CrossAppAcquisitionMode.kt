package com.readme.app.accessibility

/**
 * Defines the explicit acquisition method chosen by the user to read cross-app content.
 */
enum class CrossAppAcquisitionMode {
    /**
     * Reads text natively exposed by the foreground application through the Android Accessibility tree.
     */
    ACCESSIBILITY_TEXT,

    /**
     * Captures a screenshot of the foreground application window and uses local on-device OCR
     * to recognize the visible text. Requires API 34+.
     */
    SCREEN_OCR
}
