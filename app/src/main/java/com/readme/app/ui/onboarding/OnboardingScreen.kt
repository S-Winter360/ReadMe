package com.readme.app.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readme.app.R
import com.readme.app.accessibility.ReadMeAccessibilityService
import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState
import com.readme.app.reading.ReadingSessionState
import com.readme.app.reading.service.ReadMeReadingService
import com.readme.app.settings.ReadMeViewModel
import com.readme.app.speech.TtsState
import com.readme.app.ui.overlay.SystemFloatingBubbleController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 5-screen Onboarding Flow for ReadMe.
 *
 * Implements:
 * 1. Welcome - Logo with soft teal glow, ReadMe wordmark, Title, Subtitle, 4 Benefit Cards, "Get Started →"
 * 2. Floating bubble - Illustration, Title, Subtitle, Toggle Card, "Next →"
 * 3. Screen reading - Illustration, Title, Subtitle, In-app Disclosure Dialog, Toggle Card, "Next →"
 * 4. Auto turn page - Illustration with page curl & chevrons, Title, Subtitle, Toggle Card, "Next →"
 * 5. Ready - Logo, ReadMe wordmark, Title, Subtitle, 3 Status Checkmarks, "Start Reading →"
 */
@Composable
fun OnboardingScreen(
    viewModel: ReadMeViewModel,
    onFinishOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
    isReplay: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 5 })
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isAccessibilityEnabled by remember { mutableStateOf(ReadMeAccessibilityService.isServiceEnabled(context)) }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }

    // Bubble Controller for quick demo
    val bubbleController = remember { SystemFloatingBubbleController(context.applicationContext) }

    // Re-check permissions on resume (when returning from System Settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val overlayNow = Settings.canDrawOverlays(context)
                val accessibilityNow = ReadMeAccessibilityService.isServiceEnabled(context)

                // If overlay was just granted, enable setting and show quick demo
                if (!hasOverlayPermission && overlayNow) {
                    viewModel.setFloatingReadmeEnabled(true)
                    try {
                        bubbleController.show(
                            sessionState = ActiveReadingSessionState(
                                sessionState = ReadingSessionState.Reading,
                                speechState = TtsState.Speaking
                            ),
                            activeDocumentState = ActiveDocumentState(),
                            onBubbleTap = { bubbleController.hide() },
                            onCloseBubble = { bubbleController.hide() }
                        )
                        coroutineScope.launch {
                            delay(3500)
                            bubbleController.hide()
                        }
                    } catch (_: Exception) {}
                }

                if (!isAccessibilityEnabled && accessibilityNow) {
                    viewModel.setCrossAppReadingEnabled(true)
                }

                hasOverlayPermission = overlayNow
                isAccessibilityEnabled = accessibilityNow
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                bubbleController.hide()
            } catch (_: Exception) {}
        }
    }

    // Android 13+ Notification Permission Launcher
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Continue to overlay settings regardless of notification permission result
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }

    // System Back Button Handling
    BackHandler(enabled = true) {
        if (pagerState.currentPage > 0) {
            coroutineScope.launch {
                pagerState.animateScrollToPage(pagerState.currentPage - 1)
            }
        } else {
            if (isReplay) {
                onFinishOnboarding()
            } else {
                (context as? Activity)?.finish()
            }
        }
    }

    // Accessibility Service In-App Disclosure Dialog (Google Play policy requirement)
    if (showAccessibilityDisclosure) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDisclosure = false },
            title = {
                Text(
                    text = stringResource(R.string.onboarding_reading_dialog_title),
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    color = OnboardingColors.TextLight
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.onboarding_reading_dialog_message),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = OnboardingColors.TextLight.copy(alpha = 0.9f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAccessibilityDisclosure = false
                        viewModel.setCrossAppReadingEnabled(true)
                        Toast.makeText(
                            context,
                            context.getString(R.string.onboarding_reading_accessibility_hint),
                            Toast.LENGTH_LONG
                        ).show()
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OnboardingColors.PrimaryTeal)
                ) {
                    Text(stringResource(R.string.onboarding_reading_agree), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityDisclosure = false }) {
                    Text(
                        stringResource(R.string.onboarding_reading_no_thanks),
                        color = OnboardingColors.TextMuted
                    )
                }
            },
            containerColor = OnboardingColors.CardBackground
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("onboarding_screen"),
        containerColor = OnboardingColors.Background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Top Bar: 5-dot Progress Indicator & Skip Action
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Invisible spacer or dot center balance
                Spacer(modifier = Modifier.width(48.dp))

                OnboardingProgressIndicator(
                    pageCount = 5,
                    currentPage = pagerState.currentPage,
                    modifier = Modifier.testTag("onboarding_progress_dots")
                )

                // Skip button on pages 0..3 (hidden on page 4)
                if (pagerState.currentPage < 4) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable(
                                role = Role.Button,
                                onClick = {
                                    viewModel.updateOnboardingCompleted(true)
                                    onFinishOnboarding()
                                }
                            )
                            .semantics {
                                role = Role.Button
                                contentDescription = "Skip onboarding"
                            }
                            .testTag("onboarding_skip_button"),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_skip),
                            color = OnboardingColors.TextMuted,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(48.dp))
                }
            }

            // Pager with the 5 onboarding screens
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("onboarding_pager")
            ) { page ->
                when (page) {
                    0 -> WelcomeOnboardingPage(
                        onGetStarted = {
                            coroutineScope.launch { pagerState.animateScrollToPage(1) }
                        }
                    )
                    1 -> FloatingBubbleOnboardingPage(
                        hasOverlayPermission = hasOverlayPermission,
                        onToggleClicked = {
                            if (!hasOverlayPermission) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                }
                            } else {
                                val newState = !settings.isFloatingReadmeEnabled
                                viewModel.setFloatingReadmeEnabled(newState)
                                if (newState) {
                                    try {
                                        bubbleController.show(
                                            sessionState = ActiveReadingSessionState(
                                                sessionState = ReadingSessionState.Reading,
                                                speechState = TtsState.Speaking
                                            ),
                                            activeDocumentState = ActiveDocumentState(),
                                            onBubbleTap = { bubbleController.hide() },
                                            onCloseBubble = { bubbleController.hide() }
                                        )
                                        coroutineScope.launch {
                                            delay(3500)
                                            bubbleController.hide()
                                        }
                                    } catch (_: Exception) {}
                                } else {
                                    bubbleController.hide()
                                }
                            }
                        },
                        onNext = {
                            coroutineScope.launch { pagerState.animateScrollToPage(2) }
                        }
                    )
                    2 -> ScreenReadingOnboardingPage(
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        onToggleClicked = {
                            if (!isAccessibilityEnabled) {
                                showAccessibilityDisclosure = true
                            } else {
                                viewModel.setCrossAppReadingEnabled(!settings.isCrossAppReadingEnabled)
                            }
                        },
                        onNext = {
                            coroutineScope.launch { pagerState.animateScrollToPage(3) }
                        }
                    )
                    3 -> AutoTurnPageOnboardingPage(
                        isAutoTurnEnabled = settings.isAutoAdvanceScreenReadingEnabled,
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        onToggleClicked = {
                            viewModel.setAutoAdvanceScreenReadingEnabled(!settings.isAutoAdvanceScreenReadingEnabled)
                        },
                        onNext = {
                            coroutineScope.launch { pagerState.animateScrollToPage(4) }
                        }
                    )
                    4 -> ReadyOnboardingPage(
                        hasOverlayPermission = hasOverlayPermission,
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        isAutoTurnEnabled = settings.isAutoAdvanceScreenReadingEnabled,
                        onStartReading = {
                            viewModel.updateOnboardingCompleted(true)
                            onFinishOnboarding()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Screen 1: Welcome
 */
@Composable
private fun WelcomeOnboardingPage(
    onGetStarted: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // App Logo with soft teal glow
            Box(
                modifier = Modifier
                    .size(116.dp)
                    .drawBehind {
                        drawCircle(
                            color = OnboardingColors.GlowColor,
                            radius = size.maxDimension * 0.75f
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "ReadMe Logo",
                    modifier = Modifier
                        .size(104.dp)
                        .clip(RoundedCornerShape(26.dp))
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            ReadMeWordmark(fontSize = 38.sp)

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.onboarding_welcome_title),
                color = OnboardingColors.TextLight,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.onboarding_welcome_subtitle),
                color = OnboardingColors.TextMuted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 2x2 Grid of Benefit Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BenefitCard(
                    title = stringResource(R.string.onboarding_benefit_1_title),
                    subtitle = stringResource(R.string.onboarding_benefit_1_subtitle),
                    modifier = Modifier.weight(1f)
                )
                BenefitCard(
                    title = stringResource(R.string.onboarding_benefit_2_title),
                    subtitle = stringResource(R.string.onboarding_benefit_2_subtitle),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BenefitCard(
                    title = stringResource(R.string.onboarding_benefit_3_title),
                    subtitle = stringResource(R.string.onboarding_benefit_3_subtitle),
                    modifier = Modifier.weight(1f)
                )
                BenefitCard(
                    title = stringResource(R.string.onboarding_benefit_4_title),
                    subtitle = stringResource(R.string.onboarding_benefit_4_subtitle),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        OnboardingPrimaryButton(
            text = "${stringResource(R.string.onboarding_get_started)} →",
            onClick = onGetStarted,
            testTag = "onboarding_get_started_button"
        )
    }
}

@Composable
private fun BenefitCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(92.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(OnboardingColors.CardBackground)
            .border(1.dp, OnboardingColors.CardBorder, RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(OnboardingColors.PrimaryTeal)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    color = OnboardingColors.TextLight,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                color = OnboardingColors.TextMuted,
                fontSize = 11.5.sp,
                lineHeight = 16.sp
            )
        }
    }
}

/**
 * Screen 2: Floating Bubble
 */
@Composable
private fun FloatingBubbleOnboardingPage(
    hasOverlayPermission: Boolean,
    onToggleClicked: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            OnboardingPhoneIllustration(type = OnboardingIllustrationType.FloatingBubble)

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.onboarding_bubble_title),
                color = OnboardingColors.TextLight,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.onboarding_bubble_subtitle),
                color = OnboardingColors.TextMuted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 14.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            OnboardingToggleCard(
                iconPainter = painterResource(id = R.drawable.ic_bubble_card),
                title = stringResource(R.string.onboarding_bubble_toggle_title),
                subtitle = stringResource(R.string.onboarding_bubble_toggle_subtitle),
                checked = hasOverlayPermission,
                onCheckedChange = { onToggleClicked() },
                testTag = "onboarding_bubble_toggle"
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        OnboardingPrimaryButton(
            text = "${stringResource(R.string.onboarding_next)} →",
            onClick = onNext,
            testTag = "onboarding_bubble_next_button"
        )
    }
}

