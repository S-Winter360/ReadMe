package com.readme.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.readme.app.R
import com.readme.app.ui.components.ReadMePrimaryButton
import com.readme.app.ui.theme.BorderSubtle
import com.readme.app.ui.theme.CharcoalBackground
import com.readme.app.ui.theme.DarkElevatedSurface
import com.readme.app.ui.theme.TealAccent
import com.readme.app.ui.theme.TextPrimary
import com.readme.app.ui.theme.TextSecondary

/**
 * Phase 3A: Professional First-Launch Welcome Screen.
 *
 * Welcomes first-time users to ReadMe, highlights core implemented capabilities
 * (document reading, background speech with floating controls, and persistent progress),
 * and provides a clear primary action to enter the setup sequence.
 */
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("welcome_screen"),
        containerColor = CharcoalBackground,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Brand Emblem / App Logo
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(DarkElevatedSurface)
                        .testTag("welcome_logo_container"),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.welcome_logo),
                        contentDescription = "ReadMe Logo",
                        modifier = Modifier
                            .size(72.dp)
                            .testTag("welcome_logo"),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // App Title
                Text(
                    text = "ReadMe",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = TealAccent,
                    modifier = Modifier.testTag("welcome_app_title")
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Welcoming Introduction Tagline
                Text(
                    text = "Your Offline Reading Assistant",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Listen to your books and documents naturally, multitask with ease, and never lose your reading place.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Core Implemented Capabilities
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    WelcomeFeatureCard(
                        title = "Document Reading",
                        description = "Listen to PDF, EPUB, and TXT files with clear speech synthesis and real-time sentence tracking.",
                        indicatorNumber = "1",
                        testTag = "welcome_feature_documents"
                    )

                    WelcomeFeatureCard(
                        title = "Background & Floating Controls",
                        description = "Keep listening while using other apps or with your screen off, with quick floating controls.",
                        indicatorNumber = "2",
                        testTag = "welcome_feature_multitasking"
                    )

                    WelcomeFeatureCard(
                        title = "Continuous Reading Progress",
                        description = "Your exact position is automatically saved across sessions so you always resume where you left off.",
                        indicatorNumber = "3",
                        testTag = "welcome_feature_progress"
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Primary Call to Action
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ReadMePrimaryButton(
                    text = "Get Started",
                    onClick = onGetStarted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("welcome_get_started_button")
                )
            }
        }
    }
}

@Composable
private fun WelcomeFeatureCard(
    title: String,
    description: String,
    indicatorNumber: String,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag)
            .semantics {
                contentDescription = "$title: $description"
            },
        shape = RoundedCornerShape(12.dp),
        color = DarkElevatedSurface,
        border = BorderStroke(1.dp, BorderSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top
        ) {
            // High-contrast numerical indicator
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(TealAccent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = indicatorNumber,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = TealAccent
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight
                )
            }
        }
    }
}
