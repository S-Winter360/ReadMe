package com.readme.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val ReadMeDarkColorScheme = darkColorScheme(
    primary = TealAccent,
    onPrimary = CharcoalBackground,
    primaryContainer = TealAccentPressed,
    onPrimaryContainer = TextPrimary,
    
    secondary = DarkElevatedSurface,
    onSecondary = TextPrimary,
    
    background = CharcoalBackground,
    onBackground = TextPrimary,
    
    surface = DarkSurface,
    onSurface = TextPrimary,
    
    surfaceVariant = DarkElevatedSurface,
    onSurfaceVariant = TextSecondary,
    
    outline = BorderSubtle,
    outlineVariant = TextDisabled,
    
    error = ErrorRed,
    onError = TextPrimary
)

@Composable
fun ReadMeTheme(
    // Enforce dark-first visual design (no light theme in this phase)
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = ReadMeDarkColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}