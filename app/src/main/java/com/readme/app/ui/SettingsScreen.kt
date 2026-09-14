package com.readme.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readme.app.settings.ReadMeViewModel
import com.readme.app.ui.components.ReadMeSliderControl
import com.readme.app.ui.components.ReadMeVoiceSelector
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ReadMeViewModel,
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val availableVoices by viewModel.availableVoices.collectAsStateWithLifecycle()

    val selectedVoice = availableVoices.find { it.id == settings.selectedVoice }
    val selectedVoiceDisplayName = selectedVoice?.displayName
        ?: if (availableVoices.isEmpty()) "No voices available" else availableVoices.firstOrNull()?.displayName ?: ""

    val pitchLabels = listOf("Low", "Mid-Low", "Mid", "Mid-High", "High")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Text(
                            text = "←",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
                .testTag("settings_screen_content"),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Voice & Speech",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            ReadMeVoiceSelector(
                selectedVoice = selectedVoiceDisplayName,
                voices = availableVoices,
                onVoiceSelected = { voice ->
                    viewModel.updateSelectedVoice(voice.id)
                }
            )

            ReadMeSliderControl(
                label = "Speed",
                value = settings.speechSpeed,
                valueString = String.format(Locale.getDefault(), "%.1f×", settings.speechSpeed),
                onValueChange = { viewModel.updateSpeechSpeed(it) },
                valueRange = 0.5f..2.0f,
                steps = 14
            )

            ReadMeSliderControl(
                label = "Pitch",
                value = settings.speechPitch * 4f,
                valueString = pitchLabels[(settings.speechPitch * 4f).toInt().coerceIn(0, 4)],
                onValueChange = { viewModel.updateSpeechPitch(it / 4f) },
                valueRange = 0f..4f,
                steps = 3
            )

            ReadMeSliderControl(
                label = "Volume",
                value = settings.speechVolume * 100f,
                valueString = "${(settings.speechVolume * 100f).toInt()}%",
                onValueChange = { viewModel.updateSpeechVolume(it / 100f) },
                valueRange = 0f..100f
            )

            Text(
                text = "Cross-App & System Controls",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            SystemBubbleToggle(
                enabled = settings.isSystemBubbleEnabled,
                onEnabledChange = { viewModel.setSystemBubbleEnabled(it) }
            )

            CrossAppTextSection(viewModel = viewModel)

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
