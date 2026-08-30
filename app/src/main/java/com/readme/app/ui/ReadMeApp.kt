package com.readme.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.readme.app.settings.ReadMeViewModel
import com.readme.app.ui.navigation.Screen

@Composable
fun ReadMeApp(viewModel: ReadMeViewModel = viewModel()) {
    var currentScreen by remember { mutableStateOf(Screen.Home) }

    if (currentScreen != Screen.Home) {
        BackHandler {
            currentScreen = Screen.Home
        }
    }

    when (currentScreen) {
        Screen.Home -> ReadMeScreen(
            viewModel = viewModel,
            onNavigateToHowToUse = { currentScreen = Screen.HowToUse },
            onNavigateToAbout = { currentScreen = Screen.About }
        )
        Screen.HowToUse -> HowToUseScreen(
            onNavigateBack = { currentScreen = Screen.Home }
        )
        Screen.About -> AboutReadMeScreen(
            onNavigateBack = { currentScreen = Screen.Home }
        )
    }
}
