package org.monogram.feature.dialog.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class AlbumLayoutTest {
    @Test
    fun twoSimilarLandscapesStack() {
        val plan = layoutAlbum(listOf(16f / 9f, 16f / 9f))
        assertEquals(2, plan.cells.size)
        val top = plan.cells[0]
        val bottom = plan.cells[1]
        assertEquals(0f, top.left, 0.5f)
        assertEquals(plan.width, top.right, 0.5f)
        assertEquals(0f, bottom.left, 0.5f)
        assertEquals(plan.width, bottom.right, 0.5f)
        assertTrue(top.bottom <= bottom.top + 0.5f)
        assertTrue(top.flags and ALBUM_FLAG_TOP != 0)
        assertTrue(bottom.flags and ALBUM_FLAG_BOTTOM != 0)
    }

    @Test
    fun twoSquaresSitSideBySide() {
        val plan = layoutAlbum(listOf(1f, 1f))
        assertEquals(2, plan.cells.size)
        assertEquals(0f, plan.cells[0].top, 0.5f)
        assertEquals(0f, plan.cells[1].top, 0.5f)
        assertEquals(plan.cells[0].right, plan.cells[1].left, 1f)
        assertEquals(plan.width, plan.cells[1].right, 1f)
    }

    @Test
    fun threeLandscapesAreHeroThenPair() {
        val plan = layoutAlbum(listOf(16f / 9f, 16f / 9f, 16f / 9f))
        assertEquals(3, plan.cells.size)
        val hero = plan.cells[0]
        assertEquals(0f, hero.left, 0.5f)
        assertEquals(plan.width, hero.right, 0.5f)
        assertEquals(plan.cells[1].right, plan.cells[2].left, 1f)
        assertTrue(plan.cells[1].top >= hero.bottom - 0.5f)
    }

    @Test
    fun fourLandscapesAreHeroThenRow() {
        val plan = layoutAlbum(listOf(16f / 9f, 16f / 9f, 16f / 9f, 16f / 9f))
        assertEquals(4, plan.cells.size)
        val hero = plan.cells[0]
        assertEquals(0f, hero.left, 0.5f)
        assertEquals(plan.width, hero.right, 0.5f)
        val row = plan.cells.drop(1)
        assertEquals(3, row.size)
        assertEquals(0f, row.first().left, 1f)
        assertEquals(plan.width, row.last().right, 1f)
        row.zipWithNext().forEach { (a, b) ->
            assertEquals(a.right, b.left, 1.5f)
            assertEquals(a.top, b.top, 0.5f)
        }
    }

    @Test
    fun tenItemsCoverTheMosaic() {
        val ratios = List(10) { 16f / 9f }
        val plan = layoutAlbum(ratios)
        assertEquals(10, plan.cells.size)
        assertEquals((0..9).toList(), plan.cells.map { it.index })
        assertTrue(plan.height > 0f)
        plan.cells.forEach { cell ->
            assertTrue(cell.width > 1f)
            assertTrue(cell.height > 1f)
            assertTrue(cell.left >= -0.5f)
            assertTrue(cell.right <= plan.width + 0.5f)
        }
    }

    @Test
    fun unboundedWidthDoesNotOverflowConstraintPacking() {
        val (width, height) = albumMosaicPixelSize(
            maxWidth = Int.MAX_VALUE,
            minWidth = 0,
            hasBoundedWidth = false,
            aspect = 800f / 1207959552f,
            fallbackWidth = ALBUM_BUBBLE_MAX_DP,
        )
        assertEquals(ALBUM_BUBBLE_MAX_DP, width)
        assertTrue(width in 1..MOSAIC_CONSTRAINT_MAX)
        assertTrue(height in 1..MOSAIC_CONSTRAINT_MAX)
        val huge = albumMosaicPixelSize(
            maxWidth = 1_000_000,
            minWidth = 0,
            hasBoundedWidth = true,
            aspect = 0.5f,
            fallbackWidth = ALBUM_BUBBLE_MAX_DP,
        )
        assertEquals(MOSAIC_CONSTRAINT_MAX, huge.first)
        assertTrue(huge.second in 1..MOSAIC_CONSTRAINT_MAX)
        val tall = albumMosaicPixelSize(
            maxWidth = 800,
            minWidth = 0,
            hasBoundedWidth = true,
            aspect = 0.4f,
            fallbackWidth = ALBUM_BUBBLE_MAX_DP,
        )
        assertEquals(800, tall.first)
        assertEquals((800 * MOSAIC_MAX_HEIGHT_RATIO).roundToInt(), tall.second)
    }

    @Test
    fun portraitLeadUsesTallLeftColumn() {
        val plan = layoutAlbum(listOf(0.7f, 16f / 9f, 16f / 9f))
        assertEquals(3, plan.cells.size)
        val left = plan.cells[0]
        assertEquals(0f, left.left, 0.5f)
        assertEquals(0f, left.top, 0.5f)
        assertEquals(plan.height, left.bottom, 1f)
        assertTrue(plan.cells[1].left >= left.right - 1f)
        assertTrue(plan.cells[2].top >= plan.cells[1].bottom - 1f)
    }
}
