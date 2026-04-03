package org.monogram.presentation.core.ui.spacer

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * Simple width spacer
 *
 * @param widthDp width in DP
 **/
@NonRestartableComposable
@Composable
fun WidthSpacer(widthDp: Dp) {
    Spacer(modifier = Modifier.width(widthDp))
}