package org.monogram.app.ui.theme

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

data class CustomThemePalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val background: Color,
    val surface: Color,
    val primaryContainer: Color,
    val secondaryContainer: Color,
    val tertiaryContainer: Color,
    val surfaceVariant: Color,
    val outline: Color
)

private val DarkColorScheme = darkColorScheme(
    primary = TelegramBlue80,
    secondary = TelegramBlueGrey80,
    tertiary = TelegramCyan80
)

private val LightColorScheme = lightColorScheme(
    primary = TelegramBlue40,
    secondary = TelegramBlueGrey40,
    tertiary = TelegramCyan40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun MonoGramTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    amoledTheme: Boolean = false,
    customThemePalette: CustomThemePalette? = null,
    content: @Composable () -> Unit
) {
    val customScheme = customThemePalette?.let { palette ->
        val onPrimary = readableTextOn(palette.primary)
        val onSecondary = readableTextOn(palette.secondary)
        val onTertiary = readableTextOn(palette.tertiary)
        val onBackground = readableTextOn(palette.background)
        val onSurface = readableTextOn(palette.surface)
        val onPrimaryContainer = readableTextOn(palette.primaryContainer)
        val onSecondaryContainer = readableTextOn(palette.secondaryContainer)
        val onTertiaryContainer = readableTextOn(palette.tertiaryContainer)
        // Keep subtitles/summary readable on dark cards even if surfaceVariant is user-set.
        val onSurfaceVariant = readableTextOnAll(listOf(palette.surfaceVariant, palette.surface, palette.background))

        if (darkTheme) {
            darkColorScheme(
                primary = palette.primary,
                secondary = palette.secondary,
                tertiary = palette.tertiary,
                background = palette.background,
                surface = palette.surface,
                primaryContainer = palette.primaryContainer,
                secondaryContainer = palette.secondaryContainer,
                tertiaryContainer = palette.tertiaryContainer,
                surfaceVariant = palette.surfaceVariant,
                outline = palette.outline,
                onPrimary = onPrimary,
                onSecondary = onSecondary,
                onTertiary = onTertiary,
                onBackground = onBackground,
                onSurface = onSurface,
                onPrimaryContainer = onPrimaryContainer,
                onSecondaryContainer = onSecondaryContainer,
                onTertiaryContainer = onTertiaryContainer,
                onSurfaceVariant = onSurfaceVariant
            )
        } else {
            lightColorScheme(
                primary = palette.primary,
                secondary = palette.secondary,
                tertiary = palette.tertiary,
                background = palette.background,
                surface = palette.surface,
                primaryContainer = palette.primaryContainer,
                secondaryContainer = palette.secondaryContainer,
                tertiaryContainer = palette.tertiaryContainer,
                surfaceVariant = palette.surfaceVariant,
                outline = palette.outline,
                onPrimary = onPrimary,
                onSecondary = onSecondary,
                onTertiary = onTertiary,
                onBackground = onBackground,
                onSurface = onSurface,
                onPrimaryContainer = onPrimaryContainer,
                onSecondaryContainer = onSecondaryContainer,
                onTertiaryContainer = onTertiaryContainer,
                onSurfaceVariant = onSurfaceVariant
            )
        }
    }

    val colorScheme = when {
        customScheme != null -> customScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) {
                val scheme = dynamicDarkColorScheme(context)
                if (amoledTheme) scheme.copy(background = Color.Black, surface = Color.Black) else scheme
            } else {
                dynamicLightColorScheme(context)
            }
        }

        darkTheme -> {
            if (amoledTheme) DarkColorScheme.copy(background = Color.Black, surface = Color.Black) else DarkColorScheme
        }
        else -> LightColorScheme
    }
    val view = LocalView.current
    val activity = LocalActivity.current
    if (!view.isInEditMode) {
        SideEffect {
            activity?.window?.let { WindowCompat.getInsetsController(it, view) }?.isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}

private fun readableTextOn(background: Color): Color {
    return if (contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)) Color.White else Color.Black
}

private fun readableTextOnAll(backgrounds: List<Color>): Color {
    val whiteScore = backgrounds.minOf { contrastRatio(Color.White, it) }
    val blackScore = backgrounds.minOf { contrastRatio(Color.Black, it) }
    return if (whiteScore >= blackScore) Color.White else Color.Black
}

private fun contrastRatio(foreground: Color, background: Color): Float {
    val fg = foreground.convert(ColorSpaces.Srgb).luminance()
    val bg = background.convert(ColorSpaces.Srgb).luminance()
    val lighter = maxOf(fg, bg)
    val darker = minOf(fg, bg)
    return (lighter + 0.05f) / (darker + 0.05f)
}
