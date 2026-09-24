package com.readme.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readme.app.R
import com.readme.app.accessibility.ReadMeAccessibilityService
import com.readme.app.settings.ReadMeViewModel
import com.readme.app.ui.components.ReadMeBackButton
import com.readme.app.ui.components.ReadMeSliderControl
import com.readme.app.ui.components.ReadMeVoiceSelector
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import com.readme.app.accessibility.DebugOcrCaptureInspector
import com.readme.app.accessibility.OnDeviceCrossAppOcrEngine
import com.readme.app.accessibility.OcrIsolationTestResult
import com.readme.app.accessibility.OcrIsolationTester
import com.readme.app.accessibility.ScreenOcrDiagnostics
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ReadMeViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val availableVoices by viewModel.availableVoices.collectAsStateWithLifecycle()
    val isBubbleClosedByUser by viewModel.isBubbleClosedByUser.collectAsStateWithLifecycle()

    var isAccessibilityEnabled by remember {
        mutableStateOf(ReadMeAccessibilityService.isServiceEnabled(context))
    }
    var hasOverlayPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }

    var showAccessibilityDisclosureDialog by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = ReadMeAccessibilityService.isServiceEnabled(context)
                hasOverlayPermission = Settings.canDrawOverlays(context)
                if (!hasOverlayPermission && settings.isFloatingReadmeEnabled) {
                    viewModel.setFloatingReadmeEnabled(false)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = Settings.canDrawOverlays(context)
        hasOverlayPermission = granted
        if (granted) {
            viewModel.setFloatingReadmeEnabled(true)
        } else {
            viewModel.setFloatingReadmeEnabled(false)
        }
    }

    val accessibilitySettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val enabled = ReadMeAccessibilityService.isServiceEnabled(context)
        isAccessibilityEnabled = enabled
        if (enabled) {
            viewModel.setCrossAppReadingEnabled(true)
        } else {
            viewModel.setCrossAppReadingEnabled(false)
        }
    }

    val selectedVoice = availableVoices.find { it.id == settings.selectedVoice }
    val selectedVoiceDisplayName = selectedVoice?.displayName
        ?: if (availableVoices.isEmpty()) "No voices available" else availableVoices.firstOrNull()?.displayName ?: ""

    val pitchLabels = listOf("Low", "Mid-Low", "Mid", "Mid-High", "High")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    ReadMeBackButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("settings_back_button")
                    )
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
                text = "Floating ReadMe & Cross-App Controls",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            // 1. Floating ReadMe Switch
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                text = "Floating ReadMe",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Displays a floating bubble over other apps when ReadMe is in the background.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.isFloatingReadmeEnabled,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    if (Settings.canDrawOverlays(context)) {
                                        viewModel.setFloatingReadmeEnabled(true)
                                    } else {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        overlayPermissionLauncher.launch(intent)
                                    }
                                } else {
                                    viewModel.setFloatingReadmeEnabled(false)
                                }
                            },
                            modifier = Modifier
                                .semantics {
                                    this.contentDescription = "Floating ReadMe, switch"
                                }
                                .testTag("setting_floating_readme_switch")
                        )
                    }

                    // Restore closed bubble affordance
                    if (settings.isFloatingReadmeEnabled && isBubbleClosedByUser) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Bubble closed by user",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Button(
                                onClick = { viewModel.reopenBubble() },
                                modifier = Modifier
                                    .semantics {
                                        this.contentDescription = "Show Floating ReadMe"
                                    }
                                    .testTag("setting_show_floating_readme_button")
                            ) {
                                Text("Show Floating ReadMe")
                            }
                        }
                    }
                }
            }

            // 2. Read from other apps & screens Switch
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                text = "Read from other apps & screens",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Allows ReadMe to read text from other apps using accessibility and screen OCR.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.isCrossAppReadingEnabled,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    if (ReadMeAccessibilityService.isServiceEnabled(context)) {
                                        viewModel.setCrossAppReadingEnabled(true)
                                    } else {
                                        showAccessibilityDisclosureDialog = true
                                    }
                                } else {
                                    viewModel.setCrossAppReadingEnabled(false)
                                }
                            },
                            modifier = Modifier
                                .semantics {
                                    this.contentDescription = "Read from other apps & screens, switch"
                                }
                                .testTag("setting_cross_app_reading_switch")
                        )
                    }
                }
            }

            // 3. Automatic screen advance Switch
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(
                            text = "Automatic screen advance",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Automatically move to the next page or scroll when the current screen content has been read.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.isAutoAdvanceScreenReadingEnabled,
                        onCheckedChange = { checked ->
                            viewModel.setAutoAdvanceScreenReadingEnabled(checked)
                        },
                        modifier = Modifier
                            .semantics {
                                this.contentDescription = "Automatic screen advance, switch"
                            }
                            .testTag("setting_auto_advance_switch")
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (com.readme.app.BuildConfig.DEBUG) {
                ScreenOcrDiagnosticCard()
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showAccessibilityDisclosureDialog) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDisclosureDialog = false },
            title = {
                Text(text = stringResource(id = R.string.accessibility_disclosure_title))
            },
            text = {
                Text(text = stringResource(id = R.string.accessibility_disclosure_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAccessibilityDisclosureDialog = false
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        accessibilitySettingsLauncher.launch(intent)
                    }
                ) {
                    Text(text = stringResource(id = R.string.accessibility_disclosure_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAccessibilityDisclosureDialog = false }
                ) {
                    Text(text = stringResource(id = R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun ScreenOcrDiagnosticCard() {
    val coroutineScope = rememberCoroutineScope()
    var lastRecord by remember { mutableStateOf(ScreenOcrDiagnostics.lastDiagnostic) }
    var isolationResult by remember { mutableStateOf<OcrIsolationTestResult?>(null) }
    var isRunningTest by remember { mutableStateOf(false) }

    val rawBitmap = remember(lastRecord) { DebugOcrCaptureInspector.lastRawScreenshot }
    val cropBitmap = remember(lastRecord) { DebugOcrCaptureInspector.lastCroppedBitmap }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("screen_ocr_diagnostic_card"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Screen-OCR Diagnostics (Debug Only)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            if (lastRecord == null) {
                Text(
                    text = "No OCR operations recorded yet in this session. Trigger 'Read Screen' using the floating bubble to record acquisition metrics.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val r = lastRecord!!
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Target: ${r.targetPackageName} (windowId=${r.targetWindowId}, bounds=${r.windowBounds})",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Display: ${r.displayWidth}x${r.displayHeight} | Screenshot: ${r.screenshotWidth}x${r.screenshotHeight} | Density: ${r.densityDpi} dpi",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Selection: UI=${r.selectionInUi} -> Norm=${r.normalizedSelection}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Crop: ${r.convertedCropRect} (${r.cropWidth}x${r.cropHeight}) | Scale: (${r.scaleX}, ${r.scaleY})",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "OCR Input: ${r.ocrInputWidth}x${r.ocrInputHeight} (Upscale=${r.ocrUpscaleFactor})",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Recognition: Blocks=${r.ocrBlockCount}, Lines=${r.ocrLineCount}, Elements=${r.ocrElementCount}, TextLen=${r.ocrTextLength}, Sentences=${r.ocrSentenceCount}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Result: ${r.acquisitionResultType}${if (r.errorReason != null) " (${r.errorReason})" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r.acquisitionResultType == "Success") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Bitmaps preview
                if (cropBitmap != null || rawBitmap != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        cropBitmap?.let { cb ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Cropped Bitmap (${cb.width}x${cb.height})",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Box(
                                    modifier = Modifier
                                        .size(100.dp)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                                ) {
                                    Image(
                                        bitmap = cb.asImageBitmap(),
                                        contentDescription = "Cropped Bitmap Preview",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Isolation test status
            isolationResult?.let { res ->
                val resultText = when (res) {
                    is OcrIsolationTestResult.Passed -> "Isolation Test PASSED (${res.durationMs}ms): \"${res.recognizedText}\" [Blocks=${res.blockCount}, Lines=${res.lineCount}]"
                    is OcrIsolationTestResult.Failed -> "Isolation Test FAILED: ${res.reason}"
                }
                Text(
                    text = resultText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (res is OcrIsolationTestResult.Passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        lastRecord = ScreenOcrDiagnostics.lastDiagnostic
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Refresh")
                }

                Button(
                    onClick = {
                        isRunningTest = true
                        coroutineScope.launch {
                            val engine = OnDeviceCrossAppOcrEngine()
                            try {
                                val res = OcrIsolationTester.runIsolationTest(engine)
                                isolationResult = res
                            } finally {
                                engine.close()
                                isRunningTest = false
                            }
                        }
                    },
                    enabled = !isRunningTest,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isRunningTest) "Testing..." else "Run Test")
                }

                TextButton(
                    onClick = {
                        ScreenOcrDiagnostics.clear()
                        lastRecord = null
                        isolationResult = null
                    }
                ) {
                    Text("Clear")
                }
            }
        }
    }
}
