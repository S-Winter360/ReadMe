package com.readme.app.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * Cohesive motion design specifications and utilities for ReadMe.
 *
 * Implements subtle, professional 100-300ms transitions adhering to Material 3
 * motion guidelines while fully honoring accessibility and reduced motion preferences.
 */
object ReadMeMotion {
    const val DurationFast = 120
    const val DurationStandard = 200
    const val DurationModerate = 280

    val EasingStandard = FastOutSlowInEasing

    fun isReducedMotionEnabled(context: Context): Boolean {
        return try {
            val resolver = context.contentResolver
            val transitionScale = Settings.Global.getFloat(
                resolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1.0f
            )
            val animatorScale = Settings.Global.getFloat(
                resolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
            transitionScale == 0.0f || animatorScale == 0.0f
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * CompositionLocal providing access to the current reduced motion preference.
 */
val LocalReducedMotion = compositionLocalOf { false }

/**
 * Remembers whether reduced motion is active from system accessibility settings.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        ReadMeMotion.isReducedMotionEnabled(context)
    }
}

/**
 * Tactile press-feedback modifier applying subtle, spring-based scale down (0.97f) on press.
 * Automatically respects reduced motion by disabling scale transforms when active.
 */
@Composable
fun Modifier.readmePressFeedback(
    interactionSource: MutableInteractionSource? = null,
    targetScale: Float = 0.97f,
    enabled: Boolean = true
): Modifier {
    if (!enabled) return this
    val isReducedMotion = LocalReducedMotion.current
    val actualSource = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed by actualSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && !isReducedMotion) targetScale else 1.0f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMedium,
            dampingRatio = Spring.DampingRatioLowBouncy
        ),
        label = "PressFeedbackScale"
    )

    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Directional screen transition specification for navigation transitions.
 */
fun <T> readMeScreenTransition(
    reducedMotion: Boolean
): AnimatedContentTransitionScope<T>.() -> ContentTransform = {
    if (reducedMotion) {
        fadeIn(animationSpec = tween(durationMillis = ReadMeMotion.DurationFast)) togetherWith
            fadeOut(animationSpec = tween(durationMillis = ReadMeMotion.DurationFast))
    } else {
        val enterSpec = tween<Float>(
            durationMillis = ReadMeMotion.DurationModerate,
            easing = ReadMeMotion.EasingStandard
        )
        val exitSpec = tween<Float>(
            durationMillis = ReadMeMotion.DurationStandard,
            easing = ReadMeMotion.EasingStandard
        )

        (fadeIn(animationSpec = enterSpec) + slideInHorizontally(
            animationSpec = tween(
                durationMillis = ReadMeMotion.DurationModerate,
                easing = ReadMeMotion.EasingStandard
            ),
            initialOffsetX = { fullWidth -> (fullWidth * 0.07f).toInt() }
        )) togetherWith (fadeOut(animationSpec = exitSpec) + slideOutHorizontally(
            animationSpec = tween(
                durationMillis = ReadMeMotion.DurationStandard,
                easing = ReadMeMotion.EasingStandard
            ),
            targetOffsetX = { fullWidth -> -(fullWidth * 0.07f).toInt() }
        ))
    }
}
