package org.monogram.feature.dialog.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import org.monogram.core.ui.menu.AppMenuPlacementState
import org.monogram.core.ui.menu.AppMenuScreenMargin
import org.monogram.core.ui.menu.AppMenuSystemBarInset
import org.monogram.core.ui.menu.appMenuOffset
import org.monogram.core.ui.menu.rememberAppMenuPositionProvider

@Composable
fun rememberMessageMenuPosition(
    outgoing: Boolean,
    touch: Offset? = null,
    placementState: AppMenuPlacementState? = null,
): PopupPositionProvider {
    val density = LocalDensity.current
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    val imeBottom = WindowInsets.ime.getBottom(density)
    val statusTop = WindowInsets.statusBars.getTop(density)
    val composer = with(density) { 72.dp.roundToPx() }
    return rememberAppMenuPositionProvider(
        touch = touch,
        alignToAnchorEnd = outgoing,
        margin = AppMenuScreenMargin,
        gap = 6.dp,
        topInset = with(density) { statusTop.toDp() } + AppMenuSystemBarInset,
        bottomInset = with(density) { (maxOf(navBottom, imeBottom) + composer).toDp() } + AppMenuSystemBarInset,
        placementState = placementState,
    )
}
