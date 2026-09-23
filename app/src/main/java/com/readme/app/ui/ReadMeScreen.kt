package com.readme.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readme.app.R
import com.readme.app.accessibility.CrossAppAcquisitionMode
import com.readme.app.accessibility.ReadMeAccessibilityService
import com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult
import com.readme.app.reading.progress.SavedProgressState
import com.readme.app.settings.ReadMeViewModel
import com.readme.app.ui.components.ReadMePrimaryButton
import com.readme.app.ui.components.ReadMeSecondaryButton
import com.readme.app.ui.components.ReadingWaveVisualizer
import com.readme.app.ui.pdf.PdfReaderView
import com.readme.app.ui.pdf.PdfViewerState
import com.readme.app.ui.theme.DarkElevatedSurface
import com.readme.app.ui.theme.DarkSurface
import com.readme.app.ui.theme.TealAccent
import com.readme.app.ui.theme.TextPrimary
import com.readme.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadMeScreen(
    viewModel: ReadMeViewModel,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToHowToUse: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {}
) {
    val pdfViewerState by viewModel.pdfViewerState.collectAsStateWithLifecycle()
    val pdfViewportState by viewModel.pdfViewportState.collectAsStateWithLifecycle()
    val activeDocumentState by viewModel.activeDocumentState.collectAsStateWithLifecycle()
    val readingSessionState by viewModel.readingSessionState.collectAsStateWithLifecycle()
    val savedProgressState by viewModel.savedProgressState.collectAsStateWithLifecycle()
    val currentSegment by viewModel.currentSegment.collectAsStateWithLifecycle()
    val selectedDocumentName by viewModel.selectedDocumentName.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()

    val isReading = readingSessionState.isReading

    val primaryButtonText = when {
        activeDocumentState.isLoading -> "Loading..."
        isReading -> "Pause Reading"
        savedProgressState is SavedProgressState.Resumable -> "Resume Reading"
        savedProgressState is SavedProgressState.Completed -> "Restart Reading"
        else -> "Start Reading"
    }

    var menuExpanded by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        com.readme.app.reading.service.ReadMeReadingSessionRuntime.getInstance(context.applicationContext).setDocumentPickerActive(false)
        if (uri != null) {
            viewModel.selectDocument(uri)
        }
    }
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
                navigationIcon = {
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("menu_button")
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_menu_hamburger),
                                    contentDescription = "Menu",
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_settings),
                                        contentDescription = "Settings Icon",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                text = { Text("Settings", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = {
                                    menuExpanded = false
                                    onNavigateToSettings()
                                }
                            )
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_help),
                                        contentDescription = "How to Use Icon",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                text = { Text("How to Use", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = {
                                    menuExpanded = false
                                    onNavigateToHowToUse()
                                }
                            )
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_info),
                                        contentDescription = "About ReadMe Icon",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                text = { Text("About ReadMe", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = {
                                    menuExpanded = false
                                    onNavigateToAbout()
                                }
                            )
                        }
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "ReadMe",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TealAccent
                        )
                    }
                },
                actions = {},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (pdfViewerState is PdfViewerState.Active) {
                val activePdf = pdfViewerState as PdfViewerState.Active
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Visual PDF viewer occupying main available space
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1.3f)
                            .background(DarkSurface, shape = RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        activePdf.uri?.let { pdfUri ->
                            PdfReaderView(
                                uri = pdfUri,
                                modifier = Modifier.fillMaxSize(),
                                currentSegment = currentSegment,
                                isReading = isReading,
                                onViewportChanged = { viewportState ->
                                    viewModel.onPdfViewportChanged(viewportState)
                                },
                                onNavigatorReady = { navigator ->
                                    viewModel.setPdfPageNavigator(navigator)
                                }
                            )
                        }
                    }

                    // Reading controls area below viewer
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.9f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val docTitle = activeDocumentState.displayName.ifBlank {
                            selectedDocumentName ?: "PDF Document"
                        }
                        Text(
                            text = docTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (loadError != null) {
                            Text(
                                text = loadError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }

                        // Primary Action
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

                        // Secondary actions row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                ReadMeSecondaryButton(
                                    text = "Open File",
                                    onClick = {
                                        com.readme.app.reading.service.ReadMeReadingSessionRuntime.getInstance(context.applicationContext).setDocumentPickerActive(true)
                                        filePickerLauncher.launch(
                                            arrayOf("text/plain", "application/epub+zip", "application/pdf")
                                        )
                                    }
                                )
                            }
                            if (pdfViewportState.visiblePagesCount > 0) {
                                Box(modifier = Modifier.weight(1f)) {
                                    ReadMeSecondaryButton(
                                        text = "Read from here",
                                        onClick = {
                                            viewModel.reconcilePdfReadingPosition()
                                        }
                                    )
                                }
                            }
                        }

                        if (activeDocumentState.isEphemeral && activeDocumentState.hasSuspendedPrimary) {
                            ReadMeSecondaryButton(
                                text = "Return to Document",
                                onClick = {
                                    viewModel.returnToPrimaryDocument()
                                }
                            )
                        }

                        if (savedProgressState is SavedProgressState.Resumable && !isReading) {
                            val progress = (savedProgressState as SavedProgressState.Resumable).progress
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Sentence ${progress.segmentIndex + 1} saved",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                TextButton(onClick = { onRestartReading() }) {
                                    Text(
                                        text = "Start from beginning",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TealAccent
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Non-PDF Document (TXT, EPUB, Screen OCR, or Empty)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // Reading Wave Visualization for non-rendered content
                    ReadingWaveVisualizer(
                        isReading = isReading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    )

                    // Current Document Card
                    val hasDoc = activeDocumentState.hasActiveDocument
                    val title = if (activeDocumentState.isEphemeral) {
                        activeDocumentState.sourceAppLabel ?: activeDocumentState.displayName
                    } else {
                        activeDocumentState.displayName.ifBlank {
                            selectedDocumentName ?: "No Document Open"
                        }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("current_document_card"),
                        colors = CardDefaults.cardColors(containerColor = DarkElevatedSurface),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isReading) TealAccent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (activeDocumentState.isEphemeral) "CROSS-APP READING" else "ACTIVE DOCUMENT",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeDocumentState.isEphemeral) TealAccent else TextSecondary
                                )
                                if (isReading) {
                                    Text(
                                        text = "● READING",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = TealAccent
                                    )
                                }
                            }

                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            // Active sentence focus preview
                            if (isReading && currentSegment != null && currentSegment?.text?.isNotBlank() == true) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                        .background(
                                            TealAccent.copy(alpha = 0.08f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .border(
                                            1.dp,
                                            TealAccent.copy(alpha = 0.25f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = "\"${currentSegment?.text ?: ""}\"",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontStyle = FontStyle.Italic,
                                        color = TextPrimary,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    if (loadError != null) {
                        Text(
                            text = loadError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }

                    // Reading action
                    ReadMePrimaryButton(
                        text = primaryButtonText,
                        enabled = !activeDocumentState.isLoading && (isReading || hasDoc),
                        onClick = {
                            if (isReading) {
                                viewModel.stopReading()
                            } else {
                                onStartReading()
                            }
                        }
                    )

                    // Secondary actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            ReadMeSecondaryButton(
                                text = "Open Document",
                                onClick = {
                                    com.readme.app.reading.service.ReadMeReadingSessionRuntime.getInstance(context.applicationContext).setDocumentPickerActive(true)
                                    filePickerLauncher.launch(
                                        arrayOf("text/plain", "application/epub+zip", "application/pdf")
                                    )
                                }
                            )
                        }
                    }

                    if (activeDocumentState.isEphemeral && activeDocumentState.hasSuspendedPrimary) {
                        ReadMeSecondaryButton(
                            text = "Return to ReadMe",
                            onClick = {
                                viewModel.returnToPrimaryDocument()
                            }
                        )
                    }

                    if (savedProgressState is SavedProgressState.Resumable && !isReading) {
                        val progress = (savedProgressState as SavedProgressState.Resumable).progress
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Sentence ${progress.segmentIndex + 1} saved",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = { onRestartReading() }) {
                                Text(
                                    text = "Start from beginning",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TealAccent
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
fun SystemBubbleToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            onEnabledChange(true)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Enable Floating ReadMe",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Switch(
            checked = enabled,
            onCheckedChange = { checked ->
                if (checked) {
                    if (Settings.canDrawOverlays(context)) {
                        onEnabledChange(true)
                    } else {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
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
        Row(
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
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        accessibilitySettingsLauncher.launch(intent)
                    }
                }
            )
        }

        if (isServiceEnabled) {
            Row(
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
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        accessibilitySettingsLauncher.launch(intent)
                    }
                ) {
                    Text(text = stringResource(id = R.string.accessibility_disclosure_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDisclosureDialog = false }
                ) {
                    Text(text = stringResource(id = R.string.cancel))
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
                        statusFeedback = "Consent granted. Tap Read Screen again to capture."
                    }
                ) {
                    Text(text = stringResource(id = R.string.screen_ocr_disclosure_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showScreenOcrDisclosureDialog = false }
                ) {
                    Text(text = stringResource(id = R.string.cancel))
                }
            }
        )
    }
}
