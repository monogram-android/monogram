package org.monogram.presentation.features.chats.conversation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

internal enum class MessageSelectionBadgeAlignment {
    TopStart,
    TopEnd
}

@Composable
internal fun MessageSelectionDecoration(
    isSelectionMode: Boolean,
    isSelected: Boolean,
    badgeAlignment: MessageSelectionBadgeAlignment,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.wrapContentSize(),
        contentAlignment = Alignment.Center
    ) {
        content()

        AnimatedVisibility(
            visible = isSelectionMode,
            enter = fadeIn(animationSpec = tween(180)) +
                    scaleIn(initialScale = 0.82f, animationSpec = tween(220)) +
                    slideInHorizontally(
                        animationSpec = tween(220),
                        initialOffsetX = { if (badgeAlignment == MessageSelectionBadgeAlignment.TopStart) -it / 3 else it / 3 }
                    ),
            exit = fadeOut(animationSpec = tween(140)) +
                    scaleOut(targetScale = 0.82f, animationSpec = tween(180)) +
                    slideOutHorizontally(
                        animationSpec = tween(180),
                        targetOffsetX = { if (badgeAlignment == MessageSelectionBadgeAlignment.TopStart) -it / 3 else it / 3 }
                    ),
            modifier = Modifier.align(
                if (badgeAlignment == MessageSelectionBadgeAlignment.TopStart) Alignment.TopStart else Alignment.TopEnd
            )
        ) {
            SelectionBadge(
                isSelected = isSelected,
                modifier = Modifier.graphicsLayer {
                    translationY = -10.dp.toPx()
                    translationX = if (badgeAlignment == MessageSelectionBadgeAlignment.TopStart) {
                        -10.dp.toPx()
                    } else {
                        10.dp.toPx()
                    }
                }
            )
        }
    }
}

@Composable
private fun SelectionBadge(
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val iconAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "selectionBadgeIconAlpha"
    )

    Box(
        modifier = modifier
            .size(24.dp)
            .graphicsLayer {
                shadowElevation = 6.dp.toPx()
                shape = CircleShape
                clip = true
            },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            },
            contentColor = if (isSelected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            tonalElevation = 2.dp
        ) {}
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = if (isSelected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
            },
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { alpha = iconAlpha }
        )
    }
}
