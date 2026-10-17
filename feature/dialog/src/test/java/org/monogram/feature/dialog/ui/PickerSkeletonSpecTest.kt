package org.monogram.feature.dialog.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerSkeletonSpecTest {
    @Test
    fun emojiTabPlaceholderMatchesTheEmojiGrid() {
        val spec = pickerSkeletonSpec(PickerSkeletonKind.Emoji)

        assertEquals(48, PickerMetrics.EmojiCell)
        assertEquals(PickerMetrics.EmojiCell, spec.cellDp)
        // Category and emoji pack chips sit above the emoji grid.
        assertTrue(spec.packChips)
        assertFalse(spec.packTitle)
    }

    @Test
    fun stickerTabPlaceholderMatchesThePackGrid() {
        val spec = pickerSkeletonSpec(PickerSkeletonKind.Stickers)

        assertEquals(76, PickerMetrics.StickerCell)
        assertEquals(PickerMetrics.StickerCell, spec.cellDp)
        assertTrue(spec.packChips)
        // The sticker tab labels every pack, so the placeholder reserves that line too.
        assertTrue(spec.packTitle)
    }

    @Test
    fun gifTabPlaceholderUsesGifTiles() {
        val spec = pickerSkeletonSpec(PickerSkeletonKind.Gifs)

        assertEquals(120, PickerMetrics.GifCell)
        assertEquals(PickerMetrics.GifCell, spec.cellDp)
        assertFalse(spec.packChips)
        assertFalse(spec.packTitle)
    }

    @Test
    fun openedPacksUseTheSameCellsAsTheirContent() {
        val emojiPack = pickerSkeletonSpec(PickerSkeletonKind.EmojiPack)
        val stickerPack = pickerSkeletonSpec(PickerSkeletonKind.StickerPack)

        assertEquals(56, PickerMetrics.EmojiPackCell)
        assertEquals(PickerMetrics.EmojiPackCell, emojiPack.cellDp)
        assertEquals(76, PickerMetrics.StickerCell)
        assertEquals(PickerMetrics.StickerCell, stickerPack.cellDp)
        // A pack covers the whole panel: no chips or title of its own.
        for (spec in listOf(emojiPack, stickerPack)) {
            assertFalse(spec.packChips)
            assertFalse(spec.packTitle)
        }
        assertTrue(emojiPack.cellDp < stickerPack.cellDp)
    }

    @Test
    fun placeholderChipRowIsBigEnoughToShowPacks() {
        // Chips are the same size as the real pack buttons, and the row holds a screenful.
        assertEquals(48, PickerMetrics.ChipSize)
        assertEquals(56, PickerMetrics.ChipRowHeight)
        assertTrue(PickerMetrics.PlaceholderChips >= 6)
        assertTrue(PickerMetrics.PlaceholderCells >= PickerMetrics.PlaceholderChips)
    }
}
