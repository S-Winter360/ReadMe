package com.readme.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readme.app.R
import com.readme.app.ui.theme.CharcoalBackground
import com.readme.app.ui.theme.LocalReducedMotion
import com.readme.app.ui.theme.TealAccent
import com.readme.app.ui.theme.TextPrimary
import com.readme.app.ui.theme.TextSecondary

/**
 * Clean, branded splash screen displayed during [com.readme.app.ui.navigation.StartupState.Loading].
 *
 * Seamlessly matches the window background and system branding, guaranteeing that returning users
 * never experience an onboarding frame or flicker while preferences are loaded.
 */
@Composable
fun ReadMeSplashScreen(
    modifier: Modifier = Modifier
) {
    val isReducedMotion = LocalReducedMotion.current
    val infiniteTransition = rememberInfiniteTransition(label = "SplashGlowPulse")
    val glowAlpha by if (isReducedMotion) {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0.18f) }
    } else {
        infiniteTransition.animateFloat(
            initialValue = 0.12f,
            targetValue = 0.24f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1000),
                repeatMode = RepeatMode.Reverse
            ),
            label = "GlowAlpha"
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CharcoalBackground)
            .statusBarsPadding()
            .testTag("readme_splash_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Logo with soft teal glow
            Box(
                modifier = Modifier
                    .size(124.dp)
                    .drawBehind {
                        drawCircle(
                            color = TealAccent.copy(alpha = glowAlpha),
                            radius = size.maxDimension * 0.72f
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier
                        .size(108.dp)
                        .clip(RoundedCornerShape(26.dp))
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.app_name),
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Screen & Document Reader",
                color = TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal
            )
        }
    }
}
