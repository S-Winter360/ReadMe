package com.readme.app.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.readme.app.R
import com.readme.app.ui.components.ReadMeBackButton
import com.readme.app.ui.theme.BorderSubtle
import com.readme.app.ui.theme.CharcoalBackground
import com.readme.app.ui.theme.DarkElevatedSurface
import com.readme.app.ui.theme.TealAccent
import com.readme.app.ui.theme.TextPrimary
import com.readme.app.ui.theme.TextSecondary

/**
 * Phase 3A: Minimal Onboarding Host & Route Foundation.
 *
 * Establishes the onboarding container route for the planned sequence:
 * 1. Welcome (Phase 3A)
 * 2. Floating-bubble permission setup (Phase 3B)
 * 3. Screen-reading setup (Phase 3C)
 * 4. Auto-page-turn calibration (Phase 3D)
 * 5. Completion (Phase 3E)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    currentStep: OnboardingStep = OnboardingStep.FloatingBubblePermission
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("onboarding_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Quick Setup",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    ReadMeBackButton(
                        onClick = onNavigateBack,
                        contentDescription = "Back to Welcome",
                        modifier = Modifier.testTag("onboarding_back_button")
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CharcoalBackground,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary
                )
            )
        },
        containerColor = CharcoalBackground,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Step header badge
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = TealAccent.copy(alpha = 0.15f),
                modifier = Modifier.testTag("onboarding_step_badge")
            ) {
                Text(
                    text = "SETUP FOUNDATION",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = TealAccent,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            Text(
                text = "Configure Your Reading Experience",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Text(
                text = "ReadMe will guide you through permissions and reader preferences so you can enjoy uninterrupted background reading.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Sequence roadmap overview card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = DarkElevatedSurface,
                border = BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_overview_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Planned Setup Sequence",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    OnboardingStepPreviewRow(
                        stepNumber = "1",
                        title = "Floating Bubble Permission",
                        subtitle = "Display quick reading controls over other apps",
                        isActive = currentStep == OnboardingStep.FloatingBubblePermission
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OnboardingStepPreviewRow(
                        stepNumber = "2",
                        title = "Screen Reading Setup",
                        subtitle = "Enable assistive text detection across documents",
                        isActive = currentStep == OnboardingStep.ScreenReadingSetup
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OnboardingStepPreviewRow(
                        stepNumber = "3",
                        title = "Auto-Page-Turn Calibration",
                        subtitle = "Calibrate automatic page turning for your reader apps",
                        isActive = currentStep == OnboardingStep.AutoPageTurnCalibration
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingStepPreviewRow(
    stepNumber: String,
    title: String,
    subtitle: String,
    isActive: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Step $stepNumber: $title. $subtitle"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (isActive) TealAccent else DarkElevatedSurface,
            border = BorderStroke(1.dp, if (isActive) TealAccent else BorderSubtle),
            modifier = Modifier.size(24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = stepNumber,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive) CharcoalBackground else TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isActive) TextPrimary else TextSecondary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary.copy(alpha = 0.8f)
            )
        }
    }
}
