package org.monogram.app

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import org.monogram.app.ui.theme.CustomThemePalette
import org.monogram.app.ui.theme.MonoGramTheme
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.NightMode
import java.util.*

@Composable
fun AppThemeContainer(
    appPreferences: AppPreferences,
    content: @Composable () -> Unit
) {
    val nightMode by appPreferences.nightMode.collectAsState()
    val isDynamicColorsEnabled by appPreferences.isDynamicColorsEnabled.collectAsState()
    val isAmoledThemeEnabled by appPreferences.isAmoledThemeEnabled.collectAsState()
    val isCustomThemeEnabled by appPreferences.isCustomThemeEnabled.collectAsState()
    val themePrimaryColor by appPreferences.themePrimaryColor.collectAsState()
    val themeSecondaryColor by appPreferences.themeSecondaryColor.collectAsState()
    val themeTertiaryColor by appPreferences.themeTertiaryColor.collectAsState()
    val themeBackgroundColor by appPreferences.themeBackgroundColor.collectAsState()
    val themeSurfaceColor by appPreferences.themeSurfaceColor.collectAsState()
    val themePrimaryContainerColor by appPreferences.themePrimaryContainerColor.collectAsState()
    val themeSecondaryContainerColor by appPreferences.themeSecondaryContainerColor.collectAsState()
    val themeTertiaryContainerColor by appPreferences.themeTertiaryContainerColor.collectAsState()
    val themeSurfaceVariantColor by appPreferences.themeSurfaceVariantColor.collectAsState()
    val themeOutlineColor by appPreferences.themeOutlineColor.collectAsState()
    val themeDarkPrimaryColor by appPreferences.themeDarkPrimaryColor.collectAsState()
    val themeDarkSecondaryColor by appPreferences.themeDarkSecondaryColor.collectAsState()
    val themeDarkTertiaryColor by appPreferences.themeDarkTertiaryColor.collectAsState()
    val themeDarkBackgroundColor by appPreferences.themeDarkBackgroundColor.collectAsState()
    val themeDarkSurfaceColor by appPreferences.themeDarkSurfaceColor.collectAsState()
    val themeDarkPrimaryContainerColor by appPreferences.themeDarkPrimaryContainerColor.collectAsState()
    val themeDarkSecondaryContainerColor by appPreferences.themeDarkSecondaryContainerColor.collectAsState()
    val themeDarkTertiaryContainerColor by appPreferences.themeDarkTertiaryContainerColor.collectAsState()
    val themeDarkSurfaceVariantColor by appPreferences.themeDarkSurfaceVariantColor.collectAsState()
    val themeDarkOutlineColor by appPreferences.themeDarkOutlineColor.collectAsState()
    val startTimeStr by appPreferences.nightModeStartTime.collectAsState()
    val endTimeStr by appPreferences.nightModeEndTime.collectAsState()
    val brightnessThreshold by appPreferences.nightModeBrightnessThreshold.collectAsState()

    val systemDark = isSystemInDarkTheme()
    var screenBrightness by remember { mutableFloatStateOf(1f) }
    val context = LocalContext.current

    DisposableEffect(nightMode) {
        if (nightMode == NightMode.BRIGHTNESS) {
            val contentResolver = context.contentResolver
            val listener = object :
                ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    val brightness =
                        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255)
                    screenBrightness = brightness / 255f
                }
            }
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                false,
                listener
            )
            // Initial value
            val brightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255)
            screenBrightness = brightness / 255f

            onDispose {
                contentResolver.unregisterContentObserver(listener)
            }
        } else {
            onDispose {}
        }
    }

    val darkTheme = when (nightMode) {
        NightMode.SYSTEM -> systemDark
        NightMode.LIGHT -> false
        NightMode.DARK -> true
        NightMode.SCHEDULED -> {
            val calendar = Calendar.getInstance()
            val nowHour = calendar.get(Calendar.HOUR_OF_DAY)
            val nowMinute = calendar.get(Calendar.MINUTE)
            val now = nowHour * 60 + nowMinute

            val startParts = startTimeStr.split(":")
            val start = if (startParts.size == 2) startParts[0].toInt() * 60 + startParts[1].toInt() else 22 * 60

            val endParts = endTimeStr.split(":")
            val end = if (endParts.size == 2) endParts[0].toInt() * 60 + endParts[1].toInt() else 7 * 60

            if (start < end) {
                now in start until end
            } else {
                now >= start || now < end
            }
        }

        NightMode.BRIGHTNESS -> {
            screenBrightness <= brightnessThreshold
        }
    }

    MonoGramTheme(
        darkTheme = darkTheme,
        dynamicColor = isDynamicColorsEnabled && !isCustomThemeEnabled,
        amoledTheme = isAmoledThemeEnabled && darkTheme,
        customThemePalette = if (isCustomThemeEnabled) {
            val palettePrimary = if (darkTheme) themeDarkPrimaryColor else themePrimaryColor
            val paletteSecondary = if (darkTheme) themeDarkSecondaryColor else themeSecondaryColor
            val paletteTertiary = if (darkTheme) themeDarkTertiaryColor else themeTertiaryColor
            val paletteBackground = if (darkTheme) themeDarkBackgroundColor else themeBackgroundColor
            val paletteSurface = if (darkTheme) themeDarkSurfaceColor else themeSurfaceColor
            val palettePrimaryContainer = if (darkTheme) themeDarkPrimaryContainerColor else themePrimaryContainerColor
            val paletteSecondaryContainer = if (darkTheme) themeDarkSecondaryContainerColor else themeSecondaryContainerColor
            val paletteTertiaryContainer = if (darkTheme) themeDarkTertiaryContainerColor else themeTertiaryContainerColor
            val paletteSurfaceVariant = if (darkTheme) themeDarkSurfaceVariantColor else themeSurfaceVariantColor
            val paletteOutline = if (darkTheme) themeDarkOutlineColor else themeOutlineColor
            CustomThemePalette(
                primary = Color(palettePrimary),
                secondary = Color(paletteSecondary),
                tertiary = Color(paletteTertiary),
                background = if (isAmoledThemeEnabled && darkTheme) Color.Black else Color(paletteBackground),
                surface = if (isAmoledThemeEnabled && darkTheme) Color.Black else Color(paletteSurface),
                primaryContainer = Color(palettePrimaryContainer),
                secondaryContainer = Color(paletteSecondaryContainer),
                tertiaryContainer = Color(paletteTertiaryContainer),
                surfaceVariant = Color(paletteSurfaceVariant),
                outline = Color(paletteOutline)
            )
        } else {
            null
        }
    ) {
        content()
    }
}
