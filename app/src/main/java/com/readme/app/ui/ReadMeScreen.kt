package com.readme.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readme.app.R
import com.readme.app.accessibility.CrossAppAcquisitionMode
import com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult
import com.readme.app.accessibility.ReadMeAccessibilityService
import com.readme.app.reading.ReadingSessionState
import kotlinx.coroutines.launch
import com.readme.app.reading.progress.SavedProgressState
import com.readme.app.settings.ReadMeViewModel
import com.readme.app.speech.TtsState
import com.readme.app.ui.components.ReadMePrimaryButton
import com.readme.app.ui.components.ReadMeSecondaryButton
import com.readme.app.ui.components.ReadMeSliderControl
import com.readme.app.ui.components.ReadMeVoiceSelector
import com.readme.app.ui.components.ReadMeFloatingBubble
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
    val pdfViewportState by viewModel.pdfViewportState.collectAsStateWithLifecycle()
    val activeDocumentState by viewModel.activeDocumentState.collectAsStateWithLifecycle()
    val readingSessionState by viewModel.readingSessionState.collectAsStateWithLifecycle()
    val savedProgressState by viewModel.savedProgressState.collectAsStateWithLifecycle()
    
    val selectedVoice = availableVoices.find { it.id == settings.selectedVoice }
    val selectedVoiceDisplayName = selectedVoice?.displayName 
        ?: if (availableVoices.isEmpty()) "No voices available" else availableVoices.firstOrNull()?.displayName ?: ""
    
    val isReading = readingSessionState.isReading
    
    val primaryButtonText = when {
        activeDocumentState.isLoading -> "Loading..."
        isReading -> "Reading..."
        savedProgressState is SavedProgressState.Resumable -> "Resume Reading"
        savedProgressState is SavedProgressState.Completed -> "Restart Reading"
        else -> "Start Reading"
    }
    
    var menuExpanded by remember { mutableStateOf(false) }
    
    val pitchLabels = listOf("Low", "Mid-Low", "Mid", "Mid-High", "High")

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.selectDocument(uri)
        }
    }

    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        if (activeDocumentState.hasActiveDocument) {
            viewModel.startReading()
        }
    }

    val onStartReading = {
        if (activeDocumentState.hasActiveDocument) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.startReading()
            }
        }
    }

    val onRestartReading = {
        if (activeDocumentState.hasActiveDocument) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.restartReadingFromBeginning()
            }
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
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (pdfViewerState is PdfViewerState.Active) {
                val activePdf = pdfViewerState as PdfViewerState.Active
                Column(
                    modifier = Modifier
                        .fillMaxSize()
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
                            modifier = Modifier.fillMaxSize(),
                            onViewportChanged = { viewportState ->
                                viewModel.onPdfViewportChanged(viewportState)
                            },
                            onNavigatorReady = { navigator ->
                                viewModel.setPdfPageNavigator(navigator)
                            }
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
                    if (activeDocumentState.isLoading) {
                        Text(
                            text = "Loading content...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    } else if (activeDocumentState.isEphemeral) {
                        val appDesc = activeDocumentState.sourceAppLabel ?: activeDocumentState.displayName
                        Text(
                            text = "Reading from: $appDesc",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    } else if (selectedDocumentName != null) {
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

                    if (activeDocumentState.isEphemeral && activeDocumentState.hasSuspendedPrimary) {
                        ReadMeSecondaryButton(
                            text = "Return to ReadMe",
                            onClick = {
                                viewModel.returnToPrimaryDocument()
                            }
                        )
                    }
                    
                    if (pdfViewportState.visiblePagesCount > 0) {
                        ReadMeSecondaryButton(
                            text = "Read from here",
                            onClick = {
                                viewModel.reconcilePdfReadingPosition()
                            }
                        )
                    }

                    ReadMePrimaryButton(
                        text = primaryButtonText,
                        enabled = !activeDocumentState.isLoading,
                        onClick = {
                            if (isReading) {
                                viewModel.stopReading()
                            } else {
                                onStartReading()
                            }
                        }
                    )

                    if (savedProgressState is SavedProgressState.Resumable && !isReading) {
                        val progress = (savedProgressState as SavedProgressState.Resumable).progress
                        Text(
                            text = "Saved progress: sentence ${progress.segmentIndex + 1}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        TextButton(
                            onClick = { onRestartReading() }
                        ) {
                            Text(
                                text = "Start from beginning",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

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

                    SystemBubbleToggle(
                        enabled = settings.isSystemBubbleEnabled,
                        onEnabledChange = { viewModel.setSystemBubbleEnabled(it) }
                    )
                    CrossAppTextSection(viewModel = viewModel)
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                if (selectedDocumentName == null) {
                    Image(
                        painter = painterResource(id = R.drawable.welcome_logo),
                        contentDescription = "ReadMe Welcome Logo",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(top = 24.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                }

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
                
                SystemBubbleToggle(
                    enabled = settings.isSystemBubbleEnabled,
                    onEnabledChange = { viewModel.setSystemBubbleEnabled(it) }
                )
                CrossAppTextSection(viewModel = viewModel)
                Spacer(modifier = Modifier.weight(1f, fill = false))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (activeDocumentState.isLoading) {
                        Text(
                            text = "Loading content...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    } else if (activeDocumentState.isEphemeral) {
                        val appDesc = activeDocumentState.sourceAppLabel ?: activeDocumentState.displayName
                        Text(
                            text = "Reading from: $appDesc",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    } else if (selectedDocumentName != null) {
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

                    if (activeDocumentState.isEphemeral && activeDocumentState.hasSuspendedPrimary) {
                        ReadMeSecondaryButton(
                            text = "Return to ReadMe",
                            onClick = {
                                viewModel.returnToPrimaryDocument()
                            }
                        )
                    }

                    ReadMePrimaryButton(
                        text = primaryButtonText,
                        enabled = !activeDocumentState.isLoading && (isReading || activeDocumentState.hasActiveDocument),
                        onClick = {
                            if (isReading) {
                                viewModel.stopReading()
                            } else {
                                onStartReading()
                            }
                        }
                    )

                    if (savedProgressState is SavedProgressState.Resumable && !isReading) {
                        val progress = (savedProgressState as SavedProgressState.Resumable).progress
                        Text(
                            text = "Saved progress: sentence ${progress.segmentIndex + 1}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        TextButton(
                            onClick = { onRestartReading() }
                        ) {
                            Text(
                                text = "Start from beginning",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        ReadMeFloatingBubble(
            sessionState = readingSessionState,
            activeDocumentState = activeDocumentState,
            onToggleReading = {
                if (isReading) {
                    viewModel.stopReading()
                } else {
                    onStartReading()
                }
            }
        )
    }
}
}


@androidx.compose.runtime.Composable
fun SystemBubbleToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val overlayPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        if (android.provider.Settings.canDrawOverlays(context)) {
            onEnabledChange(true)
        }
    }

    androidx.compose.foundation.layout.Row(
        modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        androidx.compose.material3.Text(
            text = "Enable Floating ReadMe",
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
        )
        androidx.compose.material3.Switch(
            checked = enabled,
            onCheckedChange = { checked ->
                if (checked) {
                    if (android.provider.Settings.canDrawOverlays(context)) {
                        onEnabledChange(true)
                    } else {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}")
                        )
                        overlayPermissionLauncher.launch(intent)
                    }
                } else {
                    onEnabledChange(false)
                }
            }
        )
    }
}

@Composable
fun CrossAppTextSection(
    viewModel: ReadMeViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var isServiceEnabled by remember {
        mutableStateOf(ReadMeAccessibilityService.isServiceEnabled(context))
    }
    val isScreenOcrConsentGranted by viewModel.isScreenOcrConsentGranted.collectAsStateWithLifecycle()
    var showDisclosureDialog by remember { mutableStateOf(false) }
    var showScreenOcrDisclosureDialog by remember { mutableStateOf(false) }
    var statusFeedback by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isServiceEnabled = ReadMeAccessibilityService.isServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val accessibilitySettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isServiceEnabled = ReadMeAccessibilityService.isServiceEnabled(context)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.accessibility_disclosure_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (isServiceEnabled) "Service enabled & ready" else "Tap to enable external reading",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isServiceEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isServiceEnabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        showDisclosureDialog = true
                    } else {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        accessibilitySettingsLauncher.launch(intent)
                    }
                }
            )
        }

        if (isServiceEnabled) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            val result = viewModel.acquireAndReadCrossAppContent(CrossAppAcquisitionMode.ACCESSIBILITY_TEXT)
                            statusFeedback = when (result) {
                                is UnifiedCrossAppAcquisitionResult.Success -> "Reading text from app..."
                                is UnifiedCrossAppAcquisitionResult.NoTextAvailable -> "No readable text found"
                                is UnifiedCrossAppAcquisitionResult.ReadMeSelfIgnored -> "Switch to another app to read"
                                is UnifiedCrossAppAcquisitionResult.ServiceUnavailable -> "Accessibility service starting..."
                                is UnifiedCrossAppAcquisitionResult.AppSwitched -> "Cancelled (app switched)"
                                is UnifiedCrossAppAcquisitionResult.RateLimited -> "Please wait a moment before trying again"
                                is UnifiedCrossAppAcquisitionResult.UnknownError -> "Error: ${result.details}"
                                else -> "Unable to read text from this app"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(id = R.string.read_current_text_button))
                }

                Button(
                    onClick = {
                        if (!viewModel.isScreenOcrSupported) {
                            statusFeedback = "Screen reading requires Android 14+ (API 34)"
                        } else if (!isScreenOcrConsentGranted) {
                            showScreenOcrDisclosureDialog = true
                        } else {
                            statusFeedback = "Capturing screen and recognizing text..."
                            coroutineScope.launch {
                                val result = viewModel.acquireAndReadCrossAppContent(CrossAppAcquisitionMode.SCREEN_OCR)
                                statusFeedback = when (result) {
                                    is UnifiedCrossAppAcquisitionResult.Success -> "Reading text recognized from screen..."
                                    is UnifiedCrossAppAcquisitionResult.NoTextAvailable -> "No text recognized on screen"
                                    is UnifiedCrossAppAcquisitionResult.SecureWindow -> "No readable image is available from this screen"
                                    is UnifiedCrossAppAcquisitionResult.SensitiveContentBlocked -> "Screen contains sensitive or password fields"
                                    is UnifiedCrossAppAcquisitionResult.RateLimited -> "Please wait a moment before capturing again"
                                    is UnifiedCrossAppAcquisitionResult.ApiNotSupported -> "Screen reading requires Android 14+"
                                    is UnifiedCrossAppAcquisitionResult.ServiceUnavailable -> "Accessibility service starting..."
                                    is UnifiedCrossAppAcquisitionResult.ReadMeSelfIgnored -> "Switch to another app to read screen"
                                    is UnifiedCrossAppAcquisitionResult.InvalidTarget -> "No active foreground window found"
                                    is UnifiedCrossAppAcquisitionResult.AppSwitched -> "Cancelled (app switched)"
                                    is UnifiedCrossAppAcquisitionResult.UnknownError -> "Error: ${result.details}"
                                    else -> "Unable to read text from screen"
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(id = R.string.read_screen_button))
                }
            }

            if (statusFeedback != null) {
                Text(
                    text = statusFeedback ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Start
                )
            }
        }
    }

    if (showDisclosureDialog) {
        AlertDialog(
            onDismissRequest = { showDisclosureDialog = false },
            title = {
                Text(text = stringResource(id = R.string.accessibility_disclosure_title))
            },
            text = {
                Text(text = stringResource(id = R.string.accessibility_disclosure_message))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDisclosureDialog = false
                        val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        accessibilitySettingsLauncher.launch(intent)
                    }
                ) {
                    Text("Agree & Open Settings")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDisclosureDialog = false }
                ) {
                    Text("Not Now")
                }
            }
        )
    }

    if (showScreenOcrDisclosureDialog) {
        AlertDialog(
            onDismissRequest = { showScreenOcrDisclosureDialog = false },
            title = {
                Text(text = stringResource(id = R.string.screen_ocr_disclosure_title))
            },
            text = {
                Text(text = stringResource(id = R.string.screen_ocr_disclosure_message))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showScreenOcrDisclosureDialog = false
                        viewModel.setScreenOcrConsentGranted(true)
                        statusFeedback = "Capturing screen and recognizing text..."
                        coroutineScope.launch {
                            val result = viewModel.acquireAndReadCrossAppContent(CrossAppAcquisitionMode.SCREEN_OCR)
                            statusFeedback = when (result) {
                                is UnifiedCrossAppAcquisitionResult.Success -> "Reading text recognized from screen..."
                                is UnifiedCrossAppAcquisitionResult.NoTextAvailable -> "No text recognized on screen"
                                is UnifiedCrossAppAcquisitionResult.SecureWindow -> "No readable image is available from this screen"
                                is UnifiedCrossAppAcquisitionResult.SensitiveContentBlocked -> "Screen contains sensitive or password fields"
                                is UnifiedCrossAppAcquisitionResult.RateLimited -> "Please wait a moment before capturing again"
                                is UnifiedCrossAppAcquisitionResult.ApiNotSupported -> "Screen reading requires Android 14+"
                                is UnifiedCrossAppAcquisitionResult.ServiceUnavailable -> "Accessibility service starting..."
                                is UnifiedCrossAppAcquisitionResult.ReadMeSelfIgnored -> "Switch to another app to read screen"
                                is UnifiedCrossAppAcquisitionResult.InvalidTarget -> "No active foreground window found"
                                is UnifiedCrossAppAcquisitionResult.AppSwitched -> "Cancelled (app switched)"
                                is UnifiedCrossAppAcquisitionResult.UnknownError -> "Error: ${result.details}"
                                else -> "Unable to read text from screen"
                            }
                        }
                    }
                ) {
                    Text("Agree & Enable Screen Reading")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showScreenOcrDisclosureDialog = false }
                ) {
                    Text("Not Now")
                }
            }
        )
    }
}
