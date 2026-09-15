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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.readme.app.R
import com.readme.app.ui.theme.TealAccent

/**
 * Visualizer that renders the ReadMe logo as an animated reading wave for non-rendered content.
 * When [isReading] is true, gentle rhythmic harmonic waves radiate outward from behind the logo.
 * When [isReading] is false, animations are completely suspended, consuming zero CPU cycles.
 */
@Composable
fun ReadingWaveVisualizer(
    isReading: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.testTag("reading_wave_visualizer"),
        contentAlignment = Alignment.Center
    ) {
        if (isReading) {
            ActiveWaveRings()
        }

        Image(
            painter = painterResource(id = R.drawable.welcome_logo),
            contentDescription = "ReadMe Logo",
            modifier = Modifier
                .height(160.dp)
                .padding(16.dp),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun ActiveWaveRings() {
    val infiniteTransition = rememberInfiniteTransition(label = "reading_wave_active")

    val waveScale1 by infiniteTransition.animateFloat(
        initialValue = 0.80f,
        targetValue = 1.38f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_scale_1"
    )

    val waveAlpha1 by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_alpha_1"
    )

    val waveScale2 by infiniteTransition.animateFloat(
        initialValue = 0.80f,
        targetValue = 1.38f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, delayMillis = 730, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_scale_2"
    )

    val waveAlpha2 by infiniteTransition.animateFloat(
        initialValue = 0.38f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, delayMillis = 730, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_alpha_2"
    )

    val waveScale3 by infiniteTransition.animateFloat(
        initialValue = 0.80f,
        targetValue = 1.38f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, delayMillis = 1460, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_scale_3"
    )

    val waveAlpha3 by infiniteTransition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, delayMillis = 1460, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_alpha_3"
    )

    Canvas(modifier = Modifier.size(240.dp)) {
        val radius = size.minDimension / 2f

        // Wave 1
        drawCircle(
            color = TealAccent.copy(alpha = waveAlpha1),
            radius = radius * waveScale1 * 0.72f,
            style = Stroke(width = 3.dp.toPx())
        )
        // Wave 2
        drawCircle(
            color = TealAccent.copy(alpha = waveAlpha2),
            radius = radius * waveScale2 * 0.72f,
            style = Stroke(width = 2.5.dp.toPx())
        )
        // Wave 3
        drawCircle(
            color = TealAccent.copy(alpha = waveAlpha3),
            radius = radius * waveScale3 * 0.72f,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

