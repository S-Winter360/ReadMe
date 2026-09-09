package com.readme.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState
import com.readme.app.reading.DocumentLoadState
import kotlin.math.roundToInt

enum class BubbleState {
    Hidden,
    Idle,
    Reading,
    Stopped,
    Completed,
    Error
}

fun getBubbleState(
    sessionState: ActiveReadingSessionState,
    activeDocumentState: ActiveDocumentState
): BubbleState {
    if (!activeDocumentState.hasActiveDocument || activeDocumentState.loadState !is DocumentLoadState.Loaded) {
        return BubbleState.Hidden
    }
    return when {
        sessionState.isError -> BubbleState.Error
        sessionState.isReading -> BubbleState.Reading
        sessionState.isStopped -> BubbleState.Stopped
        sessionState.isCompleted -> BubbleState.Completed
        else -> BubbleState.Idle
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReadMeFloatingBubble(
    sessionState: ActiveReadingSessionState,
    activeDocumentState: ActiveDocumentState,
    onToggleReading: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bubbleState = getBubbleState(sessionState, activeDocumentState)
    
    var isExpanded by remember { mutableStateOf(false) }

    // Reset expansion on document change
    LaunchedEffect(activeDocumentState.documentId) {
        isExpanded = false
    }

    if (bubbleState == BubbleState.Hidden) return

    val density = LocalDensity.current
    val compactSizeDp = 64.dp
    val expandedWidthDp = 160.dp
    
    val currentWidthDp by animateDpAsState(
        targetValue = if (isExpanded) expandedWidthDp else compactSizeDp,
        label = "widthAnimation"
    )

    val currentWidthPx = with(density) { currentWidthDp.toPx() }
    val bubbleHeightPx = with(density) { compactSizeDp.toPx() }
    
    var parentSize by remember { mutableStateOf(IntSize.Zero) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var isInitialized by remember { mutableStateOf(false) }

    val minX = 0f
    val minY = 0f
    val maxX = (parentSize.width - currentWidthPx).coerceAtLeast(0f)
    val maxY = (parentSize.height - bubbleHeightPx).coerceAtLeast(0f)

    LaunchedEffect(maxX, maxY) {
        if (isInitialized) {
            offsetX = offsetX.coerceIn(minX, maxX)
            offsetY = offsetY.coerceIn(minY, maxY)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { newSize ->
                parentSize = newSize
                if (!isInitialized && newSize.width > 0 && newSize.height > 0) {
                    val padding = with(density) { 24.dp.toPx() }
                    val initialMaxX = (newSize.width - currentWidthPx).coerceAtLeast(0f)
                    val initialMaxY = (newSize.height - bubbleHeightPx).coerceAtLeast(0f)
                    offsetX = (initialMaxX - padding).coerceAtLeast(0f)
                    offsetY = (initialMaxY - padding).coerceAtLeast(0f)
                    isInitialized = true
                }
            }
    ) {
        if (isInitialized) {
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

            // Only pulse when reading and NOT expanded
            val scale = if (bubbleState == BubbleState.Reading && !isExpanded) readingScale else 1f

            val iconText: String
            val contentDesc: String
            val expandedActionText: String
            
            when (bubbleState) {
                BubbleState.Reading -> {
                    iconText = "≈"
                    contentDesc = "Stop reading"
                    expandedActionText = "Stop"
                }
                BubbleState.Stopped -> {
                    iconText = "▶"
                    contentDesc = "Resume reading"
                    expandedActionText = "Resume"
                }
                BubbleState.Completed -> {
                    iconText = "✓"
                    contentDesc = "Start reading again"
                    expandedActionText = "Restart"
                }
                BubbleState.Error -> {
                    iconText = "!"
                    contentDesc = "ReadMe reading controls"
                    expandedActionText = "Retry"
                }
                else -> {
                    iconText = "▶"
                    contentDesc = "ReadMe reading controls"
                    expandedActionText = "Start"
                }
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .size(width = currentWidthDp, height = compactSizeDp)
                    .scale(scale)
                    .shadow(8.dp, CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                offsetX = (offsetX + dragAmount.x).coerceIn(minX, maxX)
                                offsetY = (offsetY + dragAmount.y).coerceIn(minY, maxY)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isExpanded) {
                    // Expanded controls
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Collapse Button
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable { isExpanded = false }
                                .semantics { 
                                    this.contentDescription = "Collapse controls" 
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.Text(
                                text = "×",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.headlineMedium
                            )
                        }
                        
                        // Vertical divider
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(32.dp)
                                .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                        )
                        
                        // Primary Action Button
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable { 
                                    onToggleReading()
                                    isExpanded = false
                                }
                                .semantics { 
                                    this.contentDescription = contentDesc 
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                androidx.compose.material3.Text(
                                    text = iconText,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                androidx.compose.material3.Text(
                                    text = expandedActionText,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                } else {
                    // Compact bubble
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .combinedClickable(
                                onClick = { onToggleReading() },
                                onLongClick = { isExpanded = true },
                                onLongClickLabel = "Expand controls"
                            )
                            .semantics {
                                this.contentDescription = contentDesc
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Text(
                            text = iconText,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                }
            }
        }
    }
}
