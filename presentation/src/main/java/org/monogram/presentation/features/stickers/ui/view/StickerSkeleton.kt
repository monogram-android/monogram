package org.monogram.presentation.features.stickers.ui.view

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

fun Modifier.shimmerEffect(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "StickerShimmer")
    val progress by transition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1150, easing = LinearEasing)
        ),
        label = "StickerShimmerProgress"
    )

    val baseColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
    val plateColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.18f)
    val shimmerHighlight = MaterialTheme.colorScheme.surfaceBright.copy(alpha = 0.72f)

    drawWithCache {
        val width = size.width.takeIf { it > 0f } ?: 1f
        val height = size.height.takeIf { it > 0f } ?: 1f
        val shimmerCenterX = progress * width
        val shimmerWidth = width * 0.42f
        val shimmerBrush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                shimmerHighlight,
                Color.Transparent
            ),
            start = Offset(shimmerCenterX - shimmerWidth, 0f),
            end = Offset(shimmerCenterX, height)
        )

        onDrawBehind {
            drawRoundRect(
                color = baseColor,
                cornerRadius = CornerRadius(42f, 42f)
            )

            val insetX = width * 0.18f
            val insetY = height * 0.18f
            drawRoundRect(
                color = plateColor,
                topLeft = Offset(insetX, insetY),
                size = Size(width - insetX * 2f, height - insetY * 2f),
                cornerRadius = CornerRadius(30f, 30f)
            )

            drawRoundRect(
                brush = shimmerBrush,
                cornerRadius = CornerRadius(42f, 42f)
            )
        }
    }
}

@Composable
fun StickerSkeleton(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(4.dp)
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.extraLarge)
            .shimmerEffect()
    )
}