/**
 * Screen 3: Screen Reading
 */
@Composable
private fun ScreenReadingOnboardingPage(
    isAccessibilityEnabled: Boolean,
    onToggleClicked: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            OnboardingPhoneIllustration(type = OnboardingIllustrationType.ScreenReading)

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.onboarding_reading_title),
                color = OnboardingColors.TextLight,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.onboarding_reading_subtitle),
                color = OnboardingColors.TextMuted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 14.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            OnboardingToggleCard(
                iconPainter = painterResource(id = R.drawable.ic_accessibility_card),
                title = stringResource(R.string.onboarding_reading_toggle_title),
                subtitle = stringResource(R.string.onboarding_reading_toggle_subtitle),
                checked = isAccessibilityEnabled,
                onCheckedChange = { onToggleClicked() },
                testTag = "onboarding_reading_toggle"
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        OnboardingPrimaryButton(
            text = "${stringResource(R.string.onboarding_next)} →",
            onClick = onNext,
            testTag = "onboarding_reading_next_button"
        )
    }
}

/**
 * Screen 4: Auto Turn Page
 */
@Composable
private fun AutoTurnPageOnboardingPage(
    isAutoTurnEnabled: Boolean,
    isAccessibilityEnabled: Boolean,
    onToggleClicked: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            OnboardingPhoneIllustration(type = OnboardingIllustrationType.AutoTurnPage)

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.onboarding_autonav_title),
                color = OnboardingColors.TextLight,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.onboarding_autonav_subtitle),
                color = OnboardingColors.TextMuted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 14.dp)
            )

            if (!isAccessibilityEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.onboarding_autonav_requires_accessibility),
                    color = OnboardingColors.WordmarkTeal,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            OnboardingToggleCard(
                iconPainter = painterResource(id = R.drawable.ic_auto_turn_card),
                title = stringResource(R.string.onboarding_autonav_toggle_title),
                subtitle = stringResource(R.string.onboarding_autonav_toggle_subtitle),
                checked = isAutoTurnEnabled,
                onCheckedChange = { onToggleClicked() },
                testTag = "onboarding_autonav_toggle"
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        OnboardingPrimaryButton(
            text = "${stringResource(R.string.onboarding_next)} →",
            onClick = onNext,
            testTag = "onboarding_autonav_next_button"
        )
    }
}

