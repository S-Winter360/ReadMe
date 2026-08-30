package com.readme.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.readme.app.ui.components.ReadMePrimaryButton
import com.readme.app.ui.components.ReadMeSliderControl
import com.readme.app.ui.components.ReadMeVoiceSelector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readme.app.settings.ReadMeViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadMeScreen(
    viewModel: ReadMeViewModel,
    onNavigateToHowToUse: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {}
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    
    val voiceMap = listOf(
        "natural_voice" to "Natural Voice",
        "clear_voice" to "Clear Voice",
        "soft_voice" to "Soft Voice",
        "deep_voice" to "Deep Voice"
    )
    
    var isReading by remember { mutableStateOf(false) }
    
    var menuExpanded by remember { mutableStateOf(false) }
    
    val pitchLabels = listOf("Low", "Mid-Low", "Mid", "Mid-High", "High")
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ReadMe", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Text("☰", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            DropdownMenuItem(
                                text = { Text("How to Use", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = { 
                                    onNavigateToHowToUse()
                                    menuExpanded = false 
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("About ReadMe", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = { 
                                    onNavigateToAbout()
                                    menuExpanded = false 
                                }
                            )
                        }
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            ReadMeVoiceSelector(
                selectedVoice = voiceMap.find { it.first == settings.selectedVoice }?.second ?: "Natural Voice",
                voices = voiceMap.map { it.second },
                onVoiceSelected = { voiceName ->
                    val voiceId = voiceMap.find { it.second == voiceName }?.first ?: "natural_voice"
                    viewModel.updateSelectedVoice(voiceId)
                }
            )

            ReadMeSliderControl(
                label = "Volume",
                value = settings.speechVolume * 100f,
                valueString = "${(settings.speechVolume * 100f).toInt()}%",
                onValueChange = { viewModel.updateSpeechVolume(it / 100f) },
                valueRange = 0f..100f
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
            
            Spacer(modifier = Modifier.weight(1f, fill = false))

            ReadMePrimaryButton(
                text = if (isReading) "Ready to read..." else "Start Reading",
                onClick = { isReading = !isReading },
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}

