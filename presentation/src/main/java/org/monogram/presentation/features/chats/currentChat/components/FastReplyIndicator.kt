package org.monogram.presentation.features.chats.currentChat.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun FastReplyIndicator(
    modifier: Modifier = Modifier,
    dragOffsetX: Animatable<Float, AnimationVector1D>,
    isOutgoing: Boolean = false,
    maxWidth: Dp,
    fadeInThreshold: Float,
    fastReplyTriggerThreshold: Float
) {
    val iconScale by animateFloatAsState(
        targetValue = if (dragOffsetX.value - fadeInThreshold < 0f) 1f else 0.5f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    )

    val iconAlpha by animateFloatAsState(
        targetValue = ((dragOffsetX.value - fadeInThreshold) / fastReplyTriggerThreshold).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 150)
    )

    if (dragOffsetX.value < fadeInThreshold) {
        Box(
            modifier = modifier
                .offset(x = if (isOutgoing) -fadeInThreshold.dp else maxWidth)
                .size(30.dp)
                .graphicsLayer {
                    translationX = if (isOutgoing) (-dragOffsetX.value + fadeInThreshold) * 0.5f else -fadeInThreshold
                    scaleX = iconScale
                    scaleY = iconScale
                    alpha = iconAlpha
                }
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Reply,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}