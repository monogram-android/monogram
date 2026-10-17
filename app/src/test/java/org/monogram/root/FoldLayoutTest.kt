package org.monogram.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoldLayoutTest {
    @Test
    fun verticalSeparatingHingeSelectsBookMode() {
        val bounds = FoldBounds(1200, 0, 1240, 1800)
        val layout = foldLayout(bounds, true, true, 2400, 1800)

        assertEquals(FoldLayoutMode.VERTICAL_BOOK, layout.mode)
        assertEquals(bounds, layout.hinge)
    }

    @Test
    fun horizontalSeparatingHingeSelectsTabletopMode() {
        val layout = foldLayout(FoldBounds(0, 900, 1800, 940), true, false, 1800, 1800)

        assertEquals(FoldLayoutMode.HORIZONTAL_TABLETOP, layout.mode)
    }

    @Test
    fun tabletopHingeExposesUsableTopRegion() {
        val layout = foldLayout(FoldBounds(0, 900, 1800, 940), true, false, 1800, 1800)

        assertEquals(900, layout.hinge?.top)
        assertEquals(40, layout.hinge?.height)
    }

    @Test
    fun nonSeparatingOrInvalidHingeFallsBackToNormalLayout() {
        assertEquals(FoldLayoutMode.NONE, foldLayout(FoldBounds(1200, 0, 1240, 1800), false, true, 2400, 1800).mode)
        assertEquals(FoldLayoutMode.NONE, foldLayout(FoldBounds(1200, 0, 1240, 1900), true, true, 2400, 1800).mode)
        assertNull(foldLayout(null, true, true, 2400, 1800).hinge)
    }
}
