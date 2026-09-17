package org.monogram.core.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider

val AppMenuScreenMargin: Dp = 12.dp
val AppMenuSystemBarInset: Dp = 48.dp
val AppMenuAnchorGap: Dp = 6.dp

@Immutable
enum class AppMenuGrowth { Below, Above }

@Immutable
data class AppMenuPlacement(val offset: IntOffset, val growth: AppMenuGrowth)

fun appMenuPlacement(
    anchorLeft: Int,
    anchorTop: Int,
    anchorRight: Int,
    anchorBottom: Int,
    windowWidth: Int,
    windowHeight: Int,
    popupWidth: Int,
    popupHeight: Int,
    alignToAnchorEnd: Boolean,
    margin: Int,
    gap: Int,
    topInset: Int,
    bottomInset: Int,
): AppMenuPlacement {
    val maxX = (windowWidth - popupWidth - margin).coerceAtLeast(margin)
    val x = if (alignToAnchorEnd) {
        (anchorRight - popupWidth).coerceIn(margin, maxX)
    } else {
        anchorLeft.coerceIn(margin, maxX)
    }
    val topGuard = topInset + margin
    val bottomLimit = (windowHeight - bottomInset - margin).coerceAtLeast(topGuard)
    val below = anchorBottom + gap
    val above = anchorTop - popupHeight - gap
    return when {
        below + popupHeight <= bottomLimit -> AppMenuPlacement(IntOffset(x, below), AppMenuGrowth.Below)
        above >= topGuard -> AppMenuPlacement(IntOffset(x, above), AppMenuGrowth.Above)
        else -> AppMenuPlacement(
            offset = IntOffset(x, (bottomLimit - popupHeight).coerceAtLeast(topGuard)),
            growth = AppMenuGrowth.Above,
        )
    }
}

fun appMenuOffset(
    anchorLeft: Int,
    anchorTop: Int,
    anchorRight: Int,
    anchorBottom: Int,
    windowWidth: Int,
    windowHeight: Int,
    popupWidth: Int,
    popupHeight: Int,
    alignToAnchorEnd: Boolean,
    margin: Int,
    gap: Int,
    topInset: Int,
    bottomInset: Int,
): Pair<Int, Int> {
    val placement = appMenuPlacement(
        anchorLeft, anchorTop, anchorRight, anchorBottom,
        windowWidth, windowHeight, popupWidth, popupHeight,
        alignToAnchorEnd, margin, gap, topInset, bottomInset,
    )
    return placement.offset.x to placement.offset.y
}

class AppMenuPlacementState {
    internal var lastPlacement by mutableStateOf<AppMenuPlacement?>(null)
    val growth: AppMenuGrowth? get() = lastPlacement?.growth
}

@Composable
fun rememberAppMenuPositionProvider(
    touch: Offset? = null,
    alignToAnchorEnd: Boolean = false,
    margin: Dp = AppMenuScreenMargin,
    gap: Dp = AppMenuAnchorGap,
    topInset: Dp = AppMenuSystemBarInset,
    bottomInset: Dp = AppMenuSystemBarInset,
    placementState: AppMenuPlacementState? = null,
): PopupPositionProvider {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val marginPx = with(density) { margin.roundToPx() }
    val gapPx = with(density) { gap.roundToPx() }
    val topPx = with(density) { topInset.roundToPx() }
    val bottomPx = with(density) { bottomInset.roundToPx() }
    val alignEnd = alignToAnchorEnd xor rtl
    return remember(alignEnd, marginPx, gapPx, topPx, bottomPx, touch, placementState) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize,
            ): IntOffset {
                val placement = appMenuPlacement(
                    anchorLeft = touch?.x?.toInt() ?: anchorBounds.left,
                    anchorTop = touch?.y?.toInt() ?: anchorBounds.top,
                    anchorRight = touch?.x?.toInt() ?: anchorBounds.right,
                    anchorBottom = touch?.y?.toInt() ?: anchorBounds.bottom,
                    windowWidth = windowSize.width,
                    windowHeight = windowSize.height,
                    popupWidth = popupContentSize.width,
                    popupHeight = popupContentSize.height,
                    alignToAnchorEnd = alignEnd,
                    margin = marginPx,
                    gap = gapPx,
                    topInset = topPx,
                    bottomInset = bottomPx,
                )
                placementState?.let { state ->
                    if (state.lastPlacement != placement) state.lastPlacement = placement
                }
                return placement.offset
            }
        }
    }
}
