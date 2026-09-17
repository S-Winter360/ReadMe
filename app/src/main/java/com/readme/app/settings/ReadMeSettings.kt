package com.readme.app.settings

data class ReadMeSettings(
    val selectedVoice: String = "natural_voice",
    val speechVolume: Float = 0.70f,
    val speechSpeed: Float = 1.0f,
    val speechPitch: Float = 0.50f,
    val isSystemBubbleEnabled: Boolean = false,
    val isFloatingReadmeEnabled: Boolean = false,
    val isCrossAppReadingEnabled: Boolean = false,
    val isScreenOcrConsentGranted: Boolean = false
)
