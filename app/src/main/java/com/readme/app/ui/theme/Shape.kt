package com.readme.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Reusable moderately rounded shapes for the design system
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), // Checkboxes, subtle borders
    small = RoundedCornerShape(8.dp),      // Small buttons, inputs
    medium = RoundedCornerShape(12.dp),    // Cards, dropdown controls, menus
    large = RoundedCornerShape(16.dp),     // Sliders/Containers, dialogs
    extraLarge = RoundedCornerShape(24.dp) // Large bottom sheets
)
