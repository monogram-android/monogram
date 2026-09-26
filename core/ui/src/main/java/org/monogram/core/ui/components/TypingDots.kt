package org.monogram.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
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
    spacing: Dp = 2.dp,
) {
    val color = if (dotColor == Color.Unspecified) LocalContentColor.current else dotColor
    val phase: State<Float>? = if (LocalMediaAnimationEnabled.current) {
        rememberInfiniteTransition(label = "TypingDots").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "DotPhase",
        )
    } else {
        null
    }
    val width = dotSize * 3 + spacing * 2
    Canvas(modifier = modifier.size(width = width, height = dotSize), onDraw = {
        val dotPhase = phase?.value ?: 0f
        val diameter = size.height
        val radius = diameter / 2f
        val spacingPx = spacing.toPx()
        repeat(3) { index ->
            val shifted = (dotPhase + index / 3f) % 1f
            val progress = if (shifted <= 0.5f) shifted * 2f else (1f - shifted) * 2f
            val alpha = 0.2f + 0.8f * progress
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = radius,
                center = Offset(
                    x = radius + index * (diameter + spacingPx),
                    y = radius,
                ),
            )
        }
    })
}
