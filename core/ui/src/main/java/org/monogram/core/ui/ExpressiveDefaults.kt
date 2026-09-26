package org.monogram.core.ui

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object ExpressiveDefaults {
    val PressRadius: Dp = 16.dp

    @Composable
    fun largeButtonShapes(): ButtonShapes =
        ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight)

    @Composable
    fun extraLargeButtonShapes(): ButtonShapes =
        ButtonDefaults.shapesFor(ButtonDefaults.LargeContainerHeight)

    @Composable
    fun buttonShapesFor(height: Dp): ButtonShapes =
        ButtonDefaults.shapesFor(height)

    @Composable
    fun iconButtonShapes(): IconButtonShapes = IconButtonDefaults.shapes()

    /** Titles, sender names and action labels, one step heavier than the M3 scale. */
    @Composable
    fun titleSemiBold(): TextStyle =
        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)

    @Composable
    fun nameSemiBold(): TextStyle =
        MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)

    @Composable
    fun actionSemiBold(): TextStyle =
        MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)

    /** [MaterialTheme.typography.labelLarge] with tabular figures for coordinate readouts. */
    @Composable
    fun tabularLabel(): TextStyle =
        MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
}
