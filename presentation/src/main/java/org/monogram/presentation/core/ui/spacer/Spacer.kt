package org.monogram.presentation.core.ui.spacer

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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

/**
 * Simple height spacer
 *
 * @param heightDp height in DP
 **/
@NonRestartableComposable
@Composable
fun HeightSpacer(heightDp: Dp) {
    Spacer(modifier = Modifier.height(heightDp))
}

/**
 * Simple weight spacer
 *
 * @param weight weight in float valur
 **/
@NonRestartableComposable
@Composable
fun ColumnScope.WeightSpacer(weight: Float) {
    Spacer(modifier = Modifier.weight(weight))
}