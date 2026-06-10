package org.monogram.presentation.features.chats.conversation.ui.message

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.max

@Composable
internal fun TextWithTimestampLayout(
    modifier: Modifier = Modifier,
    horizontalPadding: Int = 8,
    textContent: @Composable () -> Unit,
    timestampContent: @Composable () -> Unit
) {
    Layout(
        contents = listOf(textContent, timestampContent),
        modifier = modifier
    ) { (textMeasurables, timestampMeasurables), constraints ->
        val horizontalPaddingPx = horizontalPadding.dp.roundToPx()

        val timestampPlaceable = timestampMeasurables.first().measure(Constraints())
        val timestampWidth = timestampPlaceable.width
        val timestampHeight = timestampPlaceable.height

        val textPlaceable = textMeasurables.first().measure(constraints.copy(minWidth = 0))
        val textWidth = textPlaceable.width
        val textHeight = textPlaceable.height

        val totalInlineWidth = textWidth + horizontalPaddingPx + timestampWidth
        val fitsInline = totalInlineWidth <= constraints.maxWidth

        if (fitsInline) {
            val layoutWidth = max(totalInlineWidth, textWidth)
            val layoutHeight = max(textHeight, timestampHeight)

            layout(layoutWidth, layoutHeight) {
                textPlaceable.placeRelative(0, 0)
                timestampPlaceable.placeRelative(
                    x = layoutWidth - timestampWidth,
                    y = layoutHeight - timestampHeight
                )
            }
        } else {
            val layoutWidth = max(textWidth, timestampWidth)
            val layoutHeight = textHeight + timestampHeight

            layout(layoutWidth, layoutHeight) {
                textPlaceable.placeRelative(0, 0)
                timestampPlaceable.placeRelative(
                    x = layoutWidth - timestampWidth,
                    y = textHeight
                )
            }
        }
    }
}
