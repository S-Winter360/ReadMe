package com.readme.app.ui

import com.readme.app.settings.ReadMeSettings
import com.readme.app.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3A: Automated tests for Onboarding Foundation and Navigation.
 */
class Phase3AOnboardingTest {

    @Test
    fun testScreenEnumIncludesOnboarding() {
        val screenNames = Screen.values().map { it.name }
        assertTrue("Screen enum must contain Onboarding", screenNames.contains("Onboarding"))
        assertTrue("Screen enum must preserve Home", screenNames.contains("Home"))
        assertTrue("Screen enum must preserve Settings", screenNames.contains("Settings"))
        assertTrue("Screen enum must preserve Account", screenNames.contains("Account"))
    }

    @Test
    fun testDefaultSettingsOnboardingState() {
        val settings = ReadMeSettings()
        assertFalse("New settings must not be marked completed by default", settings.isOnboardingCompleted)
        assertEquals("natural_voice", settings.selectedVoice)
        assertEquals(1.0f, settings.speechSpeed, 0.001f)
    }

    @Test
    fun testExistingUserPreservesPreferencesAndMarkedCompleted() {
        val existingSettings = ReadMeSettings(
            selectedVoice = "en-us-custom",
            speechSpeed = 1.25f,
            speechVolume = 0.85f,
            isOnboardingCompleted = true
        )
        assertTrue(existingSettings.isOnboardingCompleted)
        assertEquals("en-us-custom", existingSettings.selectedVoice)
        assertEquals(1.25f, existingSettings.speechSpeed, 0.001f)
    }

    @Test
    fun testOnboardingCompletedFlag() {
        val completedSettings = ReadMeSettings(isOnboardingCompleted = true)
        assertTrue("Onboarding should be marked completed", completedSettings.isOnboardingCompleted)
    }
}
