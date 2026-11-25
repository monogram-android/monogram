package org.monogram.core.harmony

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalHarmonyPalette = staticCompositionLocalOf { harmonyPalette(HarmonyAccent.HarmonyBlue) }
val LocalHarmonyAccent = staticCompositionLocalOf { HarmonyAccent.HarmonyBlue }
val LocalHarmonyDarkTheme = staticCompositionLocalOf { false }
val LocalHarmonyGlow = staticCompositionLocalOf { Color(0xFF6EA8FF) }

object HarmonyTheme {
    val palette: HarmonyPalette
        @Composable get() = LocalHarmonyPalette.current

    val accent: HarmonyAccent
        @Composable get() = LocalHarmonyAccent.current

    val isDark: Boolean
        @Composable get() = LocalHarmonyDarkTheme.current

    val glow: Color
        @Composable get() = LocalHarmonyGlow.current
}

private fun harmonyColorScheme(palette: HarmonyPalette, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = palette.primary,
        onPrimary = palette.onPrimary,
        primaryContainer = palette.primaryContainer,
        onPrimaryContainer = palette.onPrimaryContainer,
        secondary = palette.secondary,
        onSecondary = palette.onPrimary,
        surface = palette.surfaceDark,
        onSurface = Color(0xFFE6EAF2),
        background = palette.backgroundDark,
        onBackground = Color(0xFFE6EAF2),
        surfaceVariant = palette.surfaceDark,
        onSurfaceVariant = Color(0xFFAAB4C4),
        outline = Color(0xFF4A5568),
        outlineVariant = Color(0xFF2A3342),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
    )
} else {
    lightColorScheme(
        primary = palette.primary,
        onPrimary = palette.onPrimary,
        primaryContainer = palette.primaryContainer,
        onPrimaryContainer = palette.onPrimaryContainer,
        secondary = palette.secondary,
        onSecondary = Color(0xFFFFFFFF),
        surface = palette.surfaceLight,
        onSurface = Color(0xFF1A1A1A),
        background = palette.backgroundLight,
        onBackground = Color(0xFF1A1A1A),
        surfaceVariant = Color(0xFFE9EDF3),
        onSurfaceVariant = Color(0xFF5E6673),
        outline = Color(0xFF737D8C),
        outlineVariant = Color(0xFFD3D9E2),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
    )
}

@Composable
fun HarmonyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: HarmonyAccent = HarmonyAccent.HarmonyBlue,
    content: @Composable () -> Unit,
) {
    val palette = remember(accent) { harmonyPalette(accent) }
    val colors = remember(palette, darkTheme) { harmonyColorScheme(palette, darkTheme) }

    CompositionLocalProvider(
        LocalHarmonyPalette provides palette,
        LocalHarmonyAccent provides accent,
        LocalHarmonyDarkTheme provides darkTheme,
        LocalHarmonyGlow provides palette.glow,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = HarmonyTokens.Typography,
            shapes = HarmonyTokens.Shapes,
            content = content,
        )
    }
}
