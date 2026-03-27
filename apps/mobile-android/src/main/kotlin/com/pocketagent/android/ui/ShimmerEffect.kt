package com.pocketagent.android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketagent.android.ui.theme.PocketAgentDimensions

@Composable
internal fun shimmerBrush(): Brush {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    )
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_translate",
    )
    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, translateAnim - 200f),
        end = Offset(translateAnim, translateAnim),
    )
}

@Composable
private fun ShimmerLine(
    width: Float = 1f,
    height: Dp = 14.dp,
    brush: Brush,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(width)
            .height(height)
            .clip(MaterialTheme.shapes.small)
            .background(brush),
    )
}

@Composable
internal fun ShimmerMessageBubble(
    alignEnd: Boolean = false,
    lineCount: Int = 3,
) {
    val brush = shimmerBrush()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { },
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .padding(PocketAgentDimensions.cardPadding),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing)) {
                repeat(lineCount) { index ->
                    val widthFraction = when (index) {
                        0 -> 0.85f
                        lineCount - 1 -> 0.5f
                        else -> 0.7f
                    }
                    ShimmerLine(width = widthFraction, brush = brush)
                }
            }
        }
    }
}

@Composable
internal fun ShimmerMessageLoadingPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = PocketAgentDimensions.screenPadding,
                vertical = PocketAgentDimensions.sectionSpacing,
            )
            .clearAndSetSemantics { },
        verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.cardPadding),
    ) {
        ShimmerMessageBubble(alignEnd = true, lineCount = 1)
        ShimmerMessageBubble(alignEnd = false, lineCount = 3)
        ShimmerMessageBubble(alignEnd = true, lineCount = 2)
        ShimmerMessageBubble(alignEnd = false, lineCount = 2)
    }
}

@Composable
private fun ShimmerLine(
    modifier: Modifier = Modifier,
) {
    val brush = shimmerBrush()
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(brush),
    )
}

@Composable
internal fun ShimmerSessionRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = PocketAgentDimensions.sheetHorizontalPadding,
                vertical = PocketAgentDimensions.screenPadding,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing / 2),
        ) {
            ShimmerLine(
                modifier = Modifier.fillMaxWidth(0.6f).height(14.dp),
            )
            ShimmerLine(
                modifier = Modifier.fillMaxWidth(0.3f).height(10.dp),
            )
        }
    }
}

@Composable
internal fun ShimmerSessionListPlaceholder(count: Int = 5) {
    Column(
        modifier = Modifier.clearAndSetSemantics { },
    ) {
        repeat(count) {
            ShimmerSessionRow()
        }
    }
}
