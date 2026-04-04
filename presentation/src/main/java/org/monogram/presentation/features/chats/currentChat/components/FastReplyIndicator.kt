package org.monogram.presentation.features.chats.currentChat.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun FastReplyIndicator(
    modifier: Modifier = Modifier,
    dragOffsetX: Animatable<Float, AnimationVector1D>,
    isOutgoing: Boolean,
    maxWidth: Dp,
    fastReplyTriggerThreshold: Float
) {
    val iconScale by animateFloatAsState(
        targetValue = if (dragOffsetX.value < 0f) 1f else 0.5f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    )

    val iconAlpha by animateFloatAsState(
        targetValue = (dragOffsetX.value / fastReplyTriggerThreshold).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 150)
    )

    val iconSize = 36.dp

    Box(
        modifier = modifier
            .offset(x = if (isOutgoing) iconSize else maxWidth)
            .size(iconSize)
            .graphicsLayer {
                translationX = if (isOutgoing) (-dragOffsetX.value - iconSize.toPx()) * 0.5f else 0f
                scaleX = iconScale
                scaleY = iconScale
                alpha = iconAlpha
            }
            .background(
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape
            )
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Reply,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.fillMaxSize()
        )
    }
}