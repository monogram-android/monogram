package org.monogram.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun Modifier.shimmerBackground(
    shape: Shape = RectangleShape,
    durationMillis: Int = 1200,
    shimmerWidthPx: Float = 340f,
    initialOffset: Float = -600f,
    targetOffset: Float = 1200f,
): Modifier {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlight = MaterialTheme.colorScheme.surfaceContainerLow
    val transition = rememberInfiniteTransition(label = "shared_shimmer")
    val offset = transition.animateFloat(
        initialValue = initialOffset,
        targetValue = targetOffset,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shared_shimmer_offset",
    )
    return this
        .clip(shape)
        .drawBehind {
            val shimmerOffset = offset.value
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(shimmerOffset, 0f),
                    end = Offset(shimmerOffset + shimmerWidthPx, 0f),
                ),
            )
        }
}

@Composable
fun Modifier.monoPlaceholder(shape: Shape = RoundedCornerShape(6.dp)): Modifier =
    shimmerBackground(shape)

@Composable
fun MonogramPlaceholder(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
) {
    Box(modifier = modifier.shimmerBackground(shape))
}

@Composable
fun MonogramPlaceholderCircle(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(size).shimmerBackground(CircleShape))
}
