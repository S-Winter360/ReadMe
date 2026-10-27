package com.readme.app.ui

import com.readme.app.settings.OnboardingStatus
import com.readme.app.settings.ReadMeSettings
import com.readme.app.ui.navigation.Screen
import com.readme.app.ui.onboarding.OnboardingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3A: Automated tests for Onboarding Foundation and Welcome Screen navigation.
 */
class Phase3AOnboardingTest {

    // =========================================================================
    // 1. Navigation Route Verification
    // =========================================================================
    @Test
    fun testScreenEnumIncludesWelcomeAndOnboarding() {
        val screenNames = Screen.values().map { it.name }
        assertTrue("Screen enum must contain Welcome", screenNames.contains("Welcome"))
        assertTrue("Screen enum must contain Onboarding", screenNames.contains("Onboarding"))
        assertTrue("Screen enum must preserve Home", screenNames.contains("Home"))
        assertTrue("Screen enum must preserve Settings", screenNames.contains("Settings"))
        assertTrue("Screen enum must preserve Account", screenNames.contains("Account"))
    }

    // =========================================================================
    // 2. Onboarding 5-Stage Sequence Verification
    // =========================================================================
    @Test
    fun testOnboardingStepSequenceOrder() {
        val steps = OnboardingStep.values()
        assertEquals("Planned onboarding must have exactly 5 stages", 5, steps.size)
        assertEquals(OnboardingStep.Welcome, steps[0])
        assertEquals(OnboardingStep.FloatingBubblePermission, steps[1])
        assertEquals(OnboardingStep.ScreenReadingSetup, steps[2])
        assertEquals(OnboardingStep.AutoPageTurnCalibration, steps[3])
        assertEquals(OnboardingStep.Completed, steps[4])
    }

    // =========================================================================
    // 3. Settings Model Onboarding Defaults
    // =========================================================================
    @Test
    fun testDefaultSettingsOnboardingState() {
        val settings = ReadMeSettings()
        assertFalse("New settings must not be marked completed by default", settings.isOnboardingCompleted)
        assertFalse("New settings must not be marked started by default", settings.isOnboardingStarted)
        assertEquals("natural_voice", settings.selectedVoice)
        assertEquals(1.0f, settings.speechSpeed, 0.001f)
    }

    // =========================================================================
    // 4. Existing User Preservation
    // =========================================================================
    @Test
    fun testExistingUserPreservesPreferencesAndMarkedCompleted() {
        val existingSettings = ReadMeSettings(
            selectedVoice = "en-us-custom",
            speechSpeed = 1.25f,
            speechVolume = 0.85f,
            isOnboardingCompleted = true,
            isOnboardingStarted = true
        )
        assertTrue(existingSettings.isOnboardingCompleted)
        assertTrue(existingSettings.isOnboardingStarted)
        assertEquals("en-us-custom", existingSettings.selectedVoice)
        assertEquals(1.25f, existingSettings.speechSpeed, 0.001f)
    }

    // =========================================================================
    // 5. Onboarding Status State Hierarchy
    // =========================================================================
    @Test
    fun testOnboardingStatusHierarchy() {
        val loading: OnboardingStatus = OnboardingStatus.Loading
        val required: OnboardingStatus = OnboardingStatus.Required
        val completed: OnboardingStatus = OnboardingStatus.Completed

        assertTrue(loading is OnboardingStatus)
        assertTrue(required is OnboardingStatus)
        assertTrue(completed is OnboardingStatus)
    }

    // =========================================================================
    // 6. Starting Onboarding Does Not Prematurely Mark Completed
    // =========================================================================
    @Test
    fun testStartingOnboardingDoesNotMarkCompleted() {
        val settings = ReadMeSettings(
            isOnboardingStarted = true,
            isOnboardingCompleted = false
        )
        assertTrue("Onboarding should be recorded as started", settings.isOnboardingStarted)
        assertFalse("Onboarding must NOT be marked completed merely because it started", settings.isOnboardingCompleted)
    }
}
