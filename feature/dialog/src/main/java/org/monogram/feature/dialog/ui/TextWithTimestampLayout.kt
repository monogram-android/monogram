package org.monogram.feature.dialog.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

internal data class MessageTextLayoutInfo(
    val width: Int,
    val height: Int,
    val lineCount: Int,
    val lastLineLeft: Float,
    val lastLineRight: Float,
    val lastLineBottom: Float,
    val lastLineDirection: ResolvedTextDirection,
)

internal fun TextLayoutResult.toMessageTextLayoutInfo(): MessageTextLayoutInfo {
    val lastLineIndex = (lineCount - 1).coerceAtLeast(0)
    val lineStart = getLineStart(lastLineIndex)
    return MessageTextLayoutInfo(
        width = size.width,
        height = size.height,
        lineCount = lineCount,
        lastLineLeft = getLineLeft(lastLineIndex),
        lastLineRight = getLineRight(lastLineIndex),
        lastLineBottom = getLineBottom(lastLineIndex),
        lastLineDirection = getParagraphDirection(lineStart),
    )
}

internal enum class FooterPlacementMode {
    Inline,
    Stacked,
}

internal data class FooterPlacement(
    val mode: FooterPlacementMode,
    val layoutWidth: Int,
    val layoutHeight: Int,
    val footerX: Int,
    val footerY: Int,
)

@Composable
internal fun TextWithTimestampLayout(
    textLayoutInfo: MessageTextLayoutInfo?,
    modifier: Modifier = Modifier,
    horizontalPadding: Int = 8,
    stackedTopPadding: Int = 2,
    textContent: @Composable () -> Unit,
    timestampContent: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(textContent, timestampContent),
        modifier = modifier,
    ) { (textMeasurables, timestampMeasurables), constraints ->
        val horizontalPaddingPx = horizontalPadding.dp.roundToPx()
        val stackedTopPaddingPx = stackedTopPadding.dp.roundToPx()

        val timestampPlaceable = timestampMeasurables.first().measure(Constraints())
        val timestampWidth = timestampPlaceable.width
        val timestampHeight = timestampPlaceable.height

        val textPlaceable = textMeasurables.first().measure(constraints.copy(minWidth = 0))
        val placement = calculateFooterPlacement(
            textLayoutInfo = textLayoutInfo,
            textWidth = textPlaceable.width,
            textHeight = textPlaceable.height,
            footerWidth = timestampWidth,
            footerHeight = timestampHeight,
            minWidth = constraints.minWidth,
            maxWidth = constraints.maxWidth,
            horizontalPadding = horizontalPaddingPx,
            stackedTopPadding = stackedTopPaddingPx,
        )

        layout(placement.layoutWidth, placement.layoutHeight) {
            textPlaceable.place(x = 0, y = 0)
            timestampPlaceable.place(
                x = placement.footerX,
                y = placement.footerY,
            )
        }
    }
}

internal fun calculateFooterPlacement(
    textLayoutInfo: MessageTextLayoutInfo?,
    textWidth: Int,
    textHeight: Int,
    footerWidth: Int,
    footerHeight: Int,
    minWidth: Int = 0,
    maxWidth: Int,
    horizontalPadding: Int,
    stackedTopPadding: Int,
): FooterPlacement {
    val effectiveMaxWidth = if (maxWidth == Constraints.Infinity) Int.MAX_VALUE else maxWidth
    val effectiveMinWidth = minWidth.coerceIn(0, effectiveMaxWidth)

    if (textLayoutInfo != null) {
        val lastLineBottom = ceil(textLayoutInfo.lastLineBottom).toInt()
        val footerY = (lastLineBottom - footerHeight).coerceIn(
            minimumValue = 0,
            maximumValue = max(textHeight - footerHeight, 0),
        )

        if (textLayoutInfo.lastLineDirection == ResolvedTextDirection.Rtl) {
            val footerX = floor(textLayoutInfo.lastLineLeft).toInt() - horizontalPadding - footerWidth
            if (footerX >= 0) {
                return FooterPlacement(
                    mode = FooterPlacementMode.Inline,
                    layoutWidth = max(textWidth, footerX + footerWidth),
                    layoutHeight = max(textHeight, footerY + footerHeight),
                    footerX = footerX,
                    footerY = footerY,
                )
            }
        } else {
            val minimumFooterX = ceil(textLayoutInfo.lastLineRight).toInt() + horizontalPadding
            val minimumFooterRight = minimumFooterX + footerWidth
            val layoutWidth = max(max(textWidth, minimumFooterRight), effectiveMinWidth)
            val footerX = max(minimumFooterX, layoutWidth - footerWidth)
            val footerRight = footerX + footerWidth
            if (footerRight <= effectiveMaxWidth) {
                return FooterPlacement(
                    mode = FooterPlacementMode.Inline,
                    layoutWidth = max(layoutWidth, footerRight),
                    layoutHeight = max(textHeight, footerY + footerHeight),
                    footerX = footerX,
                    footerY = footerY,
                )
            }
        }
    }

    val layoutWidth = max(max(textWidth, footerWidth), effectiveMinWidth)
    return FooterPlacement(
        mode = FooterPlacementMode.Stacked,
        layoutWidth = layoutWidth,
        layoutHeight = textHeight + stackedTopPadding + footerHeight,
        footerX = layoutWidth - footerWidth,
        footerY = textHeight + stackedTopPadding,
    )
}
