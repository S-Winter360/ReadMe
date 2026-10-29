package com.readme.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.readme.app.settings.ReadMeViewModel
import com.readme.app.ui.navigation.Screen
import com.readme.app.ui.onboarding.OnboardingScreen

@Composable
fun ReadMeApp(viewModel: ReadMeViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var currentScreen by remember(settings.isOnboardingCompleted) {
        mutableStateOf(if (settings.isOnboardingCompleted) Screen.Home else Screen.Onboarding)
    }

    if (currentScreen != Screen.Home && currentScreen != Screen.Onboarding) {
        BackHandler {
            currentScreen = Screen.Home
        }
    }

    when (currentScreen) {
        Screen.Onboarding -> OnboardingScreen(
            viewModel = viewModel,
            onFinishOnboarding = { currentScreen = Screen.Home }
        )
        Screen.Home -> ReadMeScreen(
            viewModel = viewModel,
            onNavigateToSettings = { currentScreen = Screen.Settings },
            onNavigateToHowToUse = { currentScreen = Screen.HowToUse },
            onNavigateToAbout = { currentScreen = Screen.About },
            onNavigateToAccount = { currentScreen = Screen.Account }
        )
        Screen.Settings -> SettingsScreen(
            viewModel = viewModel,
            onNavigateBack = { currentScreen = Screen.Home }
        )
        Screen.HowToUse -> HowToUseScreen(
            onNavigateBack = { currentScreen = Screen.Home },
            onReplayOnboarding = { currentScreen = Screen.Onboarding }
        )
        Screen.About -> AboutReadMeScreen(
            onNavigateBack = { currentScreen = Screen.Home }
        )
        Screen.Account -> AccountScreen(
            onNavigateBack = { currentScreen = Screen.Home }
        )
    }
}
