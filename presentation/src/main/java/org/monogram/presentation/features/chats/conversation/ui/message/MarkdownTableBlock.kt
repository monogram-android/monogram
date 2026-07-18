package org.monogram.presentation.features.chats.conversation.ui.message

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun MarkdownTableBlock(
    text: String,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier
) {
    val rows = remember(text) { parseMarkdownTableRows(text) }
    if (rows.isEmpty()) {
        CodeBlock(
            text = text,
            language = "table",
            isOutgoing = isOutgoing,
            modifier = modifier
        )
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val tableWidth = remember(rows, maxWidth) {
            resolveMarkdownTableWidth(
                availableWidth = maxWidth,
                columnCount = rows.maxOfOrNull { it.size } ?: 1
            )
        }
        val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

        Box(
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier
                    .width(tableWidth)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(12.dp)
            ) {
                rows.forEachIndexed { rowIndex, row ->
                    val isLastRow = rowIndex == rows.lastIndex
                    val rowBackground = when {
                        rowIndex == 0 -> MaterialTheme.colorScheme.surfaceContainerHighest
                        rowIndex % 2 == 1 -> MaterialTheme.colorScheme.surfaceContainer
                        else -> Color.Transparent
                    }

                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawBehind {
                                if (!isLastRow) {
                                    val lineWidth = 1.dp.toPx()
                                    drawLine(
                                        color = dividerColor,
                                        start = Offset(0f, size.height - lineWidth / 2f),
                                        end = Offset(size.width, size.height - lineWidth / 2f),
                                        strokeWidth = lineWidth
                                    )
                                }
                            }
                    ) {
                        row.forEachIndexed { cellIndex, cell ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(rowBackground)
                                    .drawBehind {
                                        if (cellIndex < row.lastIndex) {
                                            val lineWidth = 1.dp.toPx()
                                            drawLine(
                                                color = dividerColor,
                                                start = Offset(size.width - lineWidth / 2f, 0f),
                                                end = Offset(
                                                    size.width - lineWidth / 2f,
                                                    size.height
                                                ),
                                                strokeWidth = lineWidth
                                            )
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = cell,
                                    style = if (rowIndex == 0) {
                                        MaterialTheme.typography.labelLarge
                                    } else {
                                        MaterialTheme.typography.bodyMedium
                                    },
                                    fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Start
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun resolveMarkdownTableWidth(
    availableWidth: Dp,
    columnCount: Int
): Dp {
    val columnSpacing = 8.dp
    val minColumnWidth = 120.dp
    val minTableWidth =
        minColumnWidth * columnCount + columnSpacing * (columnCount - 1).coerceAtLeast(0)
    return maxOf(availableWidth, minTableWidth)
}

internal fun parseMarkdownTableRows(text: String): List<List<String>> {
    val lines = text.lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .toList()

    if (lines.isEmpty()) return emptyList()

    val boxRows = lines.filter { it.contains('│') }
    if (boxRows.size >= 2) {
        return boxRows.mapNotNull { line -> parseDelimitedTableRow(line, '│') }
            .takeIf { it.size >= 2 && it.allSameSize() }
            .orEmpty()
    }

    val pipeRows = lines
        .filterNot { isMarkdownTableSeparatorRow(it) }
        .filter { it.contains('|') }

    return pipeRows.mapNotNull { line -> parseDelimitedTableRow(line, '|') }
        .takeIf { it.size >= 2 && it.allSameSize() }
        .orEmpty()
}

private fun parseDelimitedTableRow(line: String, delimiter: Char): List<String>? {
    val parts = line.split(delimiter).map { it.trim() }
    val normalized = when {
        parts.size >= 2 && parts.first().isEmpty() && parts.last().isEmpty() -> parts.drop(1)
            .dropLast(1)

        else -> parts
    }
    return normalized.takeIf { it.size >= 2 }
        ?.let { if (it.any { cell -> cell.isNotEmpty() }) it else null }
}

private fun List<List<String>>.allSameSize(): Boolean {
    val firstSize = firstOrNull()?.size ?: return false
    return all { it.size == firstSize }
}

private fun isMarkdownTableSeparatorRow(line: String): Boolean {
    val compact = line.trim()
    if (compact.isEmpty()) return false
    return compact.all { it == '|' || it == ':' || it == '-' || it.isWhitespace() }
}