/**
 * Screen 5: Ready
 */
@Composable
private fun ReadyOnboardingPage(
    hasOverlayPermission: Boolean,
    isAccessibilityEnabled: Boolean,
    isAutoTurnEnabled: Boolean,
    onStartReading: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // App Logo with soft teal glow
            Box(
                modifier = Modifier
                    .size(116.dp)
                    .drawBehind {
                        drawCircle(
                            color = OnboardingColors.GlowColor,
                            radius = size.maxDimension * 0.75f
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "ReadMe Logo",
                    modifier = Modifier
                        .size(104.dp)
                        .clip(RoundedCornerShape(26.dp))
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            ReadMeWordmark(fontSize = 38.sp)

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.onboarding_ready_title),
                color = OnboardingColors.TextLight,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.onboarding_ready_subtitle),
                color = OnboardingColors.TextMuted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 14.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 3 Status Items
            OnboardingStatusItem(
                title = if (hasOverlayPermission) {
                    stringResource(R.string.onboarding_ready_bubble_ready)
                } else {
                    stringResource(R.string.onboarding_ready_bubble_not_enabled)
                },
                isReady = hasOverlayPermission,
                testTag = "ready_item_bubble"
            )

            Spacer(modifier = Modifier.height(12.dp))

            OnboardingStatusItem(
                title = if (isAccessibilityEnabled) {
                    stringResource(R.string.onboarding_ready_screen_reading_ready)
                } else {
                    stringResource(R.string.onboarding_ready_screen_reading_not_enabled)
                },
                isReady = isAccessibilityEnabled,
                testTag = "ready_item_screen_reading"
            )

            Spacer(modifier = Modifier.height(12.dp))

            OnboardingStatusItem(
                title = if (isAutoTurnEnabled) {
                    stringResource(R.string.onboarding_ready_auto_turn_ready)
                } else {
                    stringResource(R.string.onboarding_ready_auto_turn_not_enabled)
                },
                isReady = isAutoTurnEnabled,
                testTag = "ready_item_auto_turn"
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        OnboardingPrimaryButton(
            text = "${stringResource(R.string.onboarding_start_reading)} →",
            onClick = onStartReading,
            testTag = "onboarding_start_reading_button"
        )
    }
}
