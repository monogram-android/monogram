package org.monogram.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.monogram.core.ui.AccentPreset

private data class AccentColors(
    val primary: Color, val primaryDark: Color,
    val secondary: Color, val secondaryDark: Color,
    val tertiary: Color, val tertiaryDark: Color,
)

private fun accentColors(preset: AccentPreset): AccentColors = when (preset) {
    AccentPreset.Monogram -> AccentColors(Color(0xFF006A8C), Color(0xFF77D1F5), Color(0xFF4D616C), Color(0xFFB5CAD6), Color(0xFF655B7B), Color(0xFFD0BFE7))
    AccentPreset.Sakura -> AccentColors(Color(0xFF984061), Color(0xFFFFB0CB), Color(0xFF765565), Color(0xFFE5BDCF), Color(0xFF775A2E), Color(0xFFECC18C))
    AccentPreset.Ocean -> AccentColors(Color(0xFF006780), Color(0xFF65D5F3), Color(0xFF4A626B), Color(0xFFB1CBD5), Color(0xFF52652C), Color(0xFFBAD18E))
    AccentPreset.Forest -> AccentColors(Color(0xFF356A3D), Color(0xFF9BD5A0), Color(0xFF54634B), Color(0xFFBCCCAF), Color(0xFF836000), Color(0xFFF2C14D))
    AccentPreset.Sunset -> AccentColors(Color(0xFF9A4526), Color(0xFFFFB59A), Color(0xFF795747), Color(0xFFE9BFA8), Color(0xFF666018), Color(0xFFD2CB80))
    AccentPreset.Violet -> AccentColors(Color(0xFF6750A4), Color(0xFFD0BCFF), Color(0xFF625B71), Color(0xFFCCC2DC), Color(0xFF7D5260), Color(0xFFEFB8C8))
    AccentPreset.Amber -> AccentColors(Color(0xFF785900), Color(0xFFFABD00), Color(0xFF6B5E3B), Color(0xFFD8C7A1), Color(0xFF4A6700), Color(0xFFB1D18A))
    AccentPreset.Rose -> AccentColors(Color(0xFF8C4A62), Color(0xFFFFB1C8), Color(0xFF6F5B62), Color(0xFFD8C2C8), Color(0xFF7A5730), Color(0xFFEBBB93))
    AccentPreset.Slate -> AccentColors(Color(0xFF515E7D), Color(0xFFB9C6E9), Color(0xFF5B5F6A), Color(0xFFC3C6D2), Color(0xFF6B5B4A), Color(0xFFD8C4B0))
    AccentPreset.Teal -> AccentColors(Color(0xFF006A65), Color(0xFF4DDAD3), Color(0xFF4A6361), Color(0xFFB1CBC8), Color(0xFF4A5F80), Color(0xFFB5C7EA))
}

fun accentSwatch(preset: AccentPreset): Color = accentColors(preset).primary

internal fun ColorScheme.withAccent(preset: AccentPreset, dark: Boolean): ColorScheme {
    if (preset == AccentPreset.Monogram) return this
    val colors = accentColors(preset)
    fun container(light: Color, night: Color) = if (dark) lerp(light, Color.Black, .35f) else lerp(night, Color.White, .65f)
    fun onContainer(light: Color, night: Color) = if (dark) lerp(night, Color.White, .65f) else lerp(light, Color.Black, .7f)
    return copy(
        primary = if (dark) colors.primaryDark else colors.primary,
        onPrimary = if (dark) lerp(colors.primary, Color.Black, .7f) else Color.White,
        primaryContainer = container(colors.primary, colors.primaryDark),
        onPrimaryContainer = onContainer(colors.primary, colors.primaryDark),
        inversePrimary = if (dark) colors.primary else colors.primaryDark,
        surfaceTint = if (dark) colors.primaryDark else colors.primary,
        secondary = if (dark) colors.secondaryDark else colors.secondary,
        onSecondary = if (dark) lerp(colors.secondary, Color.Black, .7f) else Color.White,
        secondaryContainer = container(colors.secondary, colors.secondaryDark),
        onSecondaryContainer = onContainer(colors.secondary, colors.secondaryDark),
        tertiary = if (dark) colors.tertiaryDark else colors.tertiary,
        onTertiary = if (dark) lerp(colors.tertiary, Color.Black, .7f) else Color.White,
        tertiaryContainer = container(colors.tertiary, colors.tertiaryDark),
        onTertiaryContainer = onContainer(colors.tertiary, colors.tertiaryDark),
        primaryFixed = lerp(colors.primaryDark, Color.White, .65f),
        primaryFixedDim = colors.primaryDark,
        onPrimaryFixed = lerp(colors.primary, Color.Black, .7f),
        onPrimaryFixedVariant = colors.primary,
        secondaryFixed = lerp(colors.secondaryDark, Color.White, .65f),
        secondaryFixedDim = colors.secondaryDark,
        onSecondaryFixed = lerp(colors.secondary, Color.Black, .7f),
        onSecondaryFixedVariant = colors.secondary,
        tertiaryFixed = lerp(colors.tertiaryDark, Color.White, .65f),
        tertiaryFixedDim = colors.tertiaryDark,
        onTertiaryFixed = lerp(colors.tertiary, Color.Black, .7f),
        onTertiaryFixedVariant = colors.tertiary,
    )
}
