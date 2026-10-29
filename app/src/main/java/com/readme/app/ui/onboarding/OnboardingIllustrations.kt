package com.readme.app.ui.onboarding

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class OnboardingIllustrationType {
    FloatingBubble,
    ScreenReading,
    AutoTurnPage
}

/**
 * High-fidelity vector illustration of the smartphone with comic panels,
 * floating wave bubble, reading highlight, and page curl/scroll chevrons
 * matching the 1080x1920 reference designs.
 */
@Composable
fun OnboardingPhoneIllustration(
    type: OnboardingIllustrationType,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "illustration_anim")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    val chevronOffset by infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "chevron_anim"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(310.dp),
        contentAlignment = Alignment.Center
    ) {
        // Phone Bezel Frame
        Box(
            modifier = Modifier
                .width(200.dp)
                .height(295.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Color(0xFF22282C))
                .border(2.dp, Color(0xFF333E43), RoundedCornerShape(32.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top speaker notch
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF374449))
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Inner Phone Screen
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFFE9F1EE))
                        .padding(8.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Comic panels top row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Panel 1 with dialogue bubble
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFA5C9BF))
                                    .padding(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.85f)
                                        .height(14.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(Color.White)
                                )
                            }

                            // Panel 2
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFB9D7CF))
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Middle wide comic panel with speech bubble
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFCDE3DD))
                                .padding(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .height(16.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Text reading lines section
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            // If screen reading, first line has vibrant teal reading bar
                            if (type == OnboardingIllustrationType.ScreenReading) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(2.5.dp))
                                        .background(OnboardingColors.PrimaryTeal)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(2.5.dp))
                                        .background(Color(0xFF8BB1A8))
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.95f)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0xFFA6C6BF))
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.6f)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0xFFA6C6BF))
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0xFFA6C6BF))
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0xFFA6C6BF))
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.95f)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0xFFA6C6BF))
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.75f)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0xFFA6C6BF))
                            )
                        }

                        // Bottom badge on ScreenReading: "Reading screen text..."
                        if (type == OnboardingIllustrationType.ScreenReading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(20.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF143934)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Reading screen text...",
                                    color = Color(0xFFC7EFE4),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // Bottom-right page curl on AutoTurnPage
                        if (type == OnboardingIllustrationType.AutoTurnPage) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(30.dp),
                                contentAlignment = Alignment.BottomEnd
                            ) {
                                Canvas(modifier = Modifier.size(36.dp, 30.dp)) {
                                    val curlPath = Path().apply {
                                        moveTo(0f, size.height)
                                        lineTo(size.width, 0f)
                                        lineTo(size.width, size.height)
                                        close()
                                    }
                                    drawPath(
                                        path = curlPath,
                                        color = Color(0xFFB4DDD1)
                                    )
                                    // Subtle fold crease
                                    drawLine(
                                        color = Color(0xFF7FA89C),
                                        start = Offset(0f, size.height),
                                        end = Offset(size.width, 0f),
                                        strokeWidth = 2f
                                    )
                                }
                            }
                        }
                    }

                    // Floating circular bubble at top right with concentric wave ripples
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .offset(x = 100.dp, y = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Concentric Ripple rings
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val center = Offset(size.width / 2, size.height / 2)
                            drawCircle(
                                color = Color(0x28308D85),
                                radius = (size.width / 2) * pulseScale,
                                center = center,
                                style = Stroke(width = 1.5f)
                            )
                            drawCircle(
                                color = Color(0x4445A69B),
                                radius = (size.width / 2.7f) * pulseScale,
                                center = center,
                                style = Stroke(width = 1.5f)
                            )
                        }

                        // Circular Teal Bubble
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFB7E8DD))
                                .border(1.5.dp, Color(0xFF5AB6A8), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            // Animated Sound Wave
                            Canvas(modifier = Modifier.size(24.dp, 14.dp)) {
                                val wavePath = Path().apply {
                                    moveTo(2f, size.height * 0.5f)
                                    cubicTo(
                                        6f, size.height * 0.95f,
                                        9f, size.height * 0.05f,
                                        13f, size.height * 0.45f
                                    )
                                    cubicTo(
                                        16f, size.height * 0.85f,
                                        19f, size.height * 0.15f,
                                        22f, size.height * 0.55f
                                    )
                                }
                                drawPath(
                                    path = wavePath,
                                    color = Color(0xFF1E6C63),
                                    style = Stroke(
                                        width = 2.4f,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Downward Chevrons (>>> pointing down) for AutoTurnPage
        if (type == OnboardingIllustrationType.AutoTurnPage) {
            Box(
                modifier = Modifier
                    .offset(x = 126.dp, y = (-20).dp + chevronOffset.dp)
                    .width(26.dp)
                    .height(60.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val chevronColor = Color(0xFF52AFA2)
                    for (i in 0..2) {
                        val yOffset = i * 16f + 8f
                        val path = Path().apply {
                            moveTo(4f, yOffset)
                            lineTo(size.width / 2f, yOffset + 9f)
                            lineTo(size.width - 4f, yOffset)
                        }
                        drawPath(
                            path = path,
                            color = chevronColor,
                            style = Stroke(width = 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }
                }
            }
        }
    }
}
