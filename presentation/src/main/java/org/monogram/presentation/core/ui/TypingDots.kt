package org.monogram.presentation.core.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        repeat(3) { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 600
                        0.2f at 0
                        1f at 300
                        0.2f at 600
                    },
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(index * 200)
                ),
                label = "DotAlpha"
            )

            Box(
                modifier = Modifier
                    .size(dotSize)
                    .background(color.copy(alpha = alpha), CircleShape)
            )
        }
    }
}