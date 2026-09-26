package org.monogram.feature.dialog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import org.monogram.core.models.Message
import org.monogram.feature.dialog.albumVisualItems
import org.monogram.network.http.MediaRepository
import kotlin.math.roundToInt

internal val BUBBLE_CONTENT_PAD = 10.dp

@Composable
fun AlbumMosaic(
    messages: List<Message>,
    mediaRepository: MediaRepository?,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val items = remember(messages) { albumVisualItems(messages) }
    if (items.isEmpty()) return
    val plan = remember(items) {
        layoutAlbum(
            items.map { message ->
                mediaAspectRatio(message.mediaWidth, message.mediaHeight)
                    ?: if (message.mediaKind == "video" || message.mediaKind == "gif") {
                        16f / 9f
                    } else {
                        4f / 3f
                    }
            },
        )
    }
    val fallbackWidth = with(LocalDensity.current) { ALBUM_BUBBLE_MAX_DP.dp.roundToPx() }
    val aspect = plan.aspect
    val measurePolicy = remember(plan, fallbackWidth, aspect) {
        object : MeasurePolicy {
            override fun MeasureScope.measure(
                measurables: List<Measurable>,
                constraints: Constraints,
            ): MeasureResult {
                val (width, height) = albumMosaicPixelSize(
                    maxWidth = constraints.maxWidth,
                    minWidth = constraints.minWidth,
                    hasBoundedWidth = constraints.hasBoundedWidth,
                    aspect = aspect,
                    fallbackWidth = fallbackWidth,
                )
                val gap = 1.dp.roundToPx()
                val scaleX = width / plan.width
                val scaleY = height / plan.height
                val placed = measurables.zip(plan.cells).map { (measurable, cell) ->
                    var x = (cell.left * scaleX).roundToInt()
                    var y = (cell.top * scaleY).roundToInt()
                    var w = (cell.width * scaleX).roundToInt()
                    var h = (cell.height * scaleY).roundToInt()
                    if (cell.flags and ALBUM_FLAG_LEFT == 0) {
                        x += gap
                        w -= gap
                    }
                    if (cell.flags and ALBUM_FLAG_TOP == 0) {
                        y += gap
                        h -= gap
                    }
                    val placeable = measurable.measure(
                        Constraints.fixed(
                            w.coerceIn(1, MOSAIC_CONSTRAINT_MAX),
                            h.coerceIn(1, MOSAIC_CONSTRAINT_MAX),
                        ),
                    )
                    Triple(placeable, x, y)
                }
                return layout(width, height) {
                    placed.forEach { (placeable, x, y) ->
                        placeable.place(x, y)
                    }
                }
            }

            override fun IntrinsicMeasureScope.minIntrinsicWidth(
                measurables: List<IntrinsicMeasurable>,
                height: Int,
            ): Int = 0

            override fun IntrinsicMeasureScope.maxIntrinsicWidth(
                measurables: List<IntrinsicMeasurable>,
                height: Int,
            ): Int = fallbackWidth

            override fun IntrinsicMeasureScope.minIntrinsicHeight(
                measurables: List<IntrinsicMeasurable>,
                width: Int,
            ): Int = mosaicHeight(width)

            override fun IntrinsicMeasureScope.maxIntrinsicHeight(
                measurables: List<IntrinsicMeasurable>,
                width: Int,
            ): Int = mosaicHeight(width)

            private fun mosaicHeight(width: Int): Int =
                albumMosaicPixelSize(
                    maxWidth = width,
                    minWidth = 0,
                    hasBoundedWidth = width in 1 until Constraints.Infinity,
                    aspect = aspect,
                    fallbackWidth = fallbackWidth,
                ).second
        }
    }
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .then(if (shape != null) Modifier.clip(shape) else Modifier),
        content = {
            CompositionLocalProvider(LocalAlbumMessages provides items) {
            items.forEach { message ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    MessageMedia(
                        message = message,
                        mediaRepository = mediaRepository,
                        fillBounds = true,
                        previewOnly = true,
                        onLongPress = onLongPress,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            }
        },
        measurePolicy = measurePolicy,
    )
}
