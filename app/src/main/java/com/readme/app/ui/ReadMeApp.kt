package com.readme.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.readme.app.settings.ReadMeViewModel
import com.readme.app.ui.components.ReadMeSplashScreen
import com.readme.app.ui.navigation.Screen
import com.readme.app.ui.navigation.StartupState
import com.readme.app.ui.onboarding.OnboardingScreen
import com.readme.app.ui.theme.LocalReducedMotion
import com.readme.app.ui.theme.ReadMeMotion
import com.readme.app.ui.theme.readMeScreenTransition
import com.readme.app.ui.theme.rememberReducedMotion

@Composable
fun ReadMeApp(viewModel: ReadMeViewModel = viewModel()) {
    val startupState by viewModel.startupState.collectAsStateWithLifecycle()
    val isReducedMotion = rememberReducedMotion()

    var currentScreen by remember { mutableStateOf(Screen.Home) }

    if (startupState is StartupState.Ready && currentScreen != Screen.Home) {
        BackHandler {
            currentScreen = Screen.Home
        }
    }

    CompositionLocalProvider(LocalReducedMotion provides isReducedMotion) {
        AnimatedContent(
            targetState = startupState,
            transitionSpec = {
                if (isReducedMotion) {
                    fadeIn(animationSpec = tween(ReadMeMotion.DurationFast)) togetherWith
                        fadeOut(animationSpec = tween(ReadMeMotion.DurationFast))
                } else {
                    fadeIn(
                        animationSpec = tween(
                            durationMillis = ReadMeMotion.DurationModerate,
                            easing = ReadMeMotion.EasingStandard
                        )
                    ) togetherWith fadeOut(
                        animationSpec = tween(
                            durationMillis = ReadMeMotion.DurationFast,
                            easing = ReadMeMotion.EasingStandard
                        )
                    )
                }
            },
            label = "StartupTransition"
        ) { state ->
            when (state) {
                StartupState.Loading -> {
                    ReadMeSplashScreen()
                }
                StartupState.NeedsOnboarding -> {
                    OnboardingScreen(
                        viewModel = viewModel,
                        isReplay = false,
                        onFinishOnboarding = {
                            viewModel.updateOnboardingCompleted(true)
                            currentScreen = Screen.Home
                        }
                    )
                }
                StartupState.Ready -> {
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = readMeScreenTransition(isReducedMotion),
                        label = "MainScreenTransition"
                    ) { targetScreen ->
                        when (targetScreen) {
                            Screen.Onboarding -> {
                                OnboardingScreen(
                                    viewModel = viewModel,
                                    isReplay = true,
                                    onFinishOnboarding = {
                                        currentScreen = Screen.Home
                                    }
                                )
                            }
                            Screen.Home -> ReadMeScreen(
                                viewModel = viewModel,
                                onNavigateToSettings = { currentScreen = Screen.Settings },
                                onNavigateToHowToUse = { currentScreen = Screen.HowToUse },
                                onNavigateToAbout = { currentScreen = Screen.About },
                                onNavigateToAccount = { currentScreen = Screen.Account },
                                onNavigateToOnboarding = { currentScreen = Screen.Onboarding }
                            )
                            Screen.Settings -> SettingsScreen(
                                viewModel = viewModel,
                                onNavigateBack = { currentScreen = Screen.Home },
                                onNavigateToOnboarding = { currentScreen = Screen.Onboarding }
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
                }
            }
        }
    }
}
