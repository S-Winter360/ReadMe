package com.readme.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.readme.app.ui.theme.ReadMeMotion
import com.readme.app.ui.theme.readmePressFeedback

@Composable
fun ReadMePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val animatedContainerColor by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(durationMillis = ReadMeMotion.DurationFast),
        label = "PrimaryButtonColor"
    )

    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .readmePressFeedback(interactionSource = interactionSource, enabled = enabled),
        enabled = enabled,
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(
            containerColor = animatedContainerColor,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 6.dp)
        )
    }
}
