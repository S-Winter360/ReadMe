package com.readme.app.ui

import com.readme.app.accessibility.CrossAppGestureDispatcher
import com.readme.app.settings.ReadMeSettings
import com.readme.app.ui.navigation.Screen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFlowTest {

    @Test
    fun testOnboardingInitialStateDefaultFalse() {
        val settings = ReadMeSettings()
        assertFalse("Onboarding must not be completed by default on first launch", settings.isOnboardingCompleted)
    }

    @Test
    fun testOnboardingCompletionState() {
        val settings = ReadMeSettings(isOnboardingCompleted = true)
        assertTrue("Onboarding completed state must be true when updated", settings.isOnboardingCompleted)
    }

    @Test
    fun testScreenEnumIncludesOnboarding() {
        val screenNames = Screen.values().map { it.name }
        assertTrue("Screen enum must contain Onboarding", screenNames.contains("Onboarding"))
        assertTrue("Screen enum must contain Home", screenNames.contains("Home"))
        assertTrue("Screen enum must contain Settings", screenNames.contains("Settings"))
        assertTrue("Screen enum must contain HowToUse", screenNames.contains("HowToUse"))
        assertTrue("Screen enum must contain About", screenNames.contains("About"))
        assertTrue("Screen enum must contain Account", screenNames.contains("Account"))
    }

    @Test
    fun testCrossAppGestureDispatcherHasSwipeSupport() = runBlocking {
        val dispatcher = object : CrossAppGestureDispatcher {
            override val canDispatchGestures: Boolean = false
            override suspend fun performTap(x: Float, y: Float, durationMs: Long): Boolean = false
        }
        val result = dispatcher.performSwipe(100f, 500f, 100f, 100f, 250L)
        assertFalse("Default performSwipe implementation returns false when disabled", result)
    }

    @Test
    fun testAutoTurnPagePreferenceToggle() {
        val defaultSettings = ReadMeSettings()
        val toggledSettings = defaultSettings.copy(isAutoAdvanceScreenReadingEnabled = true)
        assertTrue("Auto turn page setting can be toggled on", toggledSettings.isAutoAdvanceScreenReadingEnabled)
    }
}
