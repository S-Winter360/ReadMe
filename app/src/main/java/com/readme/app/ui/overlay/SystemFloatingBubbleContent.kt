package com.readme.app.ui.overlay

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState
import com.readme.app.ui.components.BubbleState
import com.readme.app.ui.components.getBubbleState

@Composable
fun SystemFloatingBubbleContent(
    sessionState: ActiveReadingSessionState,
    activeDocumentState: ActiveDocumentState,
    onToggleReading: () -> Unit
) {
    val bubbleState = getBubbleState(sessionState, activeDocumentState)
    
    if (bubbleState == BubbleState.Hidden) return
    
    val infiniteTransition = rememberInfiniteTransition()
    val readingScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "readingPulse"
    )

    val scale = if (bubbleState == BubbleState.Reading) readingScale else 1f

    val iconText: String
    val contentDesc: String

    when (bubbleState) {
        BubbleState.Reading -> {
            iconText = "≈"
            contentDesc = "Stop reading"
        }
        BubbleState.Stopped -> {
            iconText = "▶"
            contentDesc = "Resume reading"
        }
        BubbleState.Completed -> {
            iconText = "✓"
            contentDesc = "Start reading again"
        }
        BubbleState.Error -> {
            iconText = "!"
            contentDesc = "ReadMe reading controls"
        }
        else -> {
            iconText = "▶"
            contentDesc = "ReadMe reading controls"
        }
    }

    Box(
        modifier = Modifier
            .size(64.dp)
            .scale(scale)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .semantics {
                this.contentDescription = contentDesc
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = iconText,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.headlineMedium
        )
    }
}
