package com.readme.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.readme.app.ui.theme.ReadMeMotion
import com.readme.app.ui.theme.readmePressFeedback

@Composable
fun ReadMeSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val animatedOutlineColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.outline,
        animationSpec = tween(durationMillis = ReadMeMotion.DurationFast),
        label = "SecondaryButtonOutline"
    )

    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .readmePressFeedback(interactionSource = interactionSource),
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, animatedOutlineColor),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 6.dp)
        )
    }
}
