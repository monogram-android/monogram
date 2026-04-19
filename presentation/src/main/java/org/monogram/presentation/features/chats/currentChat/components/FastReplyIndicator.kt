package org.monogram.presentation.features.chats.currentChat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.pow

const val REPLY_TRIGGER_FRACTION = 0.4f
const val MAX_SWIPE_FRACTION = 0.65f
const val ICON_OFFSET_FRACTION = 0.1f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FastReplyIndicator(
    modifier: Modifier = Modifier,
    dragOffsetX: Animatable<Float, AnimationVector1D>,
    isOutgoing: Boolean = false,
    inverseOffset: Boolean = false,
    maxWidth: Dp,
) {
    val triggerDistance = maxWidth.value * REPLY_TRIGGER_FRACTION
    val dragged = (-dragOffsetX.value).coerceAtLeast(0f)

    val startOffset = 48.dp.value
    val effectiveDrag = (dragged - startOffset).coerceAtLeast(0f)
    val effectiveRange = (triggerDistance - startOffset).coerceAtLeast(1f)

    val progress = (effectiveDrag / effectiveRange).coerceIn(0f, 1f).pow(1.3f)

    val animatedProgress by
        animateFloatAsState(
            targetValue = progress,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )

    val iconOffset = maxWidth * ICON_OFFSET_FRACTION

    Box(
        modifier = modifier
            .offset(x = iconOffset)
            .size(34.dp)
            .graphicsLayer {
                translationX = when {
                    isOutgoing -> (-dragOffsetX.value - iconOffset.value) * 0.5f
                    inverseOffset -> -iconOffset.value
                    else -> iconOffset.value
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = dragged > startOffset,
            enter = fadeIn() + scaleIn(initialScale = 0.6f),
            exit = fadeOut() + scaleOut(targetScale = 0.6f)
        ) {
            Box(
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularWavyProgressIndicator(
                    progress = { animatedProgress },
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

fun Modifier.fastReplyPointer(
    canReply: Boolean,
    dragOffsetX: Animatable<Float, AnimationVector1D>,
    scope: CoroutineScope,
    onReplySwipe: () -> Unit,
    maxWidth: Float
): Modifier = pointerInput(canReply) {
    if (!canReply) return@pointerInput

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var isDragging = false
        var totalDragX = 0f

        while (down.pressed) {
            val event = awaitPointerEvent(pass = PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break

            if (change.changedToUp()) break

            val deltaX = change.positionChange().x
            totalDragX += deltaX

            if (!isDragging) {
                if (totalDragX < -48.dp.toPx()) {
                    isDragging = true
                } else if (totalDragX > 48.dp.toPx()) {
                    break
                }
            }

            if (isDragging) {
                change.consume()
                val newOffset = dragOffsetX.value + deltaX
                scope.launch {
                    dragOffsetX.snapTo(newOffset.coerceIn(-(maxWidth * MAX_SWIPE_FRACTION), 0f))
                }
            }
        }

        if (isDragging) {
            if (-dragOffsetX.value >= maxWidth * REPLY_TRIGGER_FRACTION) {
                onReplySwipe()
            }
            scope.launch {
                dragOffsetX.animateTo(
                    0f,
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
        }
    }
}