package org.monogram.feature.chats.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@Composable
internal fun folderSwipeModifier(
    folderIds: List<Int?>,
    selectedId: Int?,
    enabled: Boolean,
    onSelect: (Int?) -> Unit,
): Modifier {
    val latestSelect by rememberUpdatedState(onSelect)
    val latestFolderIds by rememberUpdatedState(folderIds)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val translation by animateFloatAsState(
        targetValue = drag * 0.15f,
        animationSpec = if (dragging) snap() else spring(),
        label = "folderSwipe",
    )
    return Modifier.pointerInput(folderIds.size, selectedId, enabled, rtl) {
        val ids = latestFolderIds
        if (!enabled || ids.size < 2) return@pointerInput
        val selectedIndex = ids.indexOf(selectedId)
        if (selectedIndex < 0) return@pointerInput
        try {
            detectHorizontalDragGestures(
                onDragStart = { dragging = true; drag = 0f },
                onDragCancel = { dragging = false; drag = 0f },
                onDragEnd = {
                    val live = latestFolderIds
                    val from = live.indexOf(selectedId).takeIf { it >= 0 } ?: selectedIndex
                    val target = swipedFolderIndex(
                        from, live.size, drag,
                        threshold = maxOf(64.dp.toPx(), size.width * 0.18f),
                        rtl = rtl,
                    )
                    dragging = false
                    drag = 0f
                    if (target != from) latestSelect(live[target])
                },
            ) { change, amount ->
                change.consume()
                drag = (drag + amount).coerceIn(-size.width.toFloat(), size.width.toFloat())
            }
        } finally {
            dragging = false
            drag = 0f
        }
    }.graphicsLayer { translationX = translation }
}

internal fun swipedFolderIndex(
    selectedIndex: Int,
    folderCount: Int,
    distance: Float,
    threshold: Float,
    rtl: Boolean,
): Int {
    if (selectedIndex !in 0 until folderCount || threshold <= 0f) return selectedIndex
    val logicalDistance = if (rtl) -distance else distance
    return when {
        logicalDistance <= -threshold -> (selectedIndex + 1).coerceAtMost(folderCount - 1)
        logicalDistance >= threshold -> (selectedIndex - 1).coerceAtLeast(0)
        else -> selectedIndex
    }
}
