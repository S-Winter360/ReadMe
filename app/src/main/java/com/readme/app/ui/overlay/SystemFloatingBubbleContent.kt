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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.readme.app.accessibility.CrossAppAcquisitionMode
import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState
import com.readme.app.ui.components.BubbleState
import com.readme.app.ui.components.getBubbleState
import com.readme.app.ui.theme.DarkElevatedSurface
import com.readme.app.ui.theme.ReadMeTheme
import com.readme.app.ui.theme.TealAccent

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
    crossAppReadingEnabled: Boolean = true,
    canAcquireText: Boolean = false,
    onToggleReading: () -> Unit = {},
    onPauseReading: () -> Unit = {},
    onResumeReading: () -> Unit = {},
    onReselectArea: () -> Unit = {},
    onStopReading: () -> Unit = {},
    onCloseBubble: () -> Unit = {},
    onAcquireMode: (CrossAppAcquisitionMode) -> Unit = {},
    onDragStart: () -> Unit = {},
    onDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {}
) {
    ReadMeTheme(darkTheme = true) {
        var isExpanded by remember { mutableStateOf(false) }

        val isReading = sessionState.isReading
        val isPaused = !isReading &&
            sessionState.isStopped &&
            (activeDocumentState.hasActiveDocument || sessionState.currentPosition != null) &&
            !sessionState.isCompleted

        val infiniteTransition = rememberInfiniteTransition(label = "bubbleTransition")
        val readingPulseScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(
                animation = tween(750, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "readingPulse"
        )

        val bubbleScale = if (isReading) readingPulseScale else 1f

        val mainIconText = when {
            isReading -> "≈"
            isPaused -> "❚❚"
            activeDocumentState.isEphemeral && activeDocumentState.hasSuspendedPrimary && sessionState.isCompleted -> "↩"
            activeDocumentState.hasActiveDocument && sessionState.isCompleted -> "✓"
            else -> "📖"
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(10.dp)
        ) {
            // Main floating bubble trigger
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .scale(bubbleScale)
                    .shadow(10.dp, CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragCancel() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.x, dragAmount.y)
                            }
                        )
                    }
                    .clickable {
                        // Single tap on compact bubble expands or collapses controls.
                        // Does NOT stop reading!
                        isExpanded = !isExpanded
                    }
                    .semantics {
                        this.contentDescription = "ReadMe floating bubble"
                    }
                    .testTag("system_floating_bubble"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = mainIconText,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // Expanded control panel
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + scaleIn(initialScale = 0.85f),
                exit = fadeOut() + scaleOut(targetScale = 0.85f)
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = DarkElevatedSurface,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.padding(start = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val hasActiveSession = isReading || isPaused

                        if (hasActiveSession) {
                            // Active external / reading controls
                            if (isReading) {
                                BubbleActionButton(
                                    icon = "❚❚",
                                    label = "Pause reading",
                                    testTag = "bubble_pause_button",
                                    onClick = {
                                        onPauseReading()
                                    }
                                )
                            } else {
                                BubbleActionButton(
                                    icon = "▶",
                                    label = "Resume reading",
                                    testTag = "bubble_resume_button",
                                    onClick = {
                                        onResumeReading()
                                    }
                                )
                            }

                            // Reselect Area (available when external/ephemeral document is active)
                            if (activeDocumentState.isEphemeral) {
                                BubbleActionButton(
                                    icon = "⟲",
                                    label = "Reselect reading area",
                                    testTag = "bubble_reselect_button",
                                    onClick = {
                                        isExpanded = false
                                        onReselectArea()
                                    }
                                )
                            }

                            // Stop Button (distinct styling from harmless actions)
                            BubbleActionButton(
                                icon = "■",
                                label = "Stop reading",
                                testTag = "bubble_stop_button",
                                iconColor = MaterialTheme.colorScheme.error,
                                backgroundColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                                onClick = {
                                    isExpanded = false
                                    onStopReading()
                                }
                            )
                        } else {
                            // Idle cross-app acquisition controls
                            if (crossAppReadingEnabled) {
                                BubbleActionButton(
                                    icon = "T",
                                    label = "Read current text",
                                    testTag = "bubble_read_current_text",
                                    onClick = {
                                        isExpanded = false
                                        onAcquireMode(CrossAppAcquisitionMode.ACCESSIBILITY_TEXT)
                                    }
                                )
                                BubbleActionButton(
                                    icon = "◳",
                                    label = "Read screen",
                                    testTag = "bubble_read_screen",
                                    onClick = {
                                        isExpanded = false
                                        onAcquireMode(CrossAppAcquisitionMode.SCREEN_OCR)
                                    }
                                )
                            } else {
                                Text(
                                    text = "Cross-app OFF",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                        }

                        // Close Bubble Button (distinct from Stop; neutral dismiss icon)
                        BubbleActionButton(
                            icon = "✕",
                            label = "Close floating bubble",
                            testTag = "bubble_close_button",
                            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            onClick = {
                                isExpanded = false
                                onCloseBubble()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BubbleActionButton(
    icon: String,
    label: String,
    testTag: String,
    iconColor: androidx.compose.ui.graphics.Color = TealAccent,
    backgroundColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceVariant,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .semantics {
                this.contentDescription = label
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = icon,
            color = iconColor,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}
