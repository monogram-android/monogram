package org.monogram.feature.chats.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal const val FOLDER_EDGE_RESISTANCE = 0.22f

internal data class FolderSlide(
    val currentId: Int?,
    val incomingId: Int?,
    val currentX: Float,
    val incomingX: Float,
    val hasIncoming: Boolean,
)

@Composable
internal fun FolderTransition(
    folderIds: List<Int?>,
    selectedId: Int?,
    enabled: Boolean,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    motionEnabled: Boolean = true,
    onMoving: (Boolean) -> Unit = {},
    content: @Composable (folderId: Int?, isPrimary: Boolean) -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    val latestSelect by rememberUpdatedState(onSelect)
    val latestIds by rememberUpdatedState(folderIds)
    val latestOnMoving by rememberUpdatedState(onMoving)
    val holder = rememberSaveableStateHolder()
    var displayedId by remember { mutableStateOf(selectedId) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var gestureSettling by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    var offsetPx by remember { mutableFloatStateOf(0f) }
    val anim = remember { Animatable(0f) }
    val settleSpec = remember {
        spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        )
    }

    val visualOffset = if (dragging) {
        folderSwipeOffset(
            drag = drag,
            width = widthPx,
            selectedIndex = latestIds.indexOf(displayedId),
            folderCount = latestIds.size,
            rtl = rtl,
        )
    } else {
        offsetPx
    }
    val slide = folderSlide(
        displayedId = displayedId,
        selectedId = selectedId,
        folderIds = folderIds,
        offset = visualOffset,
        width = widthPx,
        rtl = rtl,
    )
    val moving = dragging || gestureSettling || visualOffset != 0f || selectedId != displayedId
    LaunchedEffect(moving) { latestOnMoving(moving) }

    LaunchedEffect(selectedId, widthPx, motionEnabled, rtl) {
        if (gestureSettling || dragging) return@LaunchedEffect
        if (selectedId == displayedId) return@LaunchedEffect
        if (!motionEnabled || widthPx <= 0f) {
            displayedId = selectedId
            offsetPx = 0f
            anim.snapTo(0f)
            return@LaunchedEffect
        }
        val target = folderSettleOffset(
            fromId = displayedId,
            toId = selectedId,
            folderIds = latestIds,
            width = widthPx,
            rtl = rtl,
        )
        if (target == 0f) {
            displayedId = selectedId
            offsetPx = 0f
            anim.snapTo(0f)
            return@LaunchedEffect
        }
        anim.snapTo(offsetPx)
        anim.animateTo(target, settleSpec) { offsetPx = value }
        displayedId = selectedId
        offsetPx = 0f
        anim.snapTo(0f)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size: IntSize -> widthPx = size.width.toFloat() }
            .pointerInput(enabled, rtl, folderIds.size, motionEnabled) {
                val ids = latestIds
                if (!enabled || ids.size < 2) return@pointerInput
                try {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            gestureSettling = false
                            drag = anim.value
                            offsetPx = drag
                            scope.launch { anim.stop() }
                        },
                        onDragCancel = {
                            dragging = false
                            drag = 0f
                            scope.launch {
                                anim.snapTo(offsetPx)
                                anim.animateTo(0f, settleSpec) { offsetPx = value }
                                offsetPx = 0f
                                anim.snapTo(0f)
                            }
                        },
                        onDragEnd = {
                            val live = latestIds
                            val fromId = displayedId
                            val from = live.indexOf(fromId)
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            val physical = folderSwipeOffset(
                                drag = drag,
                                width = width,
                                selectedIndex = from,
                                folderCount = live.size,
                                rtl = rtl,
                            )
                            val threshold = maxOf(64.dp.toPx(), width * 0.18f)
                            val targetIndex = swipedFolderIndex(
                                selectedIndex = from,
                                folderCount = live.size,
                                distance = physical,
                                threshold = threshold,
                                rtl = rtl,
                            )
                            dragging = false
                            val commit = targetIndex != from && targetIndex in live.indices
                            val targetPx = when {
                                !commit -> 0f
                                physical < 0f -> -width
                                else -> width
                            }
                            if (commit) {
                                gestureSettling = true
                                latestSelect(live[targetIndex])
                            }
                            drag = 0f
                            scope.launch {
                                anim.snapTo(physical)
                                offsetPx = physical
                                if (!motionEnabled) {
                                    if (commit) displayedId = live[targetIndex]
                                    offsetPx = 0f
                                    anim.snapTo(0f)
                                    gestureSettling = false
                                    return@launch
                                }
                                anim.animateTo(targetPx, settleSpec) { offsetPx = value }
                                if (commit) displayedId = live[targetIndex]
                                offsetPx = 0f
                                anim.snapTo(0f)
                                gestureSettling = false
                            }
                        },
                    ) { change, amount ->
                        change.consume()
                        drag = (drag + amount).coerceIn(-size.width.toFloat(), size.width.toFloat())
                    }
                } finally {
                    if (dragging) {
                        dragging = false
                        drag = 0f
                    }
                }
            },
    ) {
        val pages = folderSlidePages(slide)
        pages.forEach { id ->
            val pageKey = id ?: Int.MIN_VALUE
            key(pageKey) {
                val isPrimary = id == slide.currentId
                val x = if (isPrimary) slide.currentX else slide.incomingX
                holder.SaveableStateProvider(pageKey) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationX = x },
                    ) {
                        content(id, isPrimary)
                    }
                }
            }
        }
    }
}

