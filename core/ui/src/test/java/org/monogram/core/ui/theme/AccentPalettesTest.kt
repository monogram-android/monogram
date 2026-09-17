package org.monogram.core.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test
import org.monogram.core.ui.AccentPreset
import org.monogram.core.ui.AppearanceSettings

class AccentPalettesTest {
    @Test fun presetsKeepReadableForegroundsInBothThemes() {
        for (preset in AccentPreset.entries.filterNot { it == AccentPreset.Monogram }) {
            for (dark in listOf(false, true)) {
                val scheme = (if (dark) darkColorScheme() else lightColorScheme()).withAccent(preset, dark)
                for ((background, foreground) in listOf(
                    scheme.primary to scheme.onPrimary, scheme.primaryContainer to scheme.onPrimaryContainer,
                    scheme.secondary to scheme.onSecondary, scheme.secondaryContainer to scheme.onSecondaryContainer,
                    scheme.tertiary to scheme.onTertiary, scheme.tertiaryContainer to scheme.onTertiaryContainer,
                )) {
                    assertTrue("$preset dark=$dark", contrast(background, foreground) >= 4.5f)
                }
            }
        }
    }

    @Test fun storedPresetAndUnknownValueRestore() {
        assertEquals(AccentPreset.Sakura, AppearanceSettings.parseAccent("Sakura"))
        assertEquals(AccentPreset.Violet, AppearanceSettings.parseAccent("Violet"))
        assertEquals(AccentPreset.Teal, AppearanceSettings.parseAccent("Teal"))
        assertEquals(AccentPreset.Monogram, AppearanceSettings.parseAccent("unknown"))
        assertEquals(AccentPreset.Monogram, AppearanceSettings.parseAccent(null))
    }

    @Test fun messageTextSizeClampsAndFoldersDefaultOff() {
        assertEquals(12, AppearanceSettings.parseMessageTextSize(0))
        assertEquals(30, AppearanceSettings.parseMessageTextSize(99))
        assertEquals(16, AppearanceSettings.parseMessageTextSize(16))
        assertFalse(AppearanceSettings.state.value.foldersAtBottom)
    }

    @Test fun lineSpacingClampsAndSnapsToSteps() {
        assertEquals(0.9f, AppearanceSettings.parseLineSpacing(0.1f), 0.001f)
        assertEquals(1.5f, AppearanceSettings.parseLineSpacing(3f), 0.001f)
        assertEquals(1.2f, AppearanceSettings.parseLineSpacing(1.22f), 0.001f)
        assertEquals(1f, AppearanceSettings.parseLineSpacing(1f), 0.001f)
    }

    @Test fun letterSpacingClampsAndSnapsToSteps() {
        assertEquals(0f, AppearanceSettings.parseLetterSpacing(-1f), 0.001f)
        assertEquals(0.08f, AppearanceSettings.parseLetterSpacing(1f), 0.001f)
        assertEquals(0.05f, AppearanceSettings.parseLetterSpacing(0.054f), 0.001f)
    }

    @Test fun choosingPresetDisablesDynamicColorsAndRetainsSelectionWhenReenabled() {
        try {
            AppearanceSettings.setDynamicColor(true)
            AppearanceSettings.setAccentPreset(AccentPreset.Ocean)
            assertFalse(AppearanceSettings.state.value.dynamicColor)
            AppearanceSettings.setDynamicColor(true)
            assertEquals(AccentPreset.Ocean, AppearanceSettings.state.value.accentPreset)
        } finally {
            AppearanceSettings.setAccentPreset(AccentPreset.Monogram)
            AppearanceSettings.setDynamicColor(true)
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
    }
}
