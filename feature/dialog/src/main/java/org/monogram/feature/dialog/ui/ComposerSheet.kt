package org.monogram.feature.dialog.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun composerPanelHeight(): Dp {
    val screen = LocalConfiguration.current.screenHeightDp
    return (screen * 0.5f).dp.coerceIn(320.dp, 560.dp)
}
