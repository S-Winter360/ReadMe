package com.readme.app.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
import kotlinx.coroutines.launch

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

enum class BubbleTouchGestureState {
    Idle,
    Pressed,
    Dragging,
    Released,
    Tapped,
    Cancelled
}

@Composable
fun SystemFloatingBubbleContent(
    sessionState: ActiveReadingSessionState,
    activeDocumentState: ActiveDocumentState,
    crossAppReadingEnabled: Boolean = true,
    canAcquireText: Boolean = false,
    isAutoAdvanceEnabled: Boolean = false,
    onToggleReading: () -> Unit = {},
    onPauseReading: () -> Unit = {},
    onResumeReading: () -> Unit = {},
    onReselectArea: () -> Unit = {},
    onStopReading: () -> Unit = {},
    onCloseBubble: () -> Unit = {},
    onAcquireMode: (CrossAppAcquisitionMode) -> Unit = {},
    onCalibrateNextPage: () -> Unit = {},
    onBubbleTap: () -> Unit = {},
    onDragStart: () -> Unit = {},
    onDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {}
) {
    ReadMeTheme(darkTheme = true) {
        var isExpanded by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()
        val tapFeedbackScale = remember { Animatable(1f) }

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
        val effectiveScale = bubbleScale * tapFeedbackScale.value

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
            // Main floating bubble trigger: Single authoritative gesture handler (tap vs drag)
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .scale(effectiveScale)
                    .shadow(10.dp, CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .pointerInput(Unit) {
                        val dragThresholdPx = 8.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val activePointerId = down.id
                            val startPosition = down.position
                            var isDragging = false
                            var gestureState = BubbleTouchGestureState.Pressed

                            BubbleTouchTrace.recordDown(
                                pointerId = activePointerId.value,
                                startX = startPosition.x,
                                startY = startPosition.y,
                                threshold = dragThresholdPx,
                                expanded = isExpanded
                            )

                            while (gestureState == BubbleTouchGestureState.Pressed || gestureState == BubbleTouchGestureState.Dragging) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == activePointerId }

                                if (change == null) {
                                    val allReleased = event.changes.all { !it.pressed }
                                    gestureState = if (allReleased) {
                                        if (isDragging) BubbleTouchGestureState.Released else BubbleTouchGestureState.Tapped
                                    } else {
                                        BubbleTouchGestureState.Cancelled
                                    }
                                    break
                                }

                                if (!change.pressed) {
                                    // Pointer lifted (ACTION_UP)
                                    change.consume()
                                    gestureState = if (isDragging) {
                                        BubbleTouchGestureState.Released
                                    } else {
                                        BubbleTouchGestureState.Tapped
                                    }
                                    break
                                }

                                // Pointer moving (ACTION_MOVE)
                                val currentPos = change.position
                                val dx = currentPos.x - startPosition.x
                                val dy = currentPos.y - startPosition.y
                                val displacement = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

                                BubbleTouchTrace.recordMove(
                                    currentX = currentPos.x,
                                    currentY = currentPos.y,
                                    displacement = displacement,
                                    dragging = isDragging
                                )

                                if (!isDragging) {
                                    if (displacement > dragThresholdPx) {
                                        isDragging = true
                                        gestureState = BubbleTouchGestureState.Dragging
                                        change.consume()
                                        BubbleTouchTrace.recordDragStart()
                                        onDragStart()
                                        onDrag(dx, dy)
                                    }
                                } else {
                                    val delta = change.positionChange()
                                    change.consume()
                                    if (delta.x != 0f || delta.y != 0f) {
                                        onDrag(delta.x, delta.y)
                                    }
                                }
                            }

                            when (gestureState) {
                                BubbleTouchGestureState.Tapped -> {
                                    val before = isExpanded
                                    val after = !before
                                    BubbleTouchTrace.recordTap(isExpandedBefore = before, isExpandedAfter = after)
                                    coroutineScope.launch {
                                        try {
                                            tapFeedbackScale.animateTo(0.92f, tween(50))
                                            tapFeedbackScale.animateTo(1.0f, tween(100))
                                        } catch (_: Exception) {}
                                    }
                                    isExpanded = after
                                    onBubbleTap()
                                }
                                BubbleTouchGestureState.Released -> {
                                    BubbleTouchTrace.recordRelease()
                                    onDragEnd()
                                }
                                BubbleTouchGestureState.Cancelled -> {
                                    BubbleTouchTrace.recordCancel(wasDragging = isDragging)
                                    if (isDragging) {
                                        onDragCancel()
                                    }
                                }
                                else -> {
                                    if (isDragging) {
                                        onDragCancel()
                                    }
                                }
                            }
                        }
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

                            // Calibrate button (available during reading or idle when auto-advance is on)
                            if (isAutoAdvanceEnabled) {
                                BubbleActionButton(
                                    icon = "🎯",
                                    label = "Calibrate next page",
                                    testTag = "bubble_calibrate_button",
                                    onClick = {
                                        isExpanded = false
                                        onCalibrateNextPage()
                                    }
                                )
                            }

                            // Auto-advance indicator badge
                            if (activeDocumentState.isEphemeral && isAutoAdvanceEnabled) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .testTag("bubble_auto_advance_badge")
                                ) {
                                    Text(
                                        text = "Auto-advance ON",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
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
                                if (isAutoAdvanceEnabled) {
                                    BubbleActionButton(
                                        icon = "🎯",
                                        label = "Calibrate next page",
                                        testTag = "bubble_calibrate_button",
                                        onClick = {
                                            isExpanded = false
                                            onCalibrateNextPage()
                                        }
                                    )
                                }
                            } else {
                                Text(
                                    text = "Cross-app OFF",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                        }

                        // Collapse Control (collapses expanded controls without closing the bubble)
                        BubbleActionButton(
                            icon = "‹",
                            label = "Collapse controls",
                            testTag = "bubble_collapse_button",
                            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            onClick = {
                                isExpanded = false
                            }
                        )

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
