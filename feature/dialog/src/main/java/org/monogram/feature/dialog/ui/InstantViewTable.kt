package org.monogram.feature.dialog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.core.models.InstantViewBlock
import org.monogram.core.models.InstantViewPages

@Composable
internal fun InstantViewTable(
    block: InstantViewBlock.Table,
    scale: Float,
    query: String,
    onOpenUrl: (String) -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val stripe = MaterialTheme.colorScheme.surfaceContainerHigh
    val surface = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current
    val placed = remember(block.rows) { InstantViewPages.placeTable(block.rows) }
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        block.title?.let { InstantViewRich(it, scale, query, onOpenUrl) }
        SubcomposeLayout { _ ->
            val colW = density.run { 120.dp.roundToPx() }
            val minRowH = density.run { 36.dp.roundToPx() }
            val colCount = placed.columnCount
            val rowCount = placed.rowCount
            if (colCount == 0 || rowCount == 0 || placed.cells.isEmpty()) {
                return@SubcomposeLayout layout(0, 0) {}
            }
            val cellContent: @Composable () -> Unit = {
                placed.cells.forEach { item ->
                    val cell = item.cell
                    Box(
                        Modifier
                            .then(
                                if (block.bordered) {
                                    Modifier.padding(0.5.dp).background(outline).padding(1.dp)
                                        .background(
                                            if (block.striped && item.row % 2 == 1) stripe else surface,
                                        )
                                } else if (block.striped && item.row % 2 == 1) {
                                    Modifier.background(stripe)
                                } else {
                                    Modifier
                                },
                            )
                            .padding(8.dp),
                    ) {
                        InstantViewText(
                            cell.text,
                            cell.entities,
                            MaterialTheme.typography.bodySmall.copy(
                                fontSize = (13 * scale).sp,
                                fontWeight = if (cell.header) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            query,
                            onOpenUrl,
                        )
                    }
                }
            }
            val measured = subcompose("measure", cellContent).mapIndexed { index, measurable ->
                val item = placed.cells[index]
                val width = colW * item.cell.colspan.coerceAtLeast(1)
                item to measurable.measure(Constraints.fixedWidth(width))
            }
            val rowHeights = IntArray(rowCount) { minRowH }
            measured.forEach { (item, placeable) ->
                val rowspan = item.cell.rowspan.coerceAtLeast(1)
                val end = (item.row + rowspan).coerceAtMost(rowCount)
                if (item.row < end) {
                    val current = (item.row until end).sumOf { rowHeights[it] }
                    val extra = placeable.height - current
                    if (extra > 0) {
                        rowHeights[end - 1] += extra
                    }
                }
            }
            val placeables = subcompose("place", cellContent).mapIndexed { index, measurable ->
                val item = placed.cells[index]
                val width = colW * item.cell.colspan.coerceAtLeast(1)
                val rowspan = item.cell.rowspan.coerceAtLeast(1)
                val end = (item.row + rowspan).coerceAtMost(rowCount)
                val height = (item.row until end).sumOf { rowHeights[it] }.coerceAtLeast(minRowH)
                item to measurable.measure(Constraints.fixed(width, height))
            }
            layout(colW * colCount, rowHeights.sum()) {
                placeables.forEach { (item, placeable) ->
                    placeable.place(item.col * colW, rowHeights.take(item.row).sum())
                }
            }
        }
    }
}
