package org.monogram.core.harmony

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun SpatialHeader(
    scroll: Float,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val palette = HarmonyTheme.palette
    val glow = HarmonyTheme.glow
    val shift by animateFloatAsState(
        targetValue = scroll.coerceAtMost(600f),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 320f),
        label = "spatial-header",
    )

    Box(
        modifier
            .height(216.dp)
            .clip(RectangleShape)
            .background(Brush.verticalGradient(listOf(palette.primary.copy(alpha = 0.16f), Color.Transparent)))
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = -shift * 0.35f
                    rotationX = (-shift / 600f) * 5f
                    cameraDistance = 12f * density
                }
                .background(Brush.verticalGradient(listOf(palette.primary.copy(alpha = 0.30f), Color.Transparent)))
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 32.dp, end = 32.dp)
                .size(120.dp)
                .graphicsLayer {
                    translationY = shift * 0.18f
                    scaleX = 1.04f
                    scaleY = 1.04f
                }
                .clip(CircleShape)
                .background(glow.copy(alpha = 0.22f))
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(HarmonyTokens.Spacing.xl)
                .graphicsLayer { translationY = shift * 0.08f }
        ) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(HarmonyTokens.Spacing.xs))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun DepthBox(
    depth: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier.graphicsLayer {
            shadowElevation = depth * 2f * density
            scaleX = 1f + depth * 0.01f
            scaleY = 1f + depth * 0.01f
        },
    ) {
        content()
    }
}
