package com.readme.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.readme.app.R
import com.readme.app.ui.theme.TealAccent

/**
 * Visualizer that renders the ReadMe logo as an animated reading wave for non-rendered content.
 * When [isReading] is true, pulsating waves radiate outward from the logo in rhythmic synchronization.
 */
@Composable
fun ReadingWaveVisualizer(
    isReading: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "reading_wave")

    val waveScale1 by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_scale_1"
    )

    val waveAlpha1 by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_alpha_1"
    )

    val waveScale2 by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, delayMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_scale_2"
    )

    val waveAlpha2 by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, delayMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_alpha_2"
    )

    Box(
        modifier = modifier.testTag("reading_wave_visualizer"),
        contentAlignment = Alignment.Center
    ) {
        if (isReading) {
            Canvas(modifier = Modifier.size(220.dp)) {
                val radius = size.minDimension / 2f
                // Wave 1
                drawCircle(
                    color = TealAccent.copy(alpha = waveAlpha1),
                    radius = radius * waveScale1 * 0.7f,
                    style = Stroke(width = 4.dp.toPx())
                )
                // Wave 2
                drawCircle(
                    color = TealAccent.copy(alpha = waveAlpha2),
                    radius = radius * waveScale2 * 0.7f,
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }

        Image(
            painter = painterResource(id = R.drawable.welcome_logo),
            contentDescription = "ReadMe Logo",
            modifier = Modifier
                .height(180.dp)
                .padding(16.dp),
            contentScale = ContentScale.Fit
        )
    }
}
