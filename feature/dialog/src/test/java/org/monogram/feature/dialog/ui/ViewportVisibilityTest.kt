package org.monogram.feature.dialog.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportVisibilityTest {
    @Test
    fun onscreenItemIsVisible() {
        assertTrue(isInViewport(0f, 100f, 200f, 300f, 1080, 2400, 0f))
    }

    @Test
    fun farOffscreenItemStaysHidden() {
        assertFalse(isInViewport(0f, 4000f, 200f, 4200f, 1080, 2400, 0f))
    }

    @Test
    fun lookaheadStartsRenderBeforeItemEnters() {
        assertFalse(isInViewport(0f, 2500f, 200f, 2700f, 1080, 2400, 0f))
        assertTrue(isInViewport(0f, 2500f, 200f, 2700f, 1080, 2400, 400f))
    }

    @Test
    fun emptyOrDetachedBoundsAreHidden() {
        assertFalse(isInViewport(10f, 10f, 10f, 50f, 1080, 2400, 100f))
        assertFalse(isInViewport(0f, 0f, 100f, 100f, 0, 2400, 100f))
    }
}
