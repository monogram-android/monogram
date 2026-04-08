package org.monogram.presentation.core.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun TypingDots(
    modifier: Modifier = Modifier,
    dotSize: Dp = 4.dp,
    dotColor: Color = Color.Unspecified,
    spacing: Dp = 2.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "TypingDots")
    val color = if (dotColor == Color.Unspecified) LocalContentColor.current else dotColor
    val phase: State<Float> = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "DotPhase"
    )
    val width = dotSize * 3 + spacing * 2
    val minAlpha = 0.2f
    val maxAlpha = 1f

    Canvas(modifier = modifier.size(width = width, height = dotSize), onDraw = {
        val diameter = size.height
        val radius = diameter / 2f
        val spacingPx = spacing.toPx()

        repeat(3) { index ->
            val shifted = (phase.value + index / 3f) % 1f
            val progress = if (shifted <= 0.5f) shifted * 2f else (1f - shifted) * 2f
            val alpha = minAlpha + (maxAlpha - minAlpha) * progress

            drawCircle(
                color = color.copy(alpha = alpha),
                radius = radius,
                center = Offset(
                    x = radius + index * (diameter + spacingPx),
                    y = radius
                )
            )
        }
    })
}