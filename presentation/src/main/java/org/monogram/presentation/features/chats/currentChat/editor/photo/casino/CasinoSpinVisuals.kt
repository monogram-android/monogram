package org.monogram.presentation.features.chats.currentChat.editor.photo.casino

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

@Stable
data class CasinoSpinVisualState(
    val overlayAlpha: Float,
    val contentScale: Float,
    val glowAlpha: Float,
    val accentAlpha: Float
)

@Composable
fun rememberCasinoSpinVisualState(isRunning: Boolean): CasinoSpinVisualState {
    val runningFactor by animateFloatAsState(
        targetValue = if (isRunning) 1f else 0f,
        animationSpec = tween(durationMillis = if (isRunning) 180 else 300),
        label = "CasinoRunningFactor"
    )
    val infiniteTransition = rememberInfiniteTransition(label = "CasinoSpinVisuals")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "CasinoPulseProgress"
    )
    val glowTravel by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "CasinoGlowTravel"
    )

    return CasinoSpinVisualState(
        overlayAlpha = runningFactor,
        contentScale = 1f + runningFactor * (0.015f + pulseProgress * 0.01f),
        glowAlpha = runningFactor * (0.16f + pulseProgress * 0.1f + glowTravel * 0.04f),
        accentAlpha = runningFactor * (0.15f + pulseProgress * 0.1f)
    )
}

@Composable
fun CasinoSpinOverlay(
    state: CasinoSpinVisualState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "CasinoSpinOverlay")
    val sweepProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "CasinoOverlaySweep"
    )
    val glowTravel by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "CasinoOverlayGlowTravel"
    )

    Canvas(modifier = modifier) {
        val sweepWidth = size.width * 0.24f
        val sweepX = (size.width + sweepWidth * 2f) * sweepProgress - sweepWidth
        val glowRadius = size.minDimension * 0.34f
        val glowCenter = Offset(
            x = size.width * 0.5f,
            y = size.height * (0.34f + glowTravel * 0.32f)
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0x66FFD166).copy(alpha = state.glowAlpha),
                    Color(0x44A855F7).copy(alpha = state.glowAlpha * 0.85f),
                    Color.Transparent
                ),
                center = glowCenter,
                radius = glowRadius
            ),
            radius = glowRadius,
            center = glowCenter,
            blendMode = BlendMode.Screen
        )

        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0x22FFFFFF),
                    Color(0x66FFFFFF),
                    Color(0x33A855F7),
                    Color.Transparent
                ),
                start = Offset(sweepX - sweepWidth, 0f),
                end = Offset(sweepX + sweepWidth, size.height)
            ),
            size = size,
            blendMode = BlendMode.Screen
        )

        drawLine(
            color = Color.White.copy(alpha = state.accentAlpha),
            start = Offset(size.width * 0.08f, size.height * 0.2f),
            end = Offset(size.width * 0.22f, size.height * 0.08f),
            strokeWidth = 4f
        )
        drawLine(
            color = Color(0xFFFFD166).copy(alpha = state.accentAlpha * 0.85f),
            start = Offset(size.width * 0.86f, size.height * 0.22f),
            end = Offset(size.width * 0.72f, size.height * 0.1f),
            strokeWidth = 4f
        )
        drawLine(
            color = Color(0xFFB388FF).copy(alpha = state.accentAlpha * 0.8f),
            start = Offset(size.width * 0.12f, size.height * 0.82f),
            end = Offset(size.width * 0.28f, size.height * 0.94f),
            strokeWidth = 4f
        )
        drawLine(
            color = Color.White.copy(alpha = state.accentAlpha * 0.9f),
            start = Offset(size.width * 0.9f, size.height * 0.8f),
            end = Offset(size.width * 0.76f, size.height * 0.92f),
            strokeWidth = 4f
        )
    }
}
