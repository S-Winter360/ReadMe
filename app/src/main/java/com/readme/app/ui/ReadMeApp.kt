package com.readme.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.readme.app.settings.OnboardingStatus
import com.readme.app.settings.ReadMeViewModel
import com.readme.app.ui.navigation.Screen
import com.readme.app.ui.onboarding.OnboardingScreen

@Composable
fun ReadMeApp(viewModel: ReadMeViewModel = viewModel()) {
    val onboardingStatus by viewModel.onboardingStatus.collectAsStateWithLifecycle()
    var currentScreen by remember { mutableStateOf<Screen?>(null) }

    // Resolve initial launch screen without race conditions
    LaunchedEffect(onboardingStatus) {
        if (currentScreen == null) {
            when (onboardingStatus) {
                is OnboardingStatus.Loading -> { /* awaiting persistent state */ }
                is OnboardingStatus.Required -> currentScreen = Screen.Welcome
                is OnboardingStatus.Completed -> currentScreen = Screen.Home
            }
        }
    }

    if (currentScreen == null) {
        // Prevent layout flicker while resolving preferences
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )
        return
    }

    val activeScreen = currentScreen ?: Screen.Home

    when (activeScreen) {
        Screen.Welcome -> {
            WelcomeScreen(
                onGetStarted = {
                    viewModel.startOnboarding()
                    currentScreen = Screen.Onboarding
                }
            )
        }
        Screen.Onboarding -> {
            BackHandler {
                currentScreen = Screen.Welcome
            }
            OnboardingScreen(
                onNavigateBack = {
                    currentScreen = Screen.Welcome
                }
            )
        }
        Screen.Home -> {
            ReadMeScreen(
                viewModel = viewModel,
                onNavigateToSettings = { currentScreen = Screen.Settings },
                onNavigateToHowToUse = { currentScreen = Screen.HowToUse },
                onNavigateToAbout = { currentScreen = Screen.About },
                onNavigateToAccount = { currentScreen = Screen.Account }
            )
        }
        Screen.Settings -> {
            BackHandler { currentScreen = Screen.Home }
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { currentScreen = Screen.Home }
            )
        }
        Screen.HowToUse -> {
            BackHandler { currentScreen = Screen.Home }
            HowToUseScreen(
                onNavigateBack = { currentScreen = Screen.Home }
            )
        }
        Screen.About -> {
            BackHandler { currentScreen = Screen.Home }
            AboutReadMeScreen(
                onNavigateBack = { currentScreen = Screen.Home }
            )
        }
        Screen.Account -> {
            BackHandler { currentScreen = Screen.Home }
            AccountScreen(
                onNavigateBack = { currentScreen = Screen.Home }
            )
        }
    }
}
