package org.monogram.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperModeTest {
    @Test fun newInstallUsesMonogram() {
        assertEquals(WallpaperMode.Monogram, restoreWallpaperMode(null, null))
    }

    @Test fun legacyImageIsPreserved() {
        assertEquals(WallpaperMode.Image, restoreWallpaperMode(null, "/wallpaper.jpg"))
    }

    @Test fun noWallpaperSurvivesRestore() {
        assertEquals(WallpaperMode.None, restoreWallpaperMode("None", null))
    }

    @Test fun missingImageFallsBackToDefault() {
        assertEquals(WallpaperMode.Monogram, restoreWallpaperMode("Image", null))
    }
}
