package com.readme.app.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readme.app.R

/**
 * The ReadMe wordmark with "Read" in light text and "Me" bold in teal using Serif.
 */
@Composable
fun ReadMeWordmark(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 38.sp
) {
    Row(
        modifier = modifier.semantics { contentDescription = "ReadMe" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Read",
            color = OnboardingColors.TextLight,
            fontSize = fontSize,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Normal
        )
        Text(
            text = "Me",
            color = OnboardingColors.WordmarkTeal,
            fontSize = fontSize,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Animated 5-dot progress indicator for onboarding pages.
 */
@Composable
fun OnboardingProgressIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until pageCount) {
            val isActive = i == currentPage
            val targetWidth: Dp by animateDpAsState(
                targetValue = if (isActive) 22.dp else 6.dp,
                animationSpec = tween(durationMillis = 250),
                label = "dot_width_$i"
            )
            val targetColor: Color by animateColorAsState(
                targetValue = if (isActive) OnboardingColors.PrimaryTeal else OnboardingColors.CardBorder,
                animationSpec = tween(durationMillis = 250),
                label = "dot_color_$i"
            )

            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(targetWidth)
                    .clip(RoundedCornerShape(3.dp))
                    .background(targetColor)
            )
        }
    }
}

/**
 * Pill-shaped primary button with teal gradient and white bold label with forward arrow.
 */
@Composable
fun OnboardingPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "onboarding_primary_button"
) {
    val gradient = Brush.horizontalGradient(
        colors = listOf(
            OnboardingColors.PrimaryTeal,
            OnboardingColors.DeepTeal
        )
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(60.dp))
            .background(gradient)
            .clickable(
                role = Role.Button,
                onClick = onClick
            )
            .semantics {
                role = Role.Button
                contentDescription = text
            }
            .testTag(testTag)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Standard Onboarding Toggle Card matching the design tokens (18dp corner radius, #1A1E22, #303A3C border).
 */
@Composable
fun OnboardingToggleCard(
    iconPainter: Painter,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "onboarding_toggle_card"
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(OnboardingColors.CardBackground)
            .border(1.dp, OnboardingColors.CardBorder, RoundedCornerShape(18.dp))
            .clickable { onCheckedChange(!checked) }
            .semantics {
                contentDescription = "$title, $subtitle, ${if (checked) "enabled" else "disabled"}"
            }
            .testTag(testTag)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Icon in rounded dark teal square
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(OnboardingColors.IconSquareBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = iconPainter,
                        contentDescription = null,
                        tint = OnboardingColors.PrimaryTeal,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = title,
                        color = OnboardingColors.TextLight,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = OnboardingColors.TextMuted,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = OnboardingColors.PrimaryTeal,
                    uncheckedThumbColor = OnboardingColors.ToggleOffThumb,
                    uncheckedTrackColor = OnboardingColors.ToggleOffTrack,
                    uncheckedBorderColor = Color.Transparent
                ),
                modifier = Modifier.testTag("${testTag}_switch")
            )
        }
    }
}

/**
 * Status check item for Screen 5 (Ready).
 */
@Composable
fun OnboardingStatusItem(
    title: String,
    isReady: Boolean,
    modifier: Modifier = Modifier,
    testTag: String = "onboarding_status_item"
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(OnboardingColors.CardBackground)
            .border(1.dp, OnboardingColors.CardBorder, RoundedCornerShape(18.dp))
            .semantics {
                contentDescription = "$title, ${if (isReady) "ready" else "not enabled"}"
            }
            .testTag(testTag)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(
                    id = if (isReady) R.drawable.ic_check_circle_teal else R.drawable.ic_circle_outline_muted
                ),
                contentDescription = if (isReady) "Ready" else "Not enabled",
                tint = if (isReady) Color.Unspecified else OnboardingColors.TextMuted,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Text(
                text = title,
                color = if (isReady) OnboardingColors.TextLight else OnboardingColors.TextMuted,
                fontSize = 15.sp,
                fontWeight = if (isReady) FontWeight.Medium else FontWeight.Normal
            )
        }
    }
}