internal fun folderSlide(
    displayedId: Int?,
    selectedId: Int?,
    folderIds: List<Int?>,
    offset: Float,
    width: Float,
    rtl: Boolean,
): FolderSlide {
    val currentIndex = folderIds.indexOf(displayedId)
    if (width <= 0f || currentIndex < 0) {
        return FolderSlide(displayedId, null, 0f, 0f, hasIncoming = false)
    }
    val incomingId: Int?
    val incomingFromRight: Boolean
    val hasIncoming: Boolean
    if (selectedId != displayedId) {
        val selectedIndex = folderIds.indexOf(selectedId)
        if (selectedIndex < 0) {
            return FolderSlide(displayedId, null, offset, 0f, hasIncoming = false)
        }
        val forward = selectedIndex > currentIndex
        incomingId = selectedId
        incomingFromRight = if (rtl) !forward else forward
        hasIncoming = true
    } else if (offset == 0f) {
        return FolderSlide(displayedId, null, 0f, 0f, hasIncoming = false)
    } else {
        incomingFromRight = offset < 0f
        val logicalNext = if (rtl) offset > 0f else offset < 0f
        val neighbor = if (logicalNext) currentIndex + 1 else currentIndex - 1
        hasIncoming = neighbor in folderIds.indices
        incomingId = folderIds.getOrNull(neighbor)
        if (!hasIncoming) {
            return FolderSlide(displayedId, null, offset, 0f, hasIncoming = false)
        }
    }
    val incomingX = if (incomingFromRight) offset + width else offset - width
    return FolderSlide(
        currentId = displayedId,
        incomingId = incomingId,
        currentX = offset,
        incomingX = incomingX,
        hasIncoming = hasIncoming,
    )
}

internal fun folderSlidePages(slide: FolderSlide): List<Int?> = buildList {
    if (slide.hasIncoming && slide.incomingId != slide.currentId) {
        add(slide.incomingId)
    }
    add(slide.currentId)
}

internal fun folderSettleOffset(
    fromId: Int?,
    toId: Int?,
    folderIds: List<Int?>,
    width: Float,
    rtl: Boolean,
): Float {
    if (width <= 0f || fromId == toId) return 0f
    val from = folderIds.indexOf(fromId)
    val to = folderIds.indexOf(toId)
    if (from < 0 || to < 0) return 0f
    val forward = to > from
    val incomingFromRight = if (rtl) !forward else forward
    return if (incomingFromRight) -width else width
}

internal fun folderSwipeOffset(
    drag: Float,
    width: Float,
    selectedIndex: Int,
    folderCount: Int,
    rtl: Boolean,
): Float {
    if (width <= 0f || selectedIndex !in 0 until folderCount) return 0f
    val logical = if (rtl) -drag else drag
    val resisted = when {
        logical > 0f && selectedIndex == 0 -> logical * FOLDER_EDGE_RESISTANCE
        logical < 0f && selectedIndex == folderCount - 1 -> logical * FOLDER_EDGE_RESISTANCE
        else -> logical.coerceIn(-width, width)
    }
    return if (rtl) -resisted else resisted
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
