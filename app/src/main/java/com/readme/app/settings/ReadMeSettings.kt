package com.readme.app.settings

enum class PagedReaderNavigationMode {
    ACCESSIBILITY_ACTION,
    TAP_SCREEN_EDGE
}

data class ReadMeSettings(
    val selectedVoice: String = "natural_voice",
    val speechVolume: Float = 0.70f,
    val speechSpeed: Float = 1.0f,
    val speechPitch: Float = 0.50f,
    val isSystemBubbleEnabled: Boolean = false,
    val isFloatingReadmeEnabled: Boolean = false,
    val isCrossAppReadingEnabled: Boolean = false,
    val isScreenOcrConsentGranted: Boolean = false,
    val isAutoAdvanceScreenReadingEnabled: Boolean = false,
    val pagedReaderNavigationMode: PagedReaderNavigationMode = PagedReaderNavigationMode.ACCESSIBILITY_ACTION
)
