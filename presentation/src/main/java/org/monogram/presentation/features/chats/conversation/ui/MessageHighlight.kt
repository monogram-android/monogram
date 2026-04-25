package org.monogram.presentation.features.chats.conversation.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun MessageHighlightContainer(
    highlighted: Boolean,
    onHighlightConsumed: () -> Unit,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val highlightAccent = if (isDarkTheme) Color(0xFFFFD54F) else Color(0xFFFFB300)
    val highlightBackground = highlightAccent.copy(alpha = if (isDarkTheme) 0.22f else 0.18f)
    val highlightBorder = highlightAccent.copy(alpha = 0.95f)
    val backgroundColor = remember { androidx.compose.animation.Animatable(Color.Transparent) }
    val borderAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }

    LaunchedEffect(highlighted) {
        if (!highlighted) return@LaunchedEffect

        backgroundColor.animateTo(highlightBackground, animationSpec = tween(220))
        borderAlpha.animateTo(1f, animationSpec = tween(180))
        scale.animateTo(1.012f, animationSpec = tween(220))

        delay(950)

        scale.animateTo(1f, animationSpec = tween(260))
        borderAlpha.animateTo(0f, animationSpec = tween(1800))
        backgroundColor.animateTo(Color.Transparent, animationSpec = tween(2200))

        onHighlightConsumed()
    }

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .background(backgroundColor.value, shape)
            .border(
                width = 1.5.dp,
                color = highlightBorder.copy(alpha = borderAlpha.value),
                shape = shape
            )
    ) {
        content()
    }
}
