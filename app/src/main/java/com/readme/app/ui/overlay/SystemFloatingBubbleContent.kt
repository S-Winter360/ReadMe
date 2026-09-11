package com.readme.app.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.readme.app.accessibility.CrossAppAcquisitionMode
import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState
import com.readme.app.ui.components.BubbleState
import com.readme.app.ui.components.getBubbleState

fun getSystemBubbleState(
    sessionState: ActiveReadingSessionState,
    activeDocumentState: ActiveDocumentState,
    canAcquireText: Boolean = false
): BubbleState {
    if (!activeDocumentState.hasActiveDocument) {
        return if (canAcquireText) BubbleState.Idle else BubbleState.Hidden
    }
    return getBubbleState(sessionState, activeDocumentState)
}

@Composable
fun SystemFloatingBubbleContent(
    sessionState: ActiveReadingSessionState,
    activeDocumentState: ActiveDocumentState,
    canAcquireText: Boolean = false,
    onToggleReading: () -> Unit,
    onAcquireMode: (CrossAppAcquisitionMode) -> Unit = {},
    onDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> }
) {
    val bubbleState = getSystemBubbleState(sessionState, activeDocumentState, canAcquireText)
    
    if (bubbleState == BubbleState.Hidden) return
    
    var expanded by remember { mutableStateOf(false) }

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
            contentDesc = if (activeDocumentState.isEphemeral) "Stop reading external text" else "Stop reading"
            expanded = false
        }
        BubbleState.Stopped -> {
            iconText = "▶"
            contentDesc = if (activeDocumentState.isEphemeral) "Resume reading external text" else "Resume reading"
            expanded = false
        }
        BubbleState.Completed -> {
            if (activeDocumentState.isEphemeral && activeDocumentState.hasSuspendedPrimary) {
                iconText = "↩"
                contentDesc = "Return to ReadMe"
            } else {
                iconText = "✓"
                contentDesc = "Start reading again"
            }
            expanded = false
        }
        BubbleState.Error -> {
            iconText = "!"
            contentDesc = "ReadMe reading controls"
            expanded = false
        }
        else -> {
            if (!activeDocumentState.hasActiveDocument) {
                iconText = "📖"
                contentDesc = "Read from other apps menu"
            } else {
                iconText = "▶"
                contentDesc = "ReadMe reading controls"
                expanded = false
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(8.dp) // extra padding to avoid clipping shadows
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .scale(scale)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                }
                .clickable {
                    if (bubbleState == BubbleState.Idle && !activeDocumentState.hasActiveDocument) {
                        expanded = !expanded
                    } else {
                        onToggleReading()
                    }
                }
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
        
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f)
        ) {
            Row(modifier = Modifier.padding(start = 12.dp)) {
                // Read Current Text Button
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable {
                            expanded = false
                            onAcquireMode(CrossAppAcquisitionMode.ACCESSIBILITY_TEXT)
                        }
                        .semantics { contentDescription = "Read Current Text" },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "T",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                // Read Screen OCR Button
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable {
                            expanded = false
                            onAcquireMode(CrossAppAcquisitionMode.SCREEN_OCR)
                        }
                        .semantics { contentDescription = "Read Screen" },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "◳", // Using a symbol to represent screen/image
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}
