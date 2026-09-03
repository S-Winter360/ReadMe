package com.readme.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readme.app.reading.ReadingSessionState
import com.readme.app.settings.ReadMeViewModel
import com.readme.app.speech.TtsState
import com.readme.app.ui.components.ReadMePrimaryButton
import com.readme.app.ui.components.ReadMeSecondaryButton
import com.readme.app.ui.components.ReadMeSliderControl
import com.readme.app.ui.components.ReadMeVoiceSelector
import com.readme.app.ui.pdf.PdfReaderView
import com.readme.app.ui.pdf.PdfViewerState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadMeScreen(
    viewModel: ReadMeViewModel,
    onNavigateToHowToUse: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {}
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val availableVoices by viewModel.availableVoices.collectAsStateWithLifecycle()
    val ttsState by viewModel.ttsState.collectAsStateWithLifecycle()
    val readingState by viewModel.readingState.collectAsStateWithLifecycle()
    val selectedDocumentName by viewModel.selectedDocumentName.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val pdfViewerState by viewModel.pdfViewerState.collectAsStateWithLifecycle()
    
    val selectedVoice = availableVoices.find { it.id == settings.selectedVoice }
    val selectedVoiceDisplayName = selectedVoice?.displayName 
        ?: if (availableVoices.isEmpty()) "No voices available" else availableVoices.firstOrNull()?.displayName ?: ""
    
    val isReading = readingState == ReadingSessionState.Reading || ttsState == TtsState.Speaking
    
    var menuExpanded by remember { mutableStateOf(false) }
    
    val pitchLabels = listOf("Low", "Mid-Low", "Mid", "Mid-High", "High")

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.selectDocument(uri)
        }
    }
    
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
        if (pdfViewerState is PdfViewerState.Active) {
            val activePdf = pdfViewerState as PdfViewerState.Active
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Visual PDF viewer occupying main available space
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.2f)
                        .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape = MaterialTheme.shapes.small)
                        .clip(MaterialTheme.shapes.small)
                ) {
                    activePdf.uri?.let { pdfUri ->
                        PdfReaderView(
                            uri = pdfUri,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Controls area below viewer
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (selectedDocumentName != null) {
                        Text(
                            text = "Selected: $selectedDocumentName",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }

                    if (loadError != null) {
                        Text(
                            text = loadError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }

                    ReadMeSecondaryButton(
                        text = "Open Content",
                        onClick = {
                            filePickerLauncher.launch(arrayOf("text/plain", "application/epub+zip", "application/pdf"))
                        }
                    )

                    ReadMePrimaryButton(
                        text = if (isReading) "Reading..." else "Start Reading",
                        onClick = {
                            if (isReading) {
                                viewModel.stopReading()
                            } else {
                                viewModel.startReading()
                            }
                        }
                    )

                    ReadMeVoiceSelector(
                        selectedVoice = selectedVoiceDisplayName,
                        voices = availableVoices,
                        onVoiceSelected = { voice ->
                            viewModel.updateSelectedVoice(voice.id)
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

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        } else {
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
                    selectedVoice = selectedVoiceDisplayName,
                    voices = availableVoices,
                    onVoiceSelected = { voice ->
                        viewModel.updateSelectedVoice(voice.id)
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

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (selectedDocumentName != null) {
                        Text(
                            text = "Selected: $selectedDocumentName",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }

                    if (loadError != null) {
                        Text(
                            text = loadError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }

                    ReadMeSecondaryButton(
                        text = "Open Content",
                        onClick = {
                            filePickerLauncher.launch(arrayOf("text/plain", "application/epub+zip", "application/pdf"))
                        }
                    )

                    ReadMePrimaryButton(
                        text = if (isReading) "Reading..." else "Start Reading",
                        onClick = {
                            if (isReading) {
                                viewModel.stopReading()
                            } else {
                                viewModel.startReading()
                            }
                        }
                    )
                }
            }
        }
    }
}
