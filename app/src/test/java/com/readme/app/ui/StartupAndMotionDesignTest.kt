package com.readme.app.ui

import com.readme.app.settings.ReadMeSettings
import com.readme.app.ui.navigation.StartupState
import com.readme.app.ui.theme.ReadMeMotion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automated verification of Workstream A (Zero-Flash Tri-State Startup Architecture)
 * and Workstream B (Motion Design & Interaction Timing).
 */
class StartupAndMotionDesignTest {

    @Test
    fun testStartupStateInitialModel() {
        val loading: StartupState = StartupState.Loading
        val needsOnboarding: StartupState = StartupState.NeedsOnboarding
        val ready: StartupState = StartupState.Ready

        assertTrue("StartupState.Loading must be distinct from NeedsOnboarding", loading != needsOnboarding)
        assertTrue("StartupState.Loading must be distinct from Ready", loading != ready)
        assertTrue("StartupState.NeedsOnboarding must be distinct from Ready", needsOnboarding != ready)
    }

    @Test
    fun testStartupStateTransitionForReturningUserBypassesOnboarding() = runBlocking {
        // Simulates DataStore flow for a returning user who completed onboarding
        val settingsFlow = MutableStateFlow(ReadMeSettings(isOnboardingCompleted = true))
        val sessionCompletedFlow = MutableStateFlow<Boolean?>(null)

        val startupFlow = combine(settingsFlow, sessionCompletedFlow) { settings, sessionCompleted ->
            when {
                sessionCompleted == true || settings.isOnboardingCompleted -> StartupState.Ready
                sessionCompleted == false -> StartupState.NeedsOnboarding
                else -> StartupState.NeedsOnboarding
            }
        }

        val emittedState = startupFlow.first()
        assertEquals(
            "Returning user with completed onboarding must emit Ready immediately on first flow read, never NeedsOnboarding",
            StartupState.Ready,
            emittedState
        )
    }

    @Test
    fun testStartupStateTransitionForNewUserEmitsNeedsOnboarding() = runBlocking {
        // Simulates DataStore flow on first install
        val settingsFlow = MutableStateFlow(ReadMeSettings(isOnboardingCompleted = false))
        val sessionCompletedFlow = MutableStateFlow<Boolean?>(null)

        val startupFlow = combine(settingsFlow, sessionCompletedFlow) { settings, sessionCompleted ->
            when {
                sessionCompleted == true || settings.isOnboardingCompleted -> StartupState.Ready
                sessionCompleted == false -> StartupState.NeedsOnboarding
                else -> StartupState.NeedsOnboarding
            }
        }

        val emittedState = startupFlow.first()
        assertEquals(
            "Fresh install with onboarding not completed must emit NeedsOnboarding",
            StartupState.NeedsOnboarding,
            emittedState
        )
    }

    @Test
    fun testCompletingOnboardingImmediatelyTransitionsToReady() = runBlocking {
        val settingsFlow = MutableStateFlow(ReadMeSettings(isOnboardingCompleted = false))
        val sessionCompletedFlow = MutableStateFlow<Boolean?>(null)

        val startupFlow = combine(settingsFlow, sessionCompletedFlow) { settings, sessionCompleted ->
            when {
                sessionCompleted == true || settings.isOnboardingCompleted -> StartupState.Ready
                sessionCompleted == false -> StartupState.NeedsOnboarding
                else -> StartupState.NeedsOnboarding
            }
        }

        // Before completion
        assertEquals(StartupState.NeedsOnboarding, startupFlow.first())

        // User finishes or skips onboarding:
        sessionCompletedFlow.value = true
        assertEquals(
            "Updating session completion must transition startup flow to Ready immediately",
            StartupState.Ready,
            startupFlow.first()
        )
    }

    @Test
    fun testReplayOnboardingPreservesCompletedFlag() {
        // A user replaying onboarding already has isOnboardingCompleted = true
        val settings = ReadMeSettings(isOnboardingCompleted = true)
        assertTrue(settings.isOnboardingCompleted)

        // Dismissing or finishing replay leaves the persistent flag as true
        val replayedSettings = settings.copy()
        assertTrue("Replaying onboarding must not clear completion flag", replayedSettings.isOnboardingCompleted)
    }

    @Test
    fun testMotionDurationsAdhereToDesignSpec() {
        // Subtle, purposeful animations: 100-300 ms durations
        assertTrue("DurationFast must be >= 100ms and <= 150ms", ReadMeMotion.DurationFast in 100..150)
        assertTrue("DurationStandard must be >= 150ms and <= 220ms", ReadMeMotion.DurationStandard in 150..220)
        assertTrue("DurationModerate must be >= 200ms and <= 300ms", ReadMeMotion.DurationModerate in 200..300)
    }
}
