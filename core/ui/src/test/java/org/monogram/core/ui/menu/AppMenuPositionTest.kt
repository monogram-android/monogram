package org.monogram.core.ui.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMenuPositionTest {
    @Test
    fun expandingMenuKeepsItsSideAndStaysWithinWindow() {
        val initial = place(height = 120)
        assertEquals(AppMenuGrowth.Below, initial.growth)
        val expanded = place(height = 360, preferredGrowth = initial.growth)
        assertEquals(AppMenuGrowth.Below, expanded.growth)
        assertTrue(expanded.offset.y >= 60)
        assertTrue(expanded.offset.y + 360 <= 740)
        assertEquals(initial, place(height = 120, preferredGrowth = expanded.growth))
    }

    @Test
    fun collapsingMenuDoesNotFlipFromAboveToBelow() {
        val initial = place(height = 360)
        assertEquals(AppMenuGrowth.Above, initial.growth)
        val collapsed = place(height = 120, preferredGrowth = initial.growth)
        assertEquals(AppMenuGrowth.Above, collapsed.growth)
        assertEquals(500 - 120 - 6, collapsed.offset.y)
    }

    private fun place(height: Int, preferredGrowth: AppMenuGrowth? = null) = appMenuPlacement(
        anchorLeft = 100, anchorTop = 500, anchorRight = 120, anchorBottom = 520,
        windowWidth = 400, windowHeight = 800, popupWidth = 240, popupHeight = height,
        alignToAnchorEnd = false, margin = 12, gap = 6, topInset = 48, bottomInset = 48,
        preferredGrowth = preferredGrowth,
    )
}
