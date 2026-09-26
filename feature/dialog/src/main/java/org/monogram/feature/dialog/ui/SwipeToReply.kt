package org.monogram.feature.dialog.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.monogram.feature.dialog.R
import kotlin.math.abs

@Composable
internal fun SwipeToReply(
    enabled: Boolean,
    onReply: () -> Unit,
    content: @Composable () -> Unit,
) {
    val latestReply by rememberUpdatedState(onReply)
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) -1f else 1f
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    var offset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val translation by animateFloatAsState(
        targetValue = offset,
        animationSpec = if (dragging) snap() else spring(),
        label = "replySwipe",
    )
    val replyLabel = stringResource(R.string.dialog_reply)
    Box(
        Modifier.fillMaxWidth()
            .semantics {
                if (enabled) customActions = listOf(CustomAccessibilityAction(replyLabel) {
                    latestReply()
                    true
                })
            }
            .pointerInput(enabled, direction, threshold) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var crossed = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                            val delta = change.position - down.position
                            if (!dragging) {
                                if (!change.pressed) break
                                if (abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) >= abs(delta.x)) break
                                if (abs(delta.x) <= viewConfiguration.touchSlop) continue
                                if (delta.x * direction <= 0f) break
                                dragging = true
                            }
                            val distance = (delta.x * direction - viewConfiguration.touchSlop).coerceAtLeast(0f)
                            offset = distance.coerceAtMost(threshold * 1.35f) * direction
                            val ready = replySwipeReady(distance, threshold)
                            if (ready && !crossed) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            crossed = ready
                            change.consume()
                            if (!change.pressed) {
                                if (ready) latestReply()
                                break
                            }
                        }
                    } finally {
                        dragging = false
                        offset = 0f
                    }
                }
            },
    ) {
        if (abs(translation) > 1f) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Reply,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp)
                    .graphicsLayer { alpha = (abs(translation) / threshold).coerceIn(0f, 1f) },
            )
        }
        Box(Modifier.fillMaxWidth().graphicsLayer { translationX = translation }) { content() }
    }
}

internal fun replySwipeReady(distance: Float, threshold: Float): Boolean =
    threshold > 0f && distance >= threshold
