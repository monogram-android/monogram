package org.monogram.feature.dialog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.components.MonogramPlaceholder
import org.monogram.feature.dialog.R

/**
 * Cell sizes and paddings shared by the picker grids and their loading placeholders. The
 * placeholder is built from the same numbers as the real grid, so the menu never jumps when the
 * content arrives.
 */
internal object PickerMetrics {
    const val EmojiCell = 48
    const val EmojiPackCell = 56
    const val StickerCell = 76
    const val GifCell = 120
    const val ChipSize = 48
    const val ChipRowHeight = 56
    const val GridSpacing = 4
    const val GridPadding = 8
    const val PlaceholderCells = 32
    const val PlaceholderChips = 8
}

internal enum class PickerSkeletonKind { Emoji, Stickers, Gifs, EmojiPack, StickerPack }

/** What a placeholder has to draw to stand in for the tab or pack view it covers. */
internal data class PickerSkeletonSpec(
    val cellDp: Int,
    val packChips: Boolean,
    val packTitle: Boolean,
)

internal fun pickerSkeletonSpec(kind: PickerSkeletonKind): PickerSkeletonSpec = when (kind) {
    // Emoji tab: category chips and pack chips above an emoji-sized grid.
    PickerSkeletonKind.Emoji -> PickerSkeletonSpec(
        cellDp = PickerMetrics.EmojiCell,
        packChips = true,
        packTitle = false,
    )
    // Stickers tab: pack chips, then one pack title above a sticker-sized grid.
    PickerSkeletonKind.Stickers -> PickerSkeletonSpec(
        cellDp = PickerMetrics.StickerCell,
        packChips = true,
        packTitle = true,
    )
    PickerSkeletonKind.Gifs -> PickerSkeletonSpec(
        cellDp = PickerMetrics.GifCell,
        packChips = false,
        packTitle = false,
    )
    // An opened emoji pack is the same grid the emoji tab shows inside a pack.
    PickerSkeletonKind.EmojiPack -> PickerSkeletonSpec(
        cellDp = PickerMetrics.EmojiPackCell,
        packChips = false,
        packTitle = false,
    )
    PickerSkeletonKind.StickerPack -> PickerSkeletonSpec(
        cellDp = PickerMetrics.StickerCell,
        packChips = false,
        packTitle = false,
    )
}

/** Content padding for picker grids and their placeholders, so cells line up exactly. */
internal fun PickerGridPadding(top: Dp = PickerMetrics.GridSpacing.dp): PaddingValues = PaddingValues(
    start = PickerMetrics.GridPadding.dp,
    end = PickerMetrics.GridPadding.dp,
    top = top,
    bottom = PickerTabClearance,
)

/** Content padding for the pack and category chip rows. */
internal fun PickerChipPadding(): PaddingValues = PaddingValues(
    horizontal = PickerMetrics.GridPadding.dp,
    vertical = PickerMetrics.GridSpacing.dp,
)

/** Shimmering chip slots for the category and pack row. */
@Composable
internal fun PickerPlaceholderChips(
    modifier: Modifier = Modifier,
    count: Int = PickerMetrics.PlaceholderChips,
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .height(PickerMetrics.ChipRowHeight.dp),
        contentPadding = PickerChipPadding(),
        horizontalArrangement = Arrangement.spacedBy(PickerMetrics.GridSpacing.dp),
        verticalAlignment = Alignment.CenterVertically,
        userScrollEnabled = false,
    ) {
        items(count) {
            MonogramPlaceholder(
                modifier = Modifier.size(PickerMetrics.ChipSize.dp),
                shape = MaterialTheme.shapes.medium,
            )
        }
    }
}

/**
 * Loading placeholder for the emoji, sticker and GIF menus: the same grid, chips and pack title the
 * loaded menu shows, filled with shimmering cells.
 */
@Composable
internal fun PickerSkeleton(
    kind: PickerSkeletonKind,
    modifier: Modifier = Modifier,
) {
    val spec = pickerSkeletonSpec(kind)
    val description = stringResource(R.string.dialog_media_loading)
    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = description },
    ) {
        if (spec.packChips) {
            PickerPlaceholderChips()
        }
        if (spec.packTitle) {
            MonogramPlaceholder(
                modifier = Modifier
                    .padding(start = PickerMetrics.GridPadding.dp, top = PickerMetrics.GridPadding.dp)
                    .fillMaxWidth(0.42f)
                    .height(14.dp),
                shape = RoundedCornerShape(6.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(spec.cellDp.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PickerGridPadding(),
            horizontalArrangement = Arrangement.spacedBy(PickerMetrics.GridSpacing.dp),
            verticalArrangement = Arrangement.spacedBy(PickerMetrics.GridSpacing.dp),
        ) {
            items(PickerMetrics.PlaceholderCells) {
                MonogramPlaceholder(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(spec.cellDp.dp),
                    shape = MaterialTheme.shapes.small,
                )
            }
        }
    }
}
